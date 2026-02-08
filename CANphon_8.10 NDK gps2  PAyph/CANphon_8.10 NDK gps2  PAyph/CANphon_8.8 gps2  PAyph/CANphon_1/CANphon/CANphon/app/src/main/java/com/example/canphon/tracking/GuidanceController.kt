package com.example.canphon.tracking

import android.content.Context
import android.util.Log

/**
 * Minimal GuidanceController for CANphon
 * Connects camera tracking to CAN servo control
 * 
 * Uses Low-Pass Filter for smooth tracking (like GyroManager)
 */
class GuidanceController(
    private val context: Context
) {
    companion object {
        private const val TAG = "GuidanceController"
        
        // PID Constants
        private const val KP = 0.5f
        private const val KD = 0.1f
        
        // Low-Pass Filter coefficient (0.0 = no change, 1.0 = instant)
        // Lower = smoother but slower response
        private const val ALPHA = 0.3f
        
        // Command limits (±25°)
        private const val CMD_MIN = -25f
        private const val CMD_MAX = 25f
    }
    
    // SharedBusManager for CAN servo control
    private val busManager = com.example.canphon.SharedBusManager.getInstance()
    
    // Current tracking state
    private var isTracking = false
    
    // Raw error from camera
    private var rawErrorX = 0f
    private var rawErrorY = 0f
    
    // Filtered error (smoothed)
    private var filteredErrorX = 0f
    private var filteredErrorY = 0f
    
    // Previous filtered error (for derivative)
    private var prevFilteredErrorX = 0f
    private var prevFilteredErrorY = 0f
    
    // Current commands (raw pitch/yaw before X-mixing)
    private var currentPitchCmd = 0f
    private var currentYawCmd = 0f
    
    // Servo angles (4 servos in X-config) - for display only
    private var servoAngles = floatArrayOf(0f, 0f, 0f, 0f)
    
    fun init(): Boolean {
        Log.i(TAG, "GuidanceController initialized (LPF α=$ALPHA)")
        Log.i(TAG, "CAN Connected: ${busManager.isConnected}")
        return true
    }
    
    fun startTracking() {
        isTracking = true
        // Reset filters
        filteredErrorX = 0f
        filteredErrorY = 0f
        prevFilteredErrorX = 0f
        prevFilteredErrorY = 0f
        Log.i(TAG, "Tracking started - CAN: ${busManager.isConnected}")
    }
    
    fun stopTracking() {
        isTracking = false
        // Center servos
        currentPitchCmd = 0f
        currentYawCmd = 0f
        filteredErrorX = 0f
        filteredErrorY = 0f
        servoAngles = floatArrayOf(0f, 0f, 0f, 0f)
        sendServoCommands()
        Log.i(TAG, "Tracking stopped")
    }
    
    fun stop() {
        stopTracking()
    }
    
    /**
     * Update tracking error from camera
     * @param errorX: Normalized error in X axis (-1 to +1, + = right)
     * @param errorY: Normalized error in Y axis (-1 to +1, + = down)
     */
    fun updateTrackingError(errorX: Float, errorY: Float) {
        if (!isTracking) return
        
        rawErrorX = errorX
        rawErrorY = errorY
        
        // ═══════════════════════════════════════════════════════════
        // LOW-PASS FILTER (like GyroManager)
        // filtered = α × raw + (1-α) × prev_filtered
        // ═══════════════════════════════════════════════════════════
        prevFilteredErrorX = filteredErrorX
        prevFilteredErrorY = filteredErrorY
        
        filteredErrorX = ALPHA * rawErrorX + (1 - ALPHA) * filteredErrorX
        filteredErrorY = ALPHA * rawErrorY + (1 - ALPHA) * filteredErrorY
        
        // PD Controller (using filtered error)
        // Yaw: errorX > 0 (target right) → turn right → yaw > 0
        // Pitch: errorY > 0 (target down) → pitch down → pitch < 0
        val deltaX = filteredErrorX - prevFilteredErrorX
        val deltaY = filteredErrorY - prevFilteredErrorY
        
        val yawCmd = (KP * filteredErrorX + KD * deltaX) * CMD_MAX
        val pitchCmd = -(KP * filteredErrorY + KD * deltaY) * CMD_MAX  // Inverted!
        
        currentYawCmd = yawCmd.coerceIn(CMD_MIN, CMD_MAX)
        currentPitchCmd = pitchCmd.coerceIn(CMD_MIN, CMD_MAX)
        
        // Calculate display servo angles (X-Mixing preview)
        servoAngles[0] = (currentPitchCmd + currentYawCmd).coerceIn(CMD_MIN, CMD_MAX)
        servoAngles[1] = (currentPitchCmd - currentYawCmd).coerceIn(CMD_MIN, CMD_MAX)
        servoAngles[2] = (-currentPitchCmd - currentYawCmd).coerceIn(CMD_MIN, CMD_MAX)
        servoAngles[3] = (-currentPitchCmd + currentYawCmd).coerceIn(CMD_MIN, CMD_MAX)
        
        // Log for debugging (show raw vs filtered)
        Log.d(TAG, "📍 Raw: X=${"%.2f".format(rawErrorX)}, Y=${"%.2f".format(rawErrorY)} → Filtered: X=${"%.2f".format(filteredErrorX)}, Y=${"%.2f".format(filteredErrorY)}")
        Log.d(TAG, "🎮 Cmd: Yaw=${"%.1f".format(currentYawCmd)}°, Pitch=${"%.1f".format(currentPitchCmd)}°")
        
        // Send to servos
        sendServoCommands()
    }
    
    fun getServoAngles(): FloatArray {
        return servoAngles.copyOf()
    }
    
    private fun sendServoCommands() {
        if (busManager.isConnected) {
            busManager.sendAllServoCommands(currentYawCmd, currentPitchCmd, 0f)
        }
    }
}
