package com.example.canphon.native_sensors
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import android.util.Log

/**
 * NativeSensorManager - مدير الحساسات الأصلي
 * 
 * يوفر وصولاً مباشراً للحساسات عبر NDK بأقصى تردد ممكن
 * 
 * الاستخدام:
 * ```kotlin
 * val nativeSensors = NativeSensorManager()
 * nativeSensors.initialize()
 * nativeSensors.start(0) // 0 = أقصى سرعة
 * 
 * // في loop:
 * val data = nativeSensors.poll()
 * Log.d(TAG, "Gyro Rate: ${data.measuredRate} Hz")
 * 
 * nativeSensors.stop()
 * nativeSensors.cleanup()
 * ```
 */
class NativeSensorManager {
    
    companion object {
        private const val TAG = "NativeSensorManager"
        
        // Sensor types (matching Android sensor types)
        const val SENSOR_TYPE_ACCELEROMETER = 1
        const val SENSOR_TYPE_GYROSCOPE = 4
        const val SENSOR_TYPE_MAGNETIC_FIELD = 2
        
        init {
            try {
                System.loadLibrary("canphon_native")
                Log.i(TAG, "Native library loaded successfully!")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }
    
    // Sensor data class
    data class SensorData(
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val measuredRate: Float,
        val eventCount: Int
    )
    
    private var isInitialized = false
    private var isRunning = false
    
    /**
     * Initialize native sensors
     * @return true if successful
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return true
        }
        
        val result = initNative()
        isInitialized = (result == 0)
        
        if (isInitialized) {
            Log.i(TAG, "Native sensors initialized")
            logMaxRates()
        } else {
            Log.e(TAG, "Failed to initialize native sensors")
        }
        
        return isInitialized
    }
    
    /**
     * Start sensors at specified rate
     * @param usDelay Delay in microseconds (0 = FASTEST)
     */
    fun start(usDelay: Int = 0): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "Not initialized! Call initialize() first")
            return false
        }
        
        val result = startNative(usDelay)
        isRunning = (result == 0)
        
        if (isRunning) {
            Log.i(TAG, "Sensors started with delay: $usDelay μs")
        }
        
        return isRunning
    }
    
    /**
     * Poll sensors and get latest data
     * Should be called in a loop (e.g., from a background thread)
     */
    fun poll(): SensorData {
        if (!isRunning) {
            return SensorData(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)
        }
        
        val raw = pollNative() ?: floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        
        return SensorData(
            accelX = raw[0],
            accelY = raw[1],
            accelZ = raw[2],
            gyroX = raw[3],
            gyroY = raw[4],
            gyroZ = raw[5],
            measuredRate = raw[6],
            eventCount = raw[7].toInt()
        )
    }
    
    /**
     * Stop sensors
     */
    fun stop() {
        if (isRunning) {
            stopNative()
            isRunning = false
            Log.i(TAG, "Sensors stopped")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        if (isInitialized) {
            cleanupNative()
            isInitialized = false
            Log.i(TAG, "Cleanup complete")
        }
    }
    
    /**
     * Get maximum possible rate for a sensor type
     */
    fun getMaxRate(sensorType: Int): Float {
        return if (isInitialized) getMaxRateNative(sensorType) else 0f
    }
    
    /**
     * Get current measured rate
     */
    fun getMeasuredRate(): Float {
        return if (isRunning) getMeasuredRateNative() else 0f
    }
    
    private fun logMaxRates() {
        val accelMax = getMaxRate(SENSOR_TYPE_ACCELEROMETER)
        val gyroMax = getMaxRate(SENSOR_TYPE_GYROSCOPE)
        Log.i(TAG, "Max Rates - Accelerometer: $accelMax Hz, Gyroscope: $gyroMax Hz")
    }
    
    // Native methods
    private external fun initNative(): Int
    private external fun startNative(usDelay: Int): Int
    private external fun pollNative(): FloatArray?
    private external fun stopNative()
    private external fun cleanupNative()
    private external fun getMaxRateNative(sensorType: Int): Float
    private external fun getMeasuredRateNative(): Float
}

