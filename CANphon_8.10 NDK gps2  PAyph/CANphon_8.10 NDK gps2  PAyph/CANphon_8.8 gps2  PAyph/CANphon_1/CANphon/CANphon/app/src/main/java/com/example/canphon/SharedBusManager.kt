package com.example.canphon

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton Bus Manager for CAN Servo Communication
 * 
 * Verified implementation from production Seeker system.
 * Manages Waveshare USB-CAN-A connection at 2Mbps.
 */
class SharedBusManager private constructor() {
    
    companion object {
        private const val TAG = "SharedBusManager"
        private const val ACTION_USB_PERMISSION = "com.example.canphon.USB_PERMISSION"
        
        @Volatile
        private var instance: SharedBusManager? = null
        
        fun getInstance(): SharedBusManager {
            return instance ?: synchronized(this) {
                instance ?: SharedBusManager().also { instance = it }
            }
        }
    }
    
    private var waveshare: WaveshareAdapter? = null
    private var serialPort: UsbSerialPort? = null
    private var connectedDeviceName: String? = null
    
    var isConnected = false
        private set
    
    var connectedServos = 4
        private set
    
    var lastError: String = ""
        private set
    
    var onConnectionChanged: ((Boolean, String) -> Unit)? = null
    
    // ==================== Telemetry Tracking ====================
    
    // Last commanded servo positions
    var lastS1Cmd: Float = 0f
        private set
    var lastS2Cmd: Float = 0f
        private set
    var lastS3Cmd: Float = 0f
        private set
    var lastS4Cmd: Float = 0f
        private set
    
    // Servo feedback (actual positions from CAN)
    var s1Feedback: Float = -999.9f
        private set
    var s2Feedback: Float = -999.9f
        private set
    var s3Feedback: Float = -999.9f
        private set
    var s4Feedback: Float = -999.9f
        private set
    
    // Servo online status (bitmask: bit0=S1, bit1=S2, bit2=S3, bit3=S4)
    var servoOnlineStatus: Int = 0
        private set
    
    // Feedback timeout tracking (ms)
    private val feedbackTimeout = 500L
    private var lastS1FeedbackTime: Long = 0
    private var lastS2FeedbackTime: Long = 0
    private var lastS3FeedbackTime: Long = 0
    private var lastS4FeedbackTime: Long = 0
    
    // Background thread for non-blocking CAN writes
    private val canExecutor = Executors.newSingleThreadExecutor()
    private val isWriting = AtomicBoolean(false)

    /**
     * Connect to Waveshare USB-CAN adapter
     */
    fun connect(usbManager: UsbManager, context: Context? = null): Boolean {
        if (isConnected) {
            Log.d(TAG, "Already connected")
            return true
        }
        
        try {
            // List USB devices
            val deviceList = usbManager.deviceList
            Log.d(TAG, "USB devices found: ${deviceList.size}")
            deviceList.forEach { (name, device) ->
                Log.d(TAG, "  $name: VID=${device.vendorId}, PID=${device.productId}")
            }
            
            // Find USB serial devices
            var drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            
            if (drivers.isEmpty()) {
                drivers = CustomProber.getCustomProber().findAllDrivers(usbManager)
            }
            
            if (drivers.isEmpty()) {
                lastError = "No USB Serial device found"
                Log.w(TAG, lastError)
                return false
            }
            
            // Find Waveshare CAN adapter specifically (CH340 VID = 0x1A86)
            // This prevents connecting to RS232/FTDI adapters by mistake
            val waveshareDriver = drivers.find { driver ->
                driver.device.vendorId == 0x1A86  // CH340 VID
            }
            
            if (waveshareDriver == null) {
                lastError = "Waveshare CAN adapter not found (VID=0x1A86)"
                Log.w(TAG, "$lastError - Available: ${drivers.map { "VID=0x${it.device.vendorId.toString(16)}" }}")
                return false
            }
            
            val driver = waveshareDriver
            val device = driver.device
            
            Log.d(TAG, "Found Waveshare CAN: VID=${String.format("0x%04X", device.vendorId)}, PID=${String.format("0x%04X", device.productId)}")
            
            // Check permission
            if (!usbManager.hasPermission(device)) {
                lastError = "Waiting for USB permission"
                context?.let { requestUsbPermission(usbManager, device, it) }
                return false
            }
            
            // Open device
            val connection = usbManager.openDevice(device) ?: run {
                lastError = "Failed to open USB device"
                Log.e(TAG, lastError)
                return false
            }
            
            // Configure serial port
            serialPort = driver.ports[0].apply {
                open(connection)
                setParameters(
                    WaveshareAdapter.BAUD_RATE,  // 2,000,000
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                // Enable DTR/RTS for some adapters
                dtr = true
                rts = true
            }
            
            // Create adapter
            waveshare = WaveshareAdapter(serialPort!!)
            
            // Send test frames to verify connection
            Log.d(TAG, "Sending test frames...")
            waveshare?.sendTestFrames(3)
            
            isConnected = true
            connectedDeviceName = device.deviceName
            lastError = ""
            
            Log.i(TAG, "✅ Connected to ${device.deviceName} at ${WaveshareAdapter.BAUD_RATE} baud")
            onConnectionChanged?.invoke(true, "Connected")
            return true
            
        } catch (e: Exception) {
            lastError = "Connection error: ${e.message}"
            Log.e(TAG, lastError, e)
            disconnect()
            return false
        }
    }
    
    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice, context: Context) {
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, intent)
    }

    /**
     * Send position command to a servo
     */
    fun sendServoCommand(nodeId: Int, angle: Float) {
        if (!isConnected || waveshare == null) return
        
        val frame = CANServoProtocol.createPositionCommand(nodeId, angle)
        waveshare?.sendFrame(frame)
    }
    
    /**
     * Send commands to all 4 servos using X-Configuration Mixing
     * 
     * ORIGINAL PROJECT FORMULA:
     * Servo 1 (Front-Left):  -Roll + Pitch
     * Servo 2 (Front-Right): +Roll + Pitch
     * Servo 3 (Back-Left):   -Roll - Pitch
     * Servo 4 (Back-Right):  +Roll - Pitch
     */
    fun sendAllServoCommands(roll: Float, pitch: Float, yaw: Float) {
        if (!isConnected) return
        
        // X-Configuration Mixing (Original Formula, ±25° range)
        val s1 = (-roll + pitch).coerceIn(-25f, 25f)  // Front-Left
        val s2 = (+roll + pitch).coerceIn(-25f, 25f)  // Front-Right
        val s3 = (-roll - pitch).coerceIn(-25f, 25f)  // Back-Left
        val s4 = (+roll - pitch).coerceIn(-25f, 25f)  // Back-Right
        
        // Store last commanded positions for telemetry
        lastS1Cmd = s1
        lastS2Cmd = s2
        lastS3Cmd = s3
        lastS4Cmd = s4
        
        // Skip if previous write still in progress (prevents USB buffer overflow)
        if (isWriting.get()) return
        
        // Send on background thread - NO BLOCKING on main thread!
        isWriting.set(true)
        canExecutor.execute {
            try {
                sendServoCommand(CANServoProtocol.SERVO_1, s1)
                sendServoCommand(CANServoProtocol.SERVO_2, s2)
                sendServoCommand(CANServoProtocol.SERVO_3, s3)
                sendServoCommand(CANServoProtocol.SERVO_4, s4)
            } catch (e: Exception) {
                Log.e(TAG, "CAN send error: ${e.message}")
            } finally {
                isWriting.set(false)
            }
        }
    }
    
    /**
     * Get servo positions for UI
     */
    fun getServoPositions(roll: Float, pitch: Float): FloatArray {
        return floatArrayOf(
            (-roll + pitch).coerceIn(-25f, 25f),
            (+roll + pitch).coerceIn(-25f, 25f),
            (-roll - pitch).coerceIn(-25f, 25f),
            (+roll - pitch).coerceIn(-25f, 25f)
        )
    }
    
    /**
     * Read feedback from servos
     */
    fun readFeedback(): CANFrame? {
        return waveshare?.readFrame()
    }
    
    /**
     * Get adapter statistics
     */
    fun getStats(): String {
        return waveshare?.getStats() ?: "Not connected"
    }

    /**
     * Disconnect
     */
    fun disconnect() {
        try {
            waveshare?.close()
            waveshare = null
            serialPort = null
            isConnected = false
            Log.i(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
    }
    
    fun getPermissionAction(): String = ACTION_USB_PERMISSION
    
    /**
     * Get the device name of the connected CAN adapter
     */
    fun getConnectedDeviceName(): String? = connectedDeviceName
    
    /**
     * Process CAN feedback and update servo status
     * Runs on canExecutor to prevent USB port contention with sends
     */
    fun processFeedback() {
        if (!isConnected || waveshare == null) return
        
        // Skip if already busy with writes
        if (isWriting.get()) return
        
        // Run on same thread as sends to avoid USB contention
        canExecutor.execute {
            try {
                // Read only 1 frame per call (non-blocking)
                val frame = waveshare?.readFrame() ?: return@execute
                val currentTime = System.currentTimeMillis()
                
                // Parse feedback based on CAN ID
                val nodeId = frame.id - CANServoProtocol.RX_OFFSET
                val position = CANServoProtocol.parsePositionFeedback(frame.data)
                
                if (position != null) {
                    when (nodeId) {
                        CANServoProtocol.SERVO_1 -> {
                            s1Feedback = position
                            lastS1FeedbackTime = currentTime
                        }
                        CANServoProtocol.SERVO_2 -> {
                            s2Feedback = position
                            lastS2FeedbackTime = currentTime
                        }
                        CANServoProtocol.SERVO_3 -> {
                            s3Feedback = position
                            lastS3FeedbackTime = currentTime
                        }
                        CANServoProtocol.SERVO_4 -> {
                            s4Feedback = position
                            lastS4FeedbackTime = currentTime
                        }
                    }
                }
                
                // Update online status
                updateServoOnlineStatus(currentTime)
                
            } catch (e: Exception) {
                Log.w(TAG, "Feedback read error: ${e.message}")
            }
        }
    }
    
    /**
     * Update servo online status bitmask based on feedback timeout
     */
    private fun updateServoOnlineStatus(currentTime: Long) {
        var status = 0
        
        if (currentTime - lastS1FeedbackTime < feedbackTimeout) {
            status = status or 0x01  // Bit 0 = Servo 1
        }
        if (currentTime - lastS2FeedbackTime < feedbackTimeout) {
            status = status or 0x02  // Bit 1 = Servo 2
        }
        if (currentTime - lastS3FeedbackTime < feedbackTimeout) {
            status = status or 0x04  // Bit 2 = Servo 3
        }
        if (currentTime - lastS4FeedbackTime < feedbackTimeout) {
            status = status or 0x08  // Bit 3 = Servo 4
        }
        
        servoOnlineStatus = status
        
        // Count connected servos
        connectedServos = Integer.bitCount(status)
    }
    
    /**
     * Update TelemetryStreamer with current servo data
     */
    fun updateTelemetry() {
        TelemetryStreamer.updateServoCommands(lastS1Cmd, lastS2Cmd, lastS3Cmd, lastS4Cmd)
        TelemetryStreamer.updateServoFeedback(s1Feedback, s2Feedback, s3Feedback, s4Feedback)
        TelemetryStreamer.updateServoStatus(servoOnlineStatus)
    }
}
