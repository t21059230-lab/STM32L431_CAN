package com.example.canphon.managers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sensor Fusion GPS - 100Hz Position Estimation
 * 
 * نظام دمج الحساسات للحصول على موقع بمعدل 100Hz
 * 
 * يجمع:
 * - GPS (1Hz) ← الموقع المرجعي
 * - Accelerometer (400Hz) ← حساب السرعة
 * - Gyroscope (400Hz) ← حساب الاتجاه
 * 
 * النتيجة: موقع دقيق 100 مرة في الثانية!
 */
class SensorFusionGPS(private val context: Context) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "SensorFusionGPS"
        
        // Output rate: 100Hz (10ms)
        private const val OUTPUT_INTERVAL_MS = 10L
        
        // Earth constants
        private const val EARTH_RADIUS_M = 6371000.0
        
        // Gravity constant
        private const val GRAVITY = 9.81f
        
        // Conversion factors
        private const val DEG_TO_RAD = Math.PI / 180.0
        private const val RAD_TO_DEG = 180.0 / Math.PI
        
        // Meters per degree (approximate at equator)
        private const val METERS_PER_DEG_LAT = 111320.0
    }

    // Managers
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    // Sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    
    // State
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    
    // === GPS State ===
    private var lastGpsLatitude = 0.0
    private var lastGpsLongitude = 0.0
    private var lastGpsAltitude = 0.0
    private var lastGpsAccuracy = 0f
    private var lastGpsTime = 0L
    private var hasGpsFix = false
    
    // === Fused Position (100Hz output) ===
    @Volatile var fusedLatitude = 0.0
        private set
    @Volatile var fusedLongitude = 0.0
        private set
    @Volatile var fusedAltitude = 0.0
        private set
    @Volatile var fusedSpeed = 0.0
        private set
    @Volatile var fusedBearing = 0.0  // heading in degrees
        private set
    
    // === IMU State ===
    // Linear acceleration (gravity removed)
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    
    // Gyroscope (rotation rate)
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    
    // Orientation (from rotation vector)
    private var orientationAzimuth = 0f  // heading
    private var orientationPitch = 0f
    private var orientationRoll = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // === Dead Reckoning State ===
    // Velocity in local frame (m/s)
    private var velocityNorth = 0.0
    private var velocityEast = 0.0
    private var velocityDown = 0.0
    
    // Position offset from last GPS fix (meters)
    private var offsetNorth = 0.0
    private var offsetEast = 0.0
    private var offsetDown = 0.0
    
    // Timing
    private var lastImuTime = 0L
    private var lastOutputTime = 0L
    
    // === Statistics ===
    @Volatile var outputCount = 0L
        private set
    @Volatile var gpsFixCount = 0L
        private set
    @Volatile var outputRateHz = 0.0
        private set
    private var lastStatsTime = 0L
    private var outputCountSinceStats = 0L
    
    // === Callbacks ===
    var onPositionUpdate: ((latitude: Double, longitude: Double, altitude: Double, speed: Double, bearing: Double) -> Unit)? = null
    var onGpsFix: ((latitude: Double, longitude: Double, accuracy: Float) -> Unit)? = null
    
    // === Output Loop ===
    private val outputLoop = object : Runnable {
        override fun run() {
            if (isRunning) {
                calculateFusedPosition()
                
                // Notify listener
                onPositionUpdate?.invoke(
                    fusedLatitude,
                    fusedLongitude,
                    fusedAltitude,
                    fusedSpeed,
                    fusedBearing
                )
                
                // Update stats
                outputCount++
                outputCountSinceStats++
                
                val now = System.currentTimeMillis()
                if (now - lastStatsTime >= 1000) {
                    outputRateHz = outputCountSinceStats.toDouble()
                    outputCountSinceStats = 0
                    lastStatsTime = now
                }
                
                handler.postDelayed(this, OUTPUT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Start Sensor Fusion GPS
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        
        // Reset state
        resetState()
        
        // Register IMU sensors at maximum rate
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Accelerometer registered @ FASTEST")
        }
        
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Gyroscope registered @ FASTEST")
        }
        
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Rotation Vector registered @ FASTEST")
        }
        
        // Start GPS
        startGps()
        
        // Start output loop (100Hz)
        lastStatsTime = System.currentTimeMillis()
        handler.post(outputLoop)
        
        Log.i(TAG, "Sensor Fusion GPS started @ 100Hz")
    }
    
    /**
     * Stop Sensor Fusion GPS
     */
    fun stop() {
        isRunning = false
        
        // Unregister sensors
        sensorManager.unregisterListener(this)
        
        // Stop GPS
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(this)
        }
        
        // Stop output loop
        handler.removeCallbacks(outputLoop)
        
        Log.i(TAG, "Sensor Fusion GPS stopped")
    }
    
    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // 1. Get last known location immediately (works indoors!)
            try {
                val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                
                // Use the most recent location
                val lastLocation = when {
                    lastGps != null && lastNetwork != null -> {
                        if (lastGps.time > lastNetwork.time) lastGps else lastNetwork
                    }
                    lastGps != null -> lastGps
                    lastNetwork != null -> lastNetwork
                    else -> null
                }
                
                lastLocation?.let {
                    lastGpsLatitude = it.latitude
                    lastGpsLongitude = it.longitude
                    lastGpsAltitude = it.altitude
                    lastGpsAccuracy = it.accuracy
                    fusedLatitude = it.latitude
                    fusedLongitude = it.longitude
                    fusedAltitude = it.altitude
                    hasGpsFix = true
                    Log.i(TAG, "Initial location from cache: $lastGpsLatitude, $lastGpsLongitude")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last location: ${e.message}")
            }
            
            // 2. Request GPS at maximum rate
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,      // minimum time (as fast as possible)
                0f,      // minimum distance (every update)
                this
            )
            Log.i(TAG, "GPS Provider started")
            
            // 3. Request Network updates (works indoors via WiFi/Cell towers!)
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        this
                    )
                    Log.i(TAG, "Network Provider started (WiFi/Cell)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network provider not available: ${e.message}")
            }
        } else {
            Log.w(TAG, "GPS permission not granted")
        }
    }
    
    private fun resetState() {
        // Reset position
        fusedLatitude = 0.0
        fusedLongitude = 0.0
        fusedAltitude = 0.0
        fusedSpeed = 0.0
        fusedBearing = 0.0
        
        // Reset velocity
        velocityNorth = 0.0
        velocityEast = 0.0
        velocityDown = 0.0
        
        // Reset offsets
        offsetNorth = 0.0
        offsetEast = 0.0
        offsetDown = 0.0
        
        // Reset flags
        hasGpsFix = false
        lastImuTime = 0L
        lastOutputTime = 0L
        
        // Reset stats
        outputCount = 0
        gpsFixCount = 0
        outputRateHz = 0.0
        outputCountSinceStats = 0
    }
    
    // ==========================================
    // SENSOR CALLBACKS
    // ==========================================
    
    override fun onSensorChanged(event: SensorEvent) {
        val now = System.nanoTime()
        
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
                
                // Integrate acceleration to update velocity
                if (lastImuTime > 0 && hasGpsFix) {
                    val dt = (now - lastImuTime) / 1_000_000_000.0  // nanoseconds to seconds
                    integrateImu(dt)
                }
                lastImuTime = now
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
            }
            
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Get orientation from rotation vector
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                
                orientationAzimuth = (orientationAngles[0] * RAD_TO_DEG).toFloat()  // -180 to 180
                orientationPitch = (orientationAngles[1] * RAD_TO_DEG).toFloat()
                orientationRoll = (orientationAngles[2] * RAD_TO_DEG).toFloat()
                
                // Normalize azimuth to 0-360
                if (orientationAzimuth < 0) orientationAzimuth += 360f
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    // ==========================================
    // GPS CALLBACKS
    // ==========================================
    
    override fun onLocationChanged(location: Location) {
        // Store GPS data
        lastGpsLatitude = location.latitude
        lastGpsLongitude = location.longitude
        lastGpsAltitude = location.altitude
        lastGpsAccuracy = location.accuracy
        lastGpsTime = System.currentTimeMillis()
        
        // First GPS fix - initialize fused position
        if (!hasGpsFix) {
            fusedLatitude = lastGpsLatitude
            fusedLongitude = lastGpsLongitude
            fusedAltitude = lastGpsAltitude
            hasGpsFix = true
            Log.i(TAG, "First GPS fix: $lastGpsLatitude, $lastGpsLongitude")
        } else {
            // GPS correction - reset drift
            correctWithGps()
        }
        
        gpsFixCount++
        onGpsFix?.invoke(lastGpsLatitude, lastGpsLongitude, lastGpsAccuracy)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    
    // ==========================================
    // SENSOR FUSION CORE
    // ==========================================
    
    /**
     * Integrate IMU data to update velocity and position offset
     * Called at ~400Hz
     */
    private fun integrateImu(dt: Double) {
        // Convert phone accelerations to North-East-Down frame
        // Using rotation matrix from rotation vector sensor
        
        val azimuthRad = orientationAzimuth * DEG_TO_RAD
        
        // Rotate acceleration from phone frame to world frame
        // Simplified: assuming phone is roughly horizontal
        val accelNorth = (-accelY * cos(azimuthRad) - accelX * sin(azimuthRad)).toDouble()
        val accelEast = (-accelY * sin(azimuthRad) + accelX * cos(azimuthRad)).toDouble()
        val accelDown = accelZ.toDouble()
        
        // Apply simple low-pass filter to reduce noise
        val alpha = 0.1
        
        // Integrate acceleration to velocity (with decay to prevent drift)
        val decay = 0.999  // slight decay to reduce drift when stationary
        velocityNorth = (velocityNorth + accelNorth * dt) * decay
        velocityEast = (velocityEast + accelEast * dt) * decay
        velocityDown = (velocityDown + accelDown * dt) * decay
        
        // Integrate velocity to position offset
        offsetNorth += velocityNorth * dt
        offsetEast += velocityEast * dt
        offsetDown += velocityDown * dt
        
        // Calculate speed
        fusedSpeed = sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast)
        
        // Update bearing from velocity (if moving)
        if (fusedSpeed > 0.5) {  // Only update if moving > 0.5 m/s
            fusedBearing = Math.toDegrees(kotlin.math.atan2(velocityEast, velocityNorth))
            if (fusedBearing < 0) fusedBearing += 360.0
        } else {
            // Use phone orientation when stationary
            fusedBearing = orientationAzimuth.toDouble()
        }
    }
    
    /**
     * Correct position with GPS fix
     * Called at ~1Hz when GPS updates
     */
    private fun correctWithGps() {
        // Smoothly correct to GPS position
        // This prevents jumps while correcting drift
        
        val alpha = 0.8  // How much to trust GPS (0.0-1.0)
        
        // Calculate current fused position
        val currentLat = lastGpsLatitude + (offsetNorth / METERS_PER_DEG_LAT)
        val currentLon = lastGpsLongitude + (offsetEast / (METERS_PER_DEG_LAT * cos(lastGpsLatitude * DEG_TO_RAD)))
        
        // Blend with GPS
        fusedLatitude = alpha * lastGpsLatitude + (1 - alpha) * currentLat
        fusedLongitude = alpha * lastGpsLongitude + (1 - alpha) * currentLon
        fusedAltitude = alpha * lastGpsAltitude + (1 - alpha) * (lastGpsAltitude + offsetDown)
        
        // Reset offsets (position now at GPS)
        offsetNorth = 0.0
        offsetEast = 0.0
        offsetDown = 0.0
        
        // Optionally correct velocity based on GPS speed
        // (GPS speed is usually accurate)
        
        Log.d(TAG, "GPS correction applied. Accuracy: ${lastGpsAccuracy}m")
    }
    
    /**
     * Calculate fused position from GPS + IMU offsets
     * Called at 100Hz (every 10ms)
     */
    private fun calculateFusedPosition() {
        if (!hasGpsFix) return
        
        // Convert offsets (meters) to lat/lon degrees
        val latOffset = offsetNorth / METERS_PER_DEG_LAT
        val lonOffset = offsetEast / (METERS_PER_DEG_LAT * cos(lastGpsLatitude * DEG_TO_RAD))
        
        // Apply offsets to last GPS position
        fusedLatitude = lastGpsLatitude + latOffset
        fusedLongitude = lastGpsLongitude + lonOffset
        fusedAltitude = lastGpsAltitude + offsetDown
    }
    
    // ==========================================
    // PUBLIC GETTERS
    // ==========================================
    
    /**
     * Get current fused position as Location object
     */
    fun getFusedLocation(): Location {
        return Location("SensorFusion").apply {
            latitude = fusedLatitude
            longitude = fusedLongitude
            altitude = fusedAltitude
            speed = fusedSpeed.toFloat()
            bearing = fusedBearing.toFloat()
            accuracy = lastGpsAccuracy
            time = System.currentTimeMillis()
        }
    }
    
    /**
     * Check if we have a valid GPS fix
     */
    fun hasValidFix(): Boolean = hasGpsFix
    
    /**
     * Get time since last GPS fix in milliseconds
     */
    fun getTimeSinceGpsFix(): Long {
        return if (lastGpsTime > 0) {
            System.currentTimeMillis() - lastGpsTime
        } else {
            Long.MAX_VALUE
        }
    }
}

