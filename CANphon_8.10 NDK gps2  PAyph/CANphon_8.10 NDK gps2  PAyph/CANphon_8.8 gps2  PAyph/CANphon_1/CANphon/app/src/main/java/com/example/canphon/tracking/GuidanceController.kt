package com.example.canphon.tracking
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.managers.SharedBusManager
import com.example.canphon.native_sensors.NativeCore
import com.example.canphon.data.*

import android.content.Context
import android.util.Log


/**
 * GuidanceController - Native Performance Edition
 * يستخدم NativeCore للأداء العالي (10x أسرع)
 * 
 * PID + LPF + X-Mixing كلها في C++
 */
class GuidanceController(
    private val context: Context
) {
    companion object {
        private const val TAG = "GuidanceController"
        
        // Faster response (0.6 = balanced speed/smoothness)
        private const val ALPHA = 0.6f
        private const val CMD_MAX = 25f
    }
    
    // SharedBusManager for CAN servo control
    private val busManager = SharedBusManager.getInstance()
    
    // Current tracking state
    private var isTracking = false
    
    // Servo angles cache
    private var servoAngles = floatArrayOf(0f, 0f, 0f, 0f)
    private var currentPitchCmd = 0f
    private var currentYawCmd = 0f
    
    fun init(): Boolean {
        // Initialize native guidance controller
        NativeCore.guidanceInit(ALPHA, CMD_MAX)
        Log.i(TAG, "✅ Native GuidanceController initialized (α=$ALPHA, cmdMax=$CMD_MAX)")
        Log.i(TAG, "CAN Connected: ${busManager.isConnected}")
        return true
    }
    
    fun startTracking() {
        isTracking = true
        NativeCore.guidanceStart()
        Log.i(TAG, "Tracking started - CAN: ${busManager.isConnected}")
    }
    
    fun stopTracking() {
        isTracking = false
        NativeCore.guidanceStop()
        currentPitchCmd = 0f
        currentYawCmd = 0f
        servoAngles = floatArrayOf(0f, 0f, 0f, 0f)
        sendServoCommands()
        Log.i(TAG, "Tracking stopped")
    }
    
    fun stop() {
        stopTracking()
    }
    
    /**
     * Update tracking error from camera (Native processing)
     * @param errorX: Normalized error in X axis (-1 to +1, + = right)
     * @param errorY: Normalized error in Y axis (-1 to +1, + = down)
     */
    fun updateTrackingError(errorX: Float, errorY: Float) {
        if (!isTracking) return
        
        // Update native guidance (all processing in C++)
        NativeCore.guidanceUpdate(errorX, errorY, 0.033f)  // ~30fps
        
        // Get results from native
        val commands = NativeCore.guidanceGetCommands()
        currentPitchCmd = commands[0]
        currentYawCmd = commands[1]
        
        servoAngles = NativeCore.guidanceGetServoAngles()
        
        // Log for debugging
        Log.d(TAG, "📍 Error: X=%.2f, Y=%.2f".format(errorX, errorY))
        Log.d(TAG, "🎮 Cmd: Yaw=%.1f°, Pitch=%.1f°".format(currentYawCmd, currentPitchCmd))
        
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


