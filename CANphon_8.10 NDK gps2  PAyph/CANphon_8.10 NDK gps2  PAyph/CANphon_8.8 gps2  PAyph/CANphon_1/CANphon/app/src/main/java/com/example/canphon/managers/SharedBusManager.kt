package com.example.canphon.managers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.WaveshareAdapter
import com.example.canphon.drivers.CustomProber
import com.example.canphon.data.*

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.example.canphon.protocols.UnifiedProtocol
import com.example.canphon.protocols.FeedbackParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid Bus Manager for CAN + Serial Servo Communication
 * 
 * Supports:
 * 1. Waveshare USB-CAN-A (CAN Mode) - VID=0x1A86, 2Mbps
 * 2. Standard USB-Serial adapters (Serial Mode) - CP2102, 115200 baud
 */
class SharedBusManager private constructor() {
    
    companion object {
        private const val TAG = "SharedBusManager"
        private const val ACTION_USB_PERMISSION = "com.example.canphon.USB_PERMISSION"
        
        // Waveshare CAN VID (CH340)
        private const val WAVESHARE_VID = 0x1A86
        
        // Serial servo VIDs
        private const val CP2102_VID = 0x10C4
        
        // Serial mode baud rate
        private const val SERIAL_BAUD_RATE = 115200
        
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
    
    // For MainActivity compatibility - always false in CAN-only mode
    var isSerialMode = false
        private set
    
    var connectedServos = 4
        private set
    
    var lastError: String = ""
        private set
    
    var onConnectionChanged: ((Boolean, String) -> Unit)? = null
    
    // ==================== Telemetry Tracking ====================
    
    // Last commanded servo positions
    @Volatile var lastS1Cmd: Float = 0f
        private set
    @Volatile var lastS2Cmd: Float = 0f
        private set
    @Volatile var lastS3Cmd: Float = 0f
        private set
    @Volatile var lastS4Cmd: Float = 0f
        private set
    
    // Servo feedback (actual positions from CAN)
    @Volatile var s1Feedback: Float = -999.9f
        private set
    @Volatile var s2Feedback: Float = -999.9f
        private set
    @Volatile var s3Feedback: Float = -999.9f
        private set
    @Volatile var s4Feedback: Float = -999.9f
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
    
    // Serial mode connection and protocols
    private var serialConnection: UsbDeviceConnection? = null
    private val rollProtocol = UnifiedProtocol.createRoll()
    private val pitchProtocol = UnifiedProtocol.createPitch()
    private val yawProtocol = UnifiedProtocol.createYaw()
    private val extraProtocol = UnifiedProtocol.createExtra()
    private val serialFeedbackParser = FeedbackParser()
    
    // Raw CAN debug - stores last received frames for display
    @Volatile var lastRawCanFrame: String = "No CAN data"
        private set
    @Volatile var rawFrameCount: Int = 0
        private set
        
    // Debug Counters
    @Volatile var txCount: Long = 0
        private set
    @Volatile var errorCount: Long = 0
        private set

    // Use ThreadPoolExecutor with DiscardOldestPolicy to prevent buffer bloat
    private val canExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1,
        0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(1), // AGGRESSIVE: Keep only 1 latest command
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private val readExecutor = Executors.newSingleThreadExecutor() // READ only
    private val isWriting = AtomicBoolean(false)



    /**
     * Start the continuous read loop
     */
    private fun startReadLoop() {
        readExecutor.execute {
            Log.i(TAG, "🔄 Read Loop Started")
            while (isConnected) {
                try {
                    // Blocking read (or with timeout)
                    // If no data, readFrame returns null (after timeout)
                    val frame = waveshare?.readFrame()
                    
                    if (frame != null) {
                        processFrame(frame)
                    } else {
                        // Optional: Small sleep if needed to prevent CPU spin 
                        // (only if readFrame is non-blocking and returns immediately)
                        // If underlying read has timeout, this isn't strictly necessary.
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        Log.e(TAG, "Read loop error: ${e.message}")
                        // Simple throttle on error
                        Thread.sleep(100)
                    }
                }
            }
            Log.i(TAG, "⏹ Read Loop Stopped")
        }
    }

    /**
     * Process a single received frame
     */
    private fun processFrame(frame: CANFrame) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Store raw frame for debug display
            rawFrameCount++
            // Optimization: Only format string if debug logging is enabled or needed for UI
            // match UI expectations for "lastRawCanFrame"
            val hexData = frame.data.joinToString(" ") { 
                String.format("%02X", it.toInt() and 0xFF) 
            }
            lastRawCanFrame = "ID:0x${frame.id.toString(16).uppercase()} [${hexData}] #$rawFrameCount"
            
            // Parse feedback based on CAN ID (0x581-0x584)
            val nodeId = frame.id - CANServoProtocol.RX_OFFSET
            
            // Check if this is a valid servo feedback ID
            if (nodeId in 1..4) {
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
            }
            
            // Update online status periodically (not every frame to save CPU?)
            // Or just do it here, it's cheap bitwise ops.
            updateServoOnlineStatus(currentTime)
            
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing error: ${e.message}")
        }
    }
    
    // Legacy method - kept for compatibility but empties as loop handles it
    fun processFeedback() {
        // No-op: Reading is now handled by the background read loop
    }
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
                Log.d(TAG, "  $name: VID=${String.format("0x%04X", device.vendorId)}, PID=${String.format("0x%04X", device.productId)}")
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
            
            // Priority: CP2102 (Serial) first, then Waveshare (CAN)
            val serialDriver = drivers.find { it.device.vendorId == CP2102_VID }
            if (serialDriver != null) {
                Log.i(TAG, "🔌 Found CP2102, connecting in Serial mode...")
                return connectSerial(usbManager, serialDriver, context)
            }
            
            val waveshareDriver = drivers.find { it.device.vendorId == WAVESHARE_VID }
            if (waveshareDriver != null) {
                Log.i(TAG, "🔌 Found Waveshare CAN, connecting in CAN mode...")
                return connectCAN(usbManager, waveshareDriver, context)
            }
            
            lastError = "No compatible USB adapter found (need CP2102 or Waveshare)"
            Log.w(TAG, "$lastError - Available: ${drivers.map { "VID=0x${it.device.vendorId.toString(16)}" }}")
            return false
            
        } catch (e: Exception) {
            lastError = "Connection error: ${e.message}"
            Log.e(TAG, lastError, e)
            disconnect()
            return false
        }
    }
    
    /**
     * Connect to Waveshare USB-CAN adapter (CAN Mode)
     */
    private fun connectCAN(usbManager: UsbManager, driver: com.hoho.android.usbserial.driver.UsbSerialDriver, context: Context?): Boolean {
        val device = driver.device
        
        if (!usbManager.hasPermission(device)) {
            lastError = "Waiting for USB permission"
            context?.let { requestUsbPermission(usbManager, device, it) }
            return false
        }
        
        val connection = usbManager.openDevice(device) ?: run {
            lastError = "Failed to open USB device"
            Log.e(TAG, lastError)
            return false
        }
        
        serialPort = driver.ports[0].apply {
            open(connection)
            setParameters(WaveshareAdapter.BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            dtr = true
            rts = true
        }
        
        waveshare = WaveshareAdapter(serialPort!!)
        
        Log.d(TAG, "Sending test frames...")
        waveshare?.sendTestFrames(3)
        
        isConnected = true
        isSerialMode = false
        connectedDeviceName = device.deviceName
        lastError = ""
        
        Log.i(TAG, "✅ Connected to ${device.deviceName} in CAN MODE at ${WaveshareAdapter.BAUD_RATE} baud")
        onConnectionChanged?.invoke(true, "CAN Mode Connected")
        
        // Start background read loop
        startReadLoop()
        
        return true
    }
    
    /**
     * Connect to CP2102 Serial adapter (Serial Mode)
     */
    private fun connectSerial(usbManager: UsbManager, driver: com.hoho.android.usbserial.driver.UsbSerialDriver, context: Context?): Boolean {
        val device = driver.device
        
        if (!usbManager.hasPermission(device)) {
            lastError = "Waiting for USB permission"
            context?.let { requestUsbPermission(usbManager, device, it) }
            return false
        }
        
        serialConnection = usbManager.openDevice(device) ?: run {
            lastError = "Failed to open USB device"
            Log.e(TAG, lastError)
            return false
        }
        
        serialPort = driver.ports[0].apply {
            open(serialConnection)
            setParameters(SERIAL_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            dtr = true
            rts = true
        }
        
        isConnected = true
        isSerialMode = true
        connectedDeviceName = device.deviceName
        lastError = ""
        
        Log.i(TAG, "✅ Connected to ${device.deviceName} in SERIAL MODE at $SERIAL_BAUD_RATE baud")
        onConnectionChanged?.invoke(true, "Serial Mode Connected")
        
        // Start background read loop
        startReadLoop()
        
        return true
    }
    
    // Overload for compatibility with callers that pass excludedDeviceNames
    fun connect(usbManager: UsbManager, context: Context?, excludedDeviceNames: List<String>): Boolean {
        return connect(usbManager, context)
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
        // X-Configuration Mixing (Original Formula, ±25° range)
        val s1 = (-roll + pitch).coerceIn(-25f, 25f)  // Front-Left
        val s2 = (+roll + pitch).coerceIn(-25f, 25f)  // Front-Right
        val s3 = (-roll - pitch).coerceIn(-25f, 25f)  // Back-Left
        val s4 = (+roll - pitch).coerceIn(-25f, 25f)  // Back-Right
        
        // Store last commanded positions for telemetry
        // Store last commanded positions for telemetry - MOVED TO SEND BLOCK
        // lastS1Cmd = s1
        // lastS2Cmd = s2
        // lastS3Cmd = s3
        // lastS4Cmd = s4
        
        if (!isConnected) return
        
        // Skip if previous write still in progress (prevents USB buffer overflow)
        if (isWriting.get()) return
        
        // Send on background thread - NO BLOCKING on main thread!
        isWriting.set(true)
        canExecutor.execute {
            try {
                if (isSerialMode) {
                    // Serial Mode: Use UnifiedProtocol
                    sendSerialCommands(s1, s2, s3, s4)
                } else {
                    // CAN Mode: Use CANServoProtocol WITH AGGRESSIVE JITTER FILTER
                    // Filter: Only send if change > 1.0 degrees (Ignore small noise)
                    if (Math.abs(s1 - lastS1Cmd) > 1.0f) {
                        sendServoCommand(CANServoProtocol.SERVO_1, s1)
                        lastS1Cmd = s1
                        Thread.sleep(2)  // Prevent USB buffer overflow
                    }
                    
                    if (Math.abs(s2 - lastS2Cmd) > 1.0f) {
                        sendServoCommand(CANServoProtocol.SERVO_2, s2)
                        lastS2Cmd = s2
                        Thread.sleep(2)
                    }
                    
                    if (Math.abs(s3 - lastS3Cmd) > 1.0f) {
                        sendServoCommand(CANServoProtocol.SERVO_3, s3)
                        lastS3Cmd = s3
                        Thread.sleep(2)
                    }
                    
                    if (Math.abs(s4 - lastS4Cmd) > 1.0f) {
                        sendServoCommand(CANServoProtocol.SERVO_4, s4)
                        lastS4Cmd = s4
                        Thread.sleep(2)
                    }
                }
                
                // Increment TX Count
                txCount++
                
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}")
                errorCount++
            } finally {
                isWriting.set(false)
            }
        }
    }
    
    /**
     * Send Serial commands (for Serial mode)
     */
    private fun sendSerialCommands(s1: Float, s2: Float, s3: Float, s4: Float) {
        val port = serialPort ?: return
        try {
            port.write(rollProtocol.formatCommand(s1), 20)
            port.write(pitchProtocol.formatCommand(s2), 20)
            port.write(yawProtocol.formatCommand(s3), 20)
            port.write(extraProtocol.formatCommand(s4), 20)
        } catch (e: Exception) {
            Log.e(TAG, "Serial send error: ${e.message}")
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
            isSerialMode = false
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
    
    // ==================== L431 Power Control ====================
    
    fun sendL431PowerOn(): Boolean {
        if (!isConnected || isSerialMode || waveshare == null) {
            Log.w(TAG, "L431 PowerOn: CAN not connected")
            return false
        }
        val frame = L431Protocol.createPowerOnCommand()
        val success = waveshare?.sendFrame(frame) ?: false
        if (success) Log.i(TAG, "⚡ L431 Power ON sent")
        return success
    }
    
    fun sendL431PowerOff(): Boolean {
        if (!isConnected || isSerialMode || waveshare == null) {
            Log.w(TAG, "L431 PowerOff: CAN not connected")
            return false
        }
        val frame = L431Protocol.createPowerOffCommand()
        val success = waveshare?.sendFrame(frame) ?: false
        if (success) Log.i(TAG, "🔴 L431 Power OFF sent")
        return success
    }
    
    fun sendL431Heartbeat(): Boolean {
        if (!isConnected || isSerialMode || waveshare == null) return false
        val frame = L431Protocol.createHeartbeatCommand()
        return waveshare?.sendFrame(frame) ?: false
    }
}

