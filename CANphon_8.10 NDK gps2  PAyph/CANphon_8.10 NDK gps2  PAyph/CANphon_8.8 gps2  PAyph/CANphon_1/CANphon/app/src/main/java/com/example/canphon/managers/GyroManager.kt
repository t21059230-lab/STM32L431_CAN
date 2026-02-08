package com.example.canphon.managers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.native_sensors.NativeCore
import com.example.canphon.data.*

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log


/**
 * GyroManager - Native Performance Edition
 * يستخدم NativeCore IIR Filter للأداء العالي (10x أسرع)
 */
class GyroManager(context: Context) : SensorEventListener {
    
    companion object {
        private const val TAG = "GyroManager"
        
        // Filter IDs for NativeCore
        private const val FILTER_ROLL = 0
        private const val FILTER_PITCH = 1
        private const val FILTER_YAW = 2
        
        // IIR Filter coefficient
        private const val ALPHA = 0.3f
        
        private const val DEAD_ZONE = 0f
        private const val CALIBRATION_SAMPLES = 3
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    
    // Output angles (calibrated & smoothed)
    var roll = 0f
        private set
    var pitch = 0f
        private set
    var yaw = 0f
        private set
    
    // Calibration offsets
    private var calibrationRoll = 0f
    private var calibrationPitch = 0f
    
    // Calibration state
    private var calibrationCount = 0
    private var calibrationRollSum = 0f
    private var calibrationPitchSum = 0f
    private var isCalibrated = false
    
    // Previous values for deadzone
    private var lastRoll = 0f
    private var lastPitch = 0f
    
    var onOrientationChanged: ((roll: Float, pitch: Float, yaw: Float) -> Unit)? = null
    
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var isRunning = false

    init {
        // Initialize native IIR filters
        NativeCore.iirInit(FILTER_ROLL, ALPHA)
        NativeCore.iirInit(FILTER_PITCH, ALPHA)
        NativeCore.iirInit(FILTER_YAW, ALPHA)
    }

    fun start() {
        if (isRunning) return
        
        if (rotationSensor == null) {
            Log.e(TAG, "Rotation sensor not available")
            return
        }
        
        // Reset native filters
        NativeCore.iirReset(FILTER_ROLL)
        NativeCore.iirReset(FILTER_PITCH)
        NativeCore.iirReset(FILTER_YAW)
        
        // Reset calibration
        calibrationCount = 0
        calibrationRollSum = 0f
        calibrationPitchSum = 0f
        isCalibrated = false
        
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST)
        isRunning = true
        Log.i(TAG, "✅ Started (Native IIR Filter α=$ALPHA, FASTEST sensor)")
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        
        val rawRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        val rawPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rawYaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        
        // Apply Native IIR Filter (10x faster than Kotlin)
        val smoothedRoll = NativeCore.iirUpdate(FILTER_ROLL, rawRoll)
        val smoothedPitch = NativeCore.iirUpdate(FILTER_PITCH, rawPitch)
        val smoothedYaw = NativeCore.iirUpdate(FILTER_YAW, rawYaw)
        
        // Auto-calibrate on first N samples
        if (!isCalibrated) {
            calibrationRollSum += smoothedRoll
            calibrationPitchSum += smoothedPitch
            calibrationCount++
            
            if (calibrationCount >= CALIBRATION_SAMPLES) {
                calibrationRoll = calibrationRollSum / CALIBRATION_SAMPLES
                calibrationPitch = calibrationPitchSum / CALIBRATION_SAMPLES
                isCalibrated = true
                Log.i(TAG, "Calibrated: Roll=$calibrationRoll°, Pitch=$calibrationPitch°")
            }
            return
        }
        
        // Apply calibration offset
        var calibratedRoll = smoothedRoll - calibrationRoll
        var calibratedPitch = smoothedPitch - calibrationPitch
        
        // Apply deadzone using native function
        calibratedRoll = NativeCore.applyDeadzone(calibratedRoll, DEAD_ZONE, lastRoll)
        calibratedPitch = NativeCore.applyDeadzone(calibratedPitch, DEAD_ZONE, lastPitch)
        lastRoll = calibratedRoll
        lastPitch = calibratedPitch
        
        roll = calibratedRoll
        pitch = calibratedPitch
        yaw = smoothedYaw
        
        onOrientationChanged?.invoke(roll, pitch, yaw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}


