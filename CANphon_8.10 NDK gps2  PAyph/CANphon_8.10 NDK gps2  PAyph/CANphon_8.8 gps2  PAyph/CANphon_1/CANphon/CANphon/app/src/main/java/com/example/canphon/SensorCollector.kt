package com.example.canphon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat

/**
 * SensorCollector - Collects all phone sensor data for telemetry
 * 
 * Sensors:
 * - Accelerometer (X, Y, Z)
 * - Pressure/Barometer (hPa + altitude)
 * - GPS (lat, lon, alt, speed, heading, satellites)
 * - Temperature
 * - Battery (%, voltage, charging)
 * 
 * Note: Orientation (Roll, Pitch, Yaw) is handled by GyroManager
 */
class SensorCollector(private val context: Context) : SensorEventListener, LocationListener {
    
    companion object {
        private const val TAG = "SensorCollector"
        
        // Standard sea level pressure for altitude calculation
        private const val SEA_LEVEL_PRESSURE = 1013.25f
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    
    // Sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val temperature = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    
    // Accelerometer data
    var accX: Float = 0f
        private set
    var accY: Float = 0f
        private set
    var accZ: Float = 0f
        private set
    
    // Pressure/Barometer data
    var pressureHpa: Float = 0f
        private set
    var baroAltitude: Float = 0f
        private set
    
    // GPS data
    var latitude: Double = 0.0
        private set
    var longitude: Double = 0.0
        private set
    var gpsAltitude: Float = 0f
        private set
    var speed: Float = 0f
        private set
    var heading: Float = 0f
        private set
    var satellites: Int = 0
        private set
    var gpsFix: Int = 0  // 0=No, 1=2D, 2=3D
        private set
    var hdop: Float = 99f
        private set
    
    // Temperature
    var temperatureCelsius: Float = 0f
        private set
    
    // Battery
    var batteryPercent: Int = 0
        private set
    var isCharging: Boolean = false
        private set
    var batteryVoltage: Int = 0  // mV
        private set
    
    private var isRunning = false
    
    /**
     * Start collecting sensor data
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start() {
        if (isRunning) return
        
        // Register accelerometer
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "✅ Accelerometer registered")
        } ?: Log.w(TAG, "⚠️ Accelerometer not available")
        
        // Register pressure sensor
        pressure?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "✅ Pressure sensor registered")
        } ?: Log.w(TAG, "⚠️ Pressure sensor not available")
        
        // Register temperature sensor
        temperature?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "✅ Temperature sensor registered")
        } ?: Log.w(TAG, "⚠️ Temperature sensor not available")
        
        // Start GPS updates
        startGPS()
        
        isRunning = true
        Log.i(TAG, "SensorCollector started")
    }
    
    /**
     * Stop collecting sensor data
     */
    fun stop() {
        if (!isRunning) return
        
        sensorManager.unregisterListener(this)
        stopGPS()
        
        isRunning = false
        Log.i(TAG, "SensorCollector stopped")
    }
    
    /**
     * Start GPS location updates
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startGPS() {
        if (locationManager == null) {
            Log.w(TAG, "⚠️ LocationManager not available")
            return
        }
        
        // Check permission
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "⚠️ GPS permission not granted")
            return
        }
        
        try {
            // Try GPS provider first
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    100,  // 100ms minimum interval
                    0f,   // No minimum distance
                    this
                )
                Log.d(TAG, "✅ GPS provider registered")
            }
            
            // Also try fused provider if available (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        100,
                        0f,
                        this
                    )
                    Log.d(TAG, "✅ Fused provider registered")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "GPS start error: ${e.message}")
        }
    }
    
    /**
     * Stop GPS updates
     */
    private fun stopGPS() {
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "GPS stop error: ${e.message}")
        }
    }
    
    /**
     * Update battery status
     * Call this periodically (e.g., every second)
     */
    fun updateBattery() {
        try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            
            batteryIntent?.let { intent ->
                // Battery percentage
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryPercent = if (level >= 0 && scale > 0) {
                    (level * 100 / scale)
                } else {
                    0
                }
                
                // Charging status
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
                
                // Voltage in mV
                batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                
                // Temperature from battery (backup if no ambient temp sensor)
                if (temperature == null) {
                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    temperatureCelsius = temp / 10f  // Convert from tenths of degree
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery update error: ${e.message}")
        }
    }
    
    /**
     * Update telemetry streamer with current sensor data
     */
    private var telemetryFrameCount = 0
    
    fun updateTelemetry() {
        // Update battery only every 50 calls (~1 second at 50Hz)
        // registerReceiver is slow!
        if (telemetryFrameCount++ % 50 == 0) {
            updateBattery()
        }
        
        // Send to TelemetryStreamer (fast operations)
        TelemetryStreamer.updateAccelerometer(accX, accY, accZ)
        TelemetryStreamer.updatePressure(pressureHpa, baroAltitude)
        TelemetryStreamer.updateGPS(
            latitude, longitude, gpsAltitude,
            speed, heading, satellites, gpsFix, hdop
        )
        TelemetryStreamer.updateTemperature(temperatureCelsius)
        TelemetryStreamer.updateBattery(batteryPercent, isCharging, batteryVoltage)
    }
    
    // ==================== SensorEventListener ====================
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accX = event.values[0]
                accY = event.values[1]
                accZ = event.values[2]
            }
            
            Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0]
                // Calculate altitude from pressure (simplified barometric formula)
                baroAltitude = SensorManager.getAltitude(SEA_LEVEL_PRESSURE, pressureHpa)
            }
            
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                temperatureCelsius = event.values[0]
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    // ==================== LocationListener ====================
    
    override fun onLocationChanged(location: Location) {
        latitude = location.latitude
        longitude = location.longitude
        gpsAltitude = location.altitude.toFloat()
        speed = location.speed  // m/s
        heading = location.bearing
        
        // Accuracy as HDOP estimate (rough conversion)
        hdop = location.accuracy / 5f  // Rough estimate
        
        // Satellites (if available)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Note: Getting satellite count requires GnssStatus callback
            // For simplicity, estimate from accuracy
            satellites = when {
                location.accuracy < 5 -> 10
                location.accuracy < 10 -> 7
                location.accuracy < 20 -> 5
                location.accuracy < 50 -> 3
                else -> 0
            }
        }
        
        // GPS fix type
        gpsFix = when {
            location.hasAltitude() && location.hasSpeed() -> 3  // 3D fix
            location.hasAltitude() || location.hasSpeed() -> 2  // 2D fix
            else -> 1  // Basic fix
        }
    }
    
    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "GPS provider enabled: $provider")
    }
    
    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "GPS provider disabled: $provider")
        if (provider == LocationManager.GPS_PROVIDER) {
            gpsFix = 0
            satellites = 0
        }
    }
    
    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Deprecated but required for older API levels
    }
}
