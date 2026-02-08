package com.example.canphon.ui
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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.os.Build
import java.util.Locale

/**
 * Raw Sensors Activity - Direct Hardware Access
 * 
 * وصول مباشر لجميع حساسات الهاتف بدون فلترة
 * 
 * Features:
 * - All sensors at SENSOR_DELAY_FASTEST (no filtering)
 * - Raw GPS data (no Kalman filtering)
 * - Real-time display at maximum rate
 * - Data export capability
 */
class RawSensorsActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "RawSensors"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val UI_UPDATE_INTERVAL_MS = 50L // 20Hz UI updates
    }

    // Sensor Manager
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    // All available sensors
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var barometer: Sensor? = null
    private var gravity: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private var rotationVector: Sensor? = null
    private var gameRotationVector: Sensor? = null
    private var proximity: Sensor? = null
    private var light: Sensor? = null
    private var temperature: Sensor? = null
    private var humidity: Sensor? = null
    private var uncalibratedGyro: Sensor? = null
    private var uncalibratedMag: Sensor? = null

    // Raw sensor data (unfiltered!)
    private var accelX = 0f; private var accelY = 0f; private var accelZ = 0f
    private var gyroX = 0f; private var gyroY = 0f; private var gyroZ = 0f
    private var magX = 0f; private var magY = 0f; private var magZ = 0f
    private var pressure = 0f
    private var gravityX = 0f; private var gravityY = 0f; private var gravityZ = 0f
    private var linearX = 0f; private var linearY = 0f; private var linearZ = 0f
    private var rotX = 0f; private var rotY = 0f; private var rotZ = 0f; private var rotW = 0f
    private var proximityValue = 0f
    private var lightValue = 0f
    private var temperatureValue = 0f
    private var humidityValue = 0f
    private var uncalGyroX = 0f; private var uncalGyroY = 0f; private var uncalGyroZ = 0f
    private var uncalMagX = 0f; private var uncalMagY = 0f; private var uncalMagZ = 0f

    // GPS raw data
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var speed = 0f
    private var accuracy = 0f
    private var bearing = 0f
    private var satellites = 0
    private var gpsTime = 0L

    // Sensor counts
    private var accelCount = 0L
    private var gyroCount = 0L
    private var magCount = 0L
    private var gpsCount = 0L

    // UI Elements
    private lateinit var tvAccel: TextView
    private lateinit var tvGyro: TextView
    private lateinit var tvMag: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvGravity: TextView
    private lateinit var tvLinear: TextView
    private lateinit var tvRotation: TextView
    private lateinit var tvGPS: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvUncalGyro: TextView
    private lateinit var tvUncalMag: TextView
    private lateinit var btnStartStop: Button
    private lateinit var scrollView: ScrollView

    // Sensor Fusion GPS
    private lateinit var sensorFusionGPS: SensorFusionGPS
    private lateinit var tvFusedGPS: TextView

    // Raw GNSS
    private var rawGNSS: RawGNSS? = null
    private lateinit var tvRawGNSS: TextView

    // State
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L

    // UI Update runnable (throttled to 20Hz)
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateUI()
                handler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Create UI programmatically (no XML needed)
        createUI()
        
        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Initialize Sensor Fusion GPS
        sensorFusionGPS = SensorFusionGPS(this)
        
        // Initialize Raw GNSS (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rawGNSS = RawGNSS(this)
            Log.i(TAG, "Raw GNSS initialized")
        } else {
            Log.w(TAG, "Raw GNSS requires Android 7.0+")
        }
        
        // Discover all sensors
        discoverSensors()
        
        // Request location permission
        requestLocationPermission()
        
        Log.i(TAG, "RawSensorsActivity created")
    }

    private fun createUI() {
        scrollView = ScrollView(this)
        scrollView.setBackgroundColor(0xFF1A1A2E.toInt()) // Dark background
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val title = TextView(this).apply {
            text = "📡 Raw Sensors - حساسات خام"
            textSize = 24f
            setTextColor(0xFF4ECCA3.toInt())
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        // Start/Stop Button
        btnStartStop = Button(this).apply {
            text = "▶ START"
            textSize = 18f
            setBackgroundColor(0xFF4ECCA3.toInt())
            setTextColor(0xFF1A1A2E.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener { toggleSensors() }
        }
        layout.addView(btnStartStop)

        // Spacer
        layout.addView(View(this).apply { 
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 32
            )
        })

        // Stats
        tvStats = createSensorTextView("📊 Stats")
        layout.addView(tvStats)

        // Accelerometer
        tvAccel = createSensorTextView("📱 Accelerometer (m/s²)")
        layout.addView(tvAccel)

        // Gyroscope
        tvGyro = createSensorTextView("🔄 Gyroscope (rad/s)")
        layout.addView(tvGyro)

        // Uncalibrated Gyroscope
        tvUncalGyro = createSensorTextView("🔄 Uncalibrated Gyro (rad/s)")
        layout.addView(tvUncalGyro)

        // Magnetometer
        tvMag = createSensorTextView("🧭 Magnetometer (μT)")
        layout.addView(tvMag)

        // Uncalibrated Magnetometer
        tvUncalMag = createSensorTextView("🧭 Uncalibrated Mag (μT)")
        layout.addView(tvUncalMag)

        // Pressure
        tvPressure = createSensorTextView("🌡 Pressure (hPa)")
        layout.addView(tvPressure)

        // Gravity
        tvGravity = createSensorTextView("⬇ Gravity (m/s²)")
        layout.addView(tvGravity)

        // Linear Acceleration
        tvLinear = createSensorTextView("➡ Linear Accel (m/s²)")
        layout.addView(tvLinear)

        // Rotation Vector
        tvRotation = createSensorTextView("🔀 Rotation Vector")
        layout.addView(tvRotation)

        // GPS
        tvGPS = createSensorTextView("🛰 GPS Raw (1Hz)")
        layout.addView(tvGPS)

        // Fused GPS (100Hz)
        tvFusedGPS = createSensorTextView("🚀 Sensor Fusion GPS (100Hz)")
        tvFusedGPS.setBackgroundColor(0xFF2E4057.toInt())  // Different color for fusion
        layout.addView(tvFusedGPS)

        // Raw GNSS (Pseudorange, Carrier Phase, etc.)
        tvRawGNSS = createSensorTextView("🛠 Raw GNSS Measurements")
        tvRawGNSS.setBackgroundColor(0xFF4A235A.toInt())  // Purple for raw GNSS
        layout.addView(tvRawGNSS)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun createSensorTextView(title: String): TextView {
        return TextView(this).apply {
            text = "$title\n---"
            textSize = 14f
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(0xFF232741.toInt())
            setPadding(24, 16, 24, 16)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun discoverSensors() {
        // Get all available sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        temperature = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        humidity = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        uncalibratedGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        uncalibratedMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

        // Log available sensors
        val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)
        Log.i(TAG, "Available sensors: ${sensorList.size}")
        sensorList.forEach { sensor ->
            Log.d(TAG, "  ${sensor.name}: ${sensor.type}")
        }
    }

    private fun toggleSensors() {
        if (isRunning) {
            stopSensors()
        } else {
            startSensors()
        }
    }

    private fun startSensors() {
        isRunning = true
        startTime = System.currentTimeMillis()
        accelCount = 0; gyroCount = 0; magCount = 0; gpsCount = 0

        btnStartStop.text = "⏹ STOP"
        btnStartStop.setBackgroundColor(0xFFFF6B6B.toInt())

        // Register ALL sensors at FASTEST rate (no filtering!)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Accelerometer registered @ FASTEST")
        }
        
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Gyroscope registered @ FASTEST")
        }
        
        uncalibratedGyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Uncalibrated Gyroscope registered @ FASTEST")
        }
        
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Magnetometer registered @ FASTEST")
        }
        
        uncalibratedMag?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Uncalibrated Magnetometer registered @ FASTEST")
        }
        
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Barometer registered @ FASTEST")
        }
        
        gravity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Gravity registered @ FASTEST")
        }
        
        linearAcceleration?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Linear Acceleration registered @ FASTEST")
        }
        
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Rotation Vector registered @ FASTEST")
        }
        
        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        
        light?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // Start GPS updates (fastest possible)
        startGPS()

        // Start Sensor Fusion GPS (100Hz output)
        sensorFusionGPS.start()

        // Start Raw GNSS (if supported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rawGNSS?.start()
        }

        // Start UI update loop
        handler.post(uiUpdateRunnable)

        Log.i(TAG, "All sensors started @ SENSOR_DELAY_FASTEST")
    }

    private fun stopSensors() {
        isRunning = false
        
        btnStartStop.text = "▶ START"
        btnStartStop.setBackgroundColor(0xFF4ECCA3.toInt())

        // Unregister all sensors
        sensorManager.unregisterListener(this)

        // Stop GPS
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(this)
        }

        // Stop Sensor Fusion GPS
        sensorFusionGPS.stop()

        // Stop Raw GNSS
        rawGNSS?.stop()

        handler.removeCallbacks(uiUpdateRunnable)

        Log.i(TAG, "All sensors stopped")
    }

    private fun startGPS() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
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
                    latitude = it.latitude
                    longitude = it.longitude
                    altitude = it.altitude
                    accuracy = it.accuracy
                    Log.i(TAG, "Last known location: $latitude, $longitude (accuracy: ${accuracy}m)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last location: ${e.message}")
            }
            
            // 2. Request GPS updates at maximum rate
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,      // Minimum time interval (0 = as fast as possible)
                0f,      // Minimum distance (0 = every update)
                this
            )
            Log.i(TAG, "GPS Provider started @ maximum rate")
            
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
        }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Location permission granted")
            }
        }
    }

    // ==========================================
    // SENSOR EVENT LISTENER - RAW DATA!
    // ==========================================
    override fun onSensorChanged(event: SensorEvent) {
        // NO FILTERING! Direct raw values!
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
                accelCount++
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
                gyroCount++
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                uncalGyroX = event.values[0]
                uncalGyroY = event.values[1]
                uncalGyroZ = event.values[2]
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magX = event.values[0]
                magY = event.values[1]
                magZ = event.values[2]
                magCount++
            }
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                uncalMagX = event.values[0]
                uncalMagY = event.values[1]
                uncalMagZ = event.values[2]
            }
            Sensor.TYPE_PRESSURE -> {
                pressure = event.values[0]
            }
            Sensor.TYPE_GRAVITY -> {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearX = event.values[0]
                linearY = event.values[1]
                linearZ = event.values[2]
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                rotX = event.values[0]
                rotY = event.values[1]
                rotZ = event.values[2]
                if (event.values.size > 3) rotW = event.values[3]
            }
            Sensor.TYPE_PROXIMITY -> {
                proximityValue = event.values[0]
            }
            Sensor.TYPE_LIGHT -> {
                lightValue = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} -> $accuracy")
    }

    // ==========================================
    // LOCATION LISTENER - RAW GPS DATA!
    // ==========================================
    override fun onLocationChanged(location: Location) {
        // RAW GPS values - no Kalman filter!
        latitude = location.latitude
        longitude = location.longitude
        altitude = location.altitude
        speed = location.speed
        accuracy = location.accuracy
        bearing = location.bearing
        gpsTime = location.time
        gpsCount++

        // Get satellite count if available
        val extras = location.extras
        satellites = extras?.getInt("satellites", 0) ?: 0
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ==========================================
    // UI UPDATE (Throttled to 20Hz)
    // ==========================================
    private fun updateUI() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        // Stats
        val accelHz = if (elapsed > 0) (accelCount / elapsed).toInt() else 0
        val gyroHz = if (elapsed > 0) (gyroCount / elapsed).toInt() else 0
        val magHz = if (elapsed > 0) (magCount / elapsed).toInt() else 0
        
        tvStats.text = String.format(Locale.US,
            "📊 Stats\nTime: %.1fs | Accel: %dHz | Gyro: %dHz | Mag: %dHz | GPS: %d fixes",
            elapsed, accelHz, gyroHz, magHz, gpsCount
        )

        // Accelerometer
        tvAccel.text = String.format(Locale.US,
            "📱 Accelerometer (m/s²)\nX: %+.4f\nY: %+.4f\nZ: %+.4f\nMag: %.4f",
            accelX, accelY, accelZ,
            kotlin.math.sqrt((accelX * accelX + accelY * accelY + accelZ * accelZ).toDouble())
        )

        // Gyroscope
        tvGyro.text = String.format(Locale.US,
            "🔄 Gyroscope (rad/s)\nX: %+.6f\nY: %+.6f\nZ: %+.6f",
            gyroX, gyroY, gyroZ
        )

        // Uncalibrated Gyroscope
        tvUncalGyro.text = String.format(Locale.US,
            "🔄 Uncalibrated Gyro (rad/s)\nX: %+.6f\nY: %+.6f\nZ: %+.6f",
            uncalGyroX, uncalGyroY, uncalGyroZ
        )

        // Magnetometer
        tvMag.text = String.format(Locale.US,
            "🧭 Magnetometer (μT)\nX: %+.2f\nY: %+.2f\nZ: %+.2f",
            magX, magY, magZ
        )

        // Uncalibrated Magnetometer
        tvUncalMag.text = String.format(Locale.US,
            "🧭 Uncalibrated Mag (μT)\nX: %+.2f\nY: %+.2f\nZ: %+.2f",
            uncalMagX, uncalMagY, uncalMagZ
        )

        // Pressure
        tvPressure.text = String.format(Locale.US,
            "🌡 Pressure\n%.2f hPa | Alt: %.1f m",
            pressure,
            if (pressure > 0) SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure) else 0f
        )

        // Gravity
        tvGravity.text = String.format(Locale.US,
            "⬇ Gravity (m/s²)\nX: %+.4f\nY: %+.4f\nZ: %+.4f",
            gravityX, gravityY, gravityZ
        )

        // Linear Acceleration
        tvLinear.text = String.format(Locale.US,
            "➡ Linear Accel (m/s²)\nX: %+.4f\nY: %+.4f\nZ: %+.4f",
            linearX, linearY, linearZ
        )

        // Rotation Vector
        tvRotation.text = String.format(Locale.US,
            "🔀 Rotation Vector\nX: %+.4f\nY: %+.4f\nZ: %+.4f\nW: %+.4f",
            rotX, rotY, rotZ, rotW
        )

        // GPS
        tvGPS.text = String.format(Locale.US,
            "🛰 GPS Raw (1Hz)\nLat: %.8f\nLon: %.8f\nAlt: %.2f m\nSpeed: %.2f m/s\nBearing: %.1f°\nAccuracy: %.1f m\nSats: %d",
            latitude, longitude, altitude, speed, bearing, accuracy, satellites
        )

        // Fused GPS (100Hz)
        tvFusedGPS.text = String.format(Locale.US,
            "🚀 Sensor Fusion GPS (100Hz)\nLat: %.8f\nLon: %.8f\nAlt: %.2f m\nSpeed: %.2f m/s\nBearing: %.1f°\nOutput Rate: %.0f Hz\nGPS Fixes: %d | Fused: %d",
            sensorFusionGPS.fusedLatitude,
            sensorFusionGPS.fusedLongitude,
            sensorFusionGPS.fusedAltitude,
            sensorFusionGPS.fusedSpeed,
            sensorFusionGPS.fusedBearing,
            sensorFusionGPS.outputRateHz,
            sensorFusionGPS.gpsFixCount,
            sensorFusionGPS.outputCount
        )

        // Raw GNSS Measurements
        rawGNSS?.let { gnss ->
            val bestSats = gnss.getBestSatellites(4)
            val satInfo = StringBuilder()
            for (sat in bestSats) {
                satInfo.append("${sat.constellationName} PRN${sat.svid}: ${String.format("%.1f", sat.cn0DbHz)}dB\n")
            }
            
            tvRawGNSS.text = String.format(Locale.US,
                "🛠 Raw GNSS Measurements\nVisible: %d | Used: %d | Measurements: %d\nClock Bias: %.3f ms\n--- Best Satellites ---\n%s",
                gnss.visibleSatellites,
                gnss.usedInFix,
                gnss.measurementCount,
                gnss.clockBiasNanos / 1_000_000.0,
                satInfo.toString()
            )
        } ?: run {
            tvRawGNSS.text = "🛠 Raw GNSS Measurements\nNot supported or not started"
        }
    }

    override fun onResume() {
        super.onResume()
        // Don't auto-start on resume
    }

    override fun onPause() {
        super.onPause()
        if (isRunning) {
            stopSensors()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensors()
        Log.i(TAG, "RawSensorsActivity destroyed")
    }
}

