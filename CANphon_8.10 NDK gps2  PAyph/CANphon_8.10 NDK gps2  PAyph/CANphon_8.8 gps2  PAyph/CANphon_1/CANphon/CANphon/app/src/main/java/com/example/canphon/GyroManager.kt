package com.example.canphon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Gyroscope Manager (Original Project Settings)
 * 
 * Settings from verified production system:
 * - Smoothing Factor: 0.5 (50% current + 50% previous)
 * - Deadzone: 0°
 * - Calibration on startup
 */
class GyroManager(context: Context) : SensorEventListener {
    
    companion object {
        private const val TAG = "GyroManager"
        
        // MAX SENSITIVITY SETTINGS
        private const val SMOOTHING_FACTOR = 0.2f  // 20% blend (faster response)
        private const val DEAD_ZONE = 0f           // No dead zone
        private const val CALIBRATION_SAMPLES = 3  // Fast calibration
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
    
    // Smoothed values (before calibration)
    private var smoothedRoll = 0f
    private var smoothedPitch = 0f
    private var smoothedYaw = 0f
    
    // Calibration offsets
    private var calibrationRoll = 0f
    private var calibrationPitch = 0f
    private var calibrationYaw = 0f
    
    // Previous values for deadzone
    private var lastRoll = 0f
    private var lastPitch = 0f
    
    // Calibration state
    private var calibrationCount = 0
    private var calibrationRollSum = 0f
    private var calibrationPitchSum = 0f
    private var isCalibrated = false
    
    var onOrientationChanged: ((roll: Float, pitch: Float, yaw: Float) -> Unit)? = null
    
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var isRunning = false

    fun start() {
        if (isRunning) return
        
        if (rotationSensor == null) {
            Log.e(TAG, "Rotation sensor not available")
            return
        }
        
        // Reset
        smoothedRoll = 0f
        smoothedPitch = 0f
        calibrationCount = 0
        calibrationRollSum = 0f
        calibrationPitchSum = 0f
        isCalibrated = false
        
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST)
        isRunning = true
        Log.i(TAG, "Started (MAX settings: Smooth=0.2, Dead=0°, FASTEST sensor)")
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
        
        // Apply smoothing (weighted average)
        smoothedRoll = (smoothedRoll * SMOOTHING_FACTOR) + (rawRoll * (1 - SMOOTHING_FACTOR))
        smoothedPitch = (smoothedPitch * SMOOTHING_FACTOR) + (rawPitch * (1 - SMOOTHING_FACTOR))
        smoothedYaw = rawYaw
        
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
        
        // Apply deadzone (ignore small changes)
        if (kotlin.math.abs(calibratedRoll - lastRoll) < DEAD_ZONE) {
            calibratedRoll = lastRoll
        } else {
            lastRoll = calibratedRoll
        }
        
        if (kotlin.math.abs(calibratedPitch - lastPitch) < DEAD_ZONE) {
            calibratedPitch = lastPitch
        } else {
            lastPitch = calibratedPitch
        }
        
        roll = calibratedRoll
        pitch = calibratedPitch
        yaw = smoothedYaw
        
        onOrientationChanged?.invoke(roll, pitch, yaw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
