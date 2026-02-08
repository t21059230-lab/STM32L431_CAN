package com.example.canphon.data
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TelemetryStreamer - Streams all telemetry data over USB Serial
 * 
 * Protocol: 73-byte binary frame @ 60Hz
 * Baud Rate: 115200
 * 
 * Frame Structure:
 * [Header 2B][Len 1B][Time 4B][Orientation 6B][Accel 6B][Pressure 4B]
 * [GPS 18B][ServoCmds 8B][ServoFB 8B][ServoStatus 1B][Tracking 8B]
 * [Battery 4B][Temperature 2B][Checksum 1B]
 * 
 * Total: 73 bytes
 */
object TelemetryStreamer {
    
    private const val TAG = "TelemetryStreamer"
    
    // Frame constants
    const val FRAME_HEADER_1 = 0xAA.toByte()
    const val FRAME_HEADER_2 = 0x55.toByte()
    const val FRAME_LENGTH = 70  // Length after header (73 - 3)
    const val TOTAL_FRAME_SIZE = 73
    
    // Baud rate
    const val BAUD_RATE = 115200
    
    // Known telemetry radio VIDs (will try to connect to any non-Waveshare device)
    private val WAVESHARE_VID = 0x1A86  // CH340 - Waveshare CAN adapter
    
    // USB Serial
    private var serialPort: UsbSerialPort? = null
    @Volatile private var isConnected = false
    private var connectedDeviceName: String? = null
    private var lastSendTime: Long = 0L
    private var consecutiveErrors: Int = 0
    private var storedUsbManager: UsbManager? = null
    private var storedContext: android.content.Context? = null
    
    // Background thread for serial writes
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val isWriting = AtomicBoolean(false)
    
    // Frame buffer (reused for efficiency)
    private val frameBuffer = ByteBuffer.allocate(TOTAL_FRAME_SIZE).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }
    
    // Telemetry data holders
    var timestamp: Long = 0L
    
    // Orientation (from GyroManager)
    var roll: Float = 0f
    var pitch: Float = 0f
    var yaw: Float = 0f
    
    // Accelerometer
    var accX: Float = 0f
    var accY: Float = 0f
    var accZ: Float = 0f
    
    // Pressure/Barometer
    var pressure: Float = 0f      // hPa
    var baroAltitude: Float = 0f  // meters
    
    // GPS
    var latitude: Double = 0.0    // degrees
    var longitude: Double = 0.0   // degrees
    var gpsAltitude: Float = 0f   // meters
    var speed: Float = 0f         // m/s
    var heading: Float = 0f       // degrees
    var satellites: Int = 0
    var gpsFix: Int = 0           // 0=No, 1=2D, 2=3D
    var hdop: Float = 0f
    
    // Servo Commands (sent to servos)
    var s1Cmd: Float = 0f
    var s2Cmd: Float = 0f
    var s3Cmd: Float = 0f
    var s4Cmd: Float = 0f
    
    // Servo Feedback (received from servos)
    var s1Fb: Float = 0f
    var s2Fb: Float = 0f
    var s3Fb: Float = 0f
    var s4Fb: Float = 0f
    
    // Servo Online Status (bitmask: bit0=S1, bit1=S2, bit2=S3, bit3=S4)
    var servoOnline: Int = 0
    
    // Tracking
    var targetX: Int = -1
    var targetY: Int = -1
    var targetW: Int = 0
    var targetH: Int = 0
    
    // Battery
    var batteryPercent: Int = 0
    var isCharging: Boolean = false
    var batteryVoltage: Int = 0   // mV
    
    // Temperature
    var temperature: Float = 0f   // °C
    
    // Start time for relative timestamps
    private var startTime: Long = 0L
    
    /**
     * Connect to telemetry USB serial device
     * Connects to FTDI device (VID=0x0403) for telemetry
     * @param excludedDeviceNames List of device names to exclude (e.g. Serial servo device)
     */
    fun connect(usbManager: UsbManager, context: Context, excludedDeviceNames: List<String> = emptyList()): Boolean {
        if (isConnected) {
            Log.d(TAG, "Already connected")
            return true
        }
        
        try {
            // List all USB devices for debugging
            val deviceList = usbManager.deviceList
            Log.d(TAG, "USB devices detected: ${deviceList.size}")
            deviceList.forEach { (name, device) ->
                Log.d(TAG, "  Device: $name, VID=${String.format("0x%04X", device.vendorId)}, PID=${String.format("0x%04X", device.productId)}")
            }
            
            // Find Telemetry device - can be FTDI (0x0403) OR CH340 (0x1A86)
            // CRITICAL: 
            // 1. Must NOT be already claimed by CAN/Servo (uses excludedDeviceNames)
            // 2. CP2102 (0x10C4) is for Serial servos - DO NOT USE IT!
            // 3. STM32 (0x0483) is excluded
            val telemetryDevice = deviceList.values.find { device ->
                val vid = device.vendorId
                val deviceName = device.deviceName
                // Accept FTDI or CH340 for Telemetry, but NOT:
                // - CP2102 (for servos)
                // - STM32
                // - Already claimed devices
                (vid == 0x0403 || vid == 0x1A86) && 
                vid != 0x10C4 &&   // Not CP2102 (servos)
                vid != 0x0483 &&   // Not STM32
                deviceName !in excludedDeviceNames  // Not already claimed
            }
            
            if (telemetryDevice == null) {
                // If no device found, Telemetry not available
                Log.w(TAG, "No Telemetry Radio found (looking for FTDI/CH340, excluded: $excludedDeviceNames)")
                return false
            }
            
            val deviceName = telemetryDevice.deviceName
            val vid = telemetryDevice.vendorId
            val pid = telemetryDevice.productId
            
            Log.d(TAG, "Found Telemetry device: $deviceName, VID=${String.format("0x%04X", vid)}, PID=${String.format("0x%04X", pid)}")
            
            // Check permission
            if (!usbManager.hasPermission(telemetryDevice)) {
                Log.w(TAG, "No permission for device $deviceName - requesting...")
                val intent = android.app.PendingIntent.getBroadcast(
                    context, 
                    0, 
                    android.content.Intent("USB_TELEMETRY_PERMISSION"),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(telemetryDevice, intent)
                return false
            }
            
            // Find driver for Telemetry device
            val defaultProber = UsbSerialProber.getDefaultProber()
            val driver = defaultProber.probeDevice(telemetryDevice)
            
            if (driver == null) {
                Log.e(TAG, "No driver found for Telemetry device")
                return false
            }
                
            // Try to open this device
            val connection = usbManager.openDevice(telemetryDevice)
            if (connection == null) {
                Log.e(TAG, "Failed to open Telemetry device")
                return false
            }
                
            // Configure serial port - FTDI specific settings
            val port = driver.ports[0]
            port.open(connection)
            
            // Set parameters
            port.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            
            // Disable flow control signals - important for FTDI!
            port.dtr = false
            port.rts = false
            
            // Small delay for FTDI chip to initialize
            try { Thread.sleep(100) } catch (e: InterruptedException) { return false }
            
            // Test write - send single byte to verify connection
            try {
                port.write(byteArrayOf(0x00), 1000)
                Log.d(TAG, "Test write successful!")
            } catch (e: Exception) {
                Log.w(TAG, "Test write failed: ${e.message}")
                // Try enabling DTR/RTS
                port.dtr = true
                port.rts = true
                try { Thread.sleep(50) } catch (e: InterruptedException) { return false }
                try {
                    port.write(byteArrayOf(0x00), 1000)
                    Log.d(TAG, "Test write with DTR/RTS successful!")
                } catch (e2: Exception) {
                    Log.e(TAG, "Test write still failed: ${e2.message}")
                    port.close()
                    return false
                }
            }
            
            serialPort = port
            connectedDeviceName = deviceName
            isConnected = true
            startTime = System.currentTimeMillis()
            consecutiveErrors = 0
            
            // Store for reconnection
            storedUsbManager = usbManager
            storedContext = context
            
            Log.i(TAG, "✅ Connected to telemetry device: $deviceName (VID=${String.format("0x%04X", vid)}, PID=${String.format("0x%04X", pid)})")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}", e)
            disconnect()
            return false
        }
    }
    
    /**
     * Disconnect from telemetry device
     */
    fun disconnect() {
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
        serialPort = null
        isConnected = false
        Log.i(TAG, "Disconnected")
    }
    
    /**
     * Reconnect to telemetry device
     */
    private fun reconnect() {
        val manager = storedUsbManager
        val context = storedContext
        
        if (manager == null || context == null) {
            Log.e(TAG, "Cannot reconnect - no stored references")
            return
        }
        
        disconnect()
        try { Thread.sleep(500) } catch (e: InterruptedException) { return }  // Wait before reconnecting
        
        if (connect(manager, context)) {
            Log.i(TAG, "✅ Reconnected successfully!")
            consecutiveErrors = 0
        } else {
            Log.e(TAG, "❌ Reconnection failed")
        }
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * Get connected device name (for exclusion in GPS service)
     */
    fun getConnectedDeviceName(): String? = if (isConnected) connectedDeviceName else null
    
    /**
     * Manual test send - sends "TEST" string on background thread
     */
    fun testSend(): Boolean {
        val port = serialPort
        if (port == null || !isConnected) {
            Log.e(TAG, "❌ Not connected for test send")
            return false
        }
        
        // Don't overlap writes
        if (isWriting.get()) {
            Log.w(TAG, "Write already in progress")
            return false
        }
        
        isWriting.set(true)
        
        writeExecutor.execute {
            try {
                val testData = "TEST\r\n".toByteArray(Charsets.US_ASCII)
                port.write(testData, 1000)
                Log.i(TAG, "✅ Test send successful: ${testData.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test send failed: ${e.message}")
            } finally {
                isWriting.set(false)
            }
        }
        
        return true  // Return true = write queued
    }
    
    /**
     * Build and send telemetry frame @ 60Hz
     * Uses background thread for writes
     */
    private var frameCount = 0L
    
    fun sendFrame() {
        if (!isConnected || serialPort == null) {
            // Log every 60 frames (1 second) to avoid spam
            if (frameCount % 60 == 0L) {
                Log.w(TAG, "sendFrame skipped: isConnected=$isConnected, serialPort=${serialPort != null}")
            }
            frameCount++
            return
        }
        
        // Skip if previous write still in progress
        if (isWriting.get()) {
            return
        }
        
        try {
            // Update timestamp
            timestamp = System.currentTimeMillis() - startTime
            
            // Build frame
            frameBuffer.clear()
            
            // Header (2 bytes)
            frameBuffer.put(FRAME_HEADER_1)
            frameBuffer.put(FRAME_HEADER_2)
            
            // Length (1 byte)
            frameBuffer.put(FRAME_LENGTH.toByte())
            
            // Timestamp (4 bytes) - uint32
            frameBuffer.putInt((timestamp and 0xFFFFFFFFL).toInt())
            
            // Orientation (6 bytes) - int16 × 3
            frameBuffer.putShort((roll * 10).toInt().toShort())
            frameBuffer.putShort((pitch * 10).toInt().toShort())
            frameBuffer.putShort((yaw * 10).toInt().toShort())
            
            // Accelerometer (6 bytes) - int16 × 3
            frameBuffer.putShort((accX * 100).toInt().toShort())
            frameBuffer.putShort((accY * 100).toInt().toShort())
            frameBuffer.putShort((accZ * 100).toInt().toShort())
            
            // Pressure (2 bytes) - uint16
            frameBuffer.putShort(pressure.toInt().toShort())
            
            // Baro Altitude (2 bytes) - int16, meters × 10
            frameBuffer.putShort((baroAltitude * 10).toInt().toShort())
            
            // GPS Latitude (4 bytes) - int32, degrees × 10^7
            frameBuffer.putInt((latitude * 10_000_000).toInt())
            
            // GPS Longitude (4 bytes) - int32, degrees × 10^7
            frameBuffer.putInt((longitude * 10_000_000).toInt())
            
            // GPS Altitude (2 bytes) - int16, meters
            frameBuffer.putShort(gpsAltitude.toInt().toShort())
            
            // Speed (2 bytes) - uint16, cm/s
            frameBuffer.putShort((speed * 100).toInt().toShort())
            
            // Heading (2 bytes) - uint16, degrees × 10
            frameBuffer.putShort((heading * 10).toInt().toShort())
            
            // Satellites (1 byte) - uint8
            frameBuffer.put(satellites.toByte())
            
            // GPS Fix (1 byte) - uint8
            frameBuffer.put(gpsFix.toByte())
            
            // HDOP (2 bytes) - uint16, × 100
            frameBuffer.putShort((hdop * 100).toInt().toShort())
            
            // Servo Commands (8 bytes) - int16 × 4
            frameBuffer.putShort((s1Cmd * 10).toInt().toShort())
            frameBuffer.putShort((s2Cmd * 10).toInt().toShort())
            frameBuffer.putShort((s3Cmd * 10).toInt().toShort())
            frameBuffer.putShort((s4Cmd * 10).toInt().toShort())
            
            // Servo Feedback (8 bytes) - int16 × 4
            frameBuffer.putShort((s1Fb * 10).toInt().toShort())
            frameBuffer.putShort((s2Fb * 10).toInt().toShort())
            frameBuffer.putShort((s3Fb * 10).toInt().toShort())
            frameBuffer.putShort((s4Fb * 10).toInt().toShort())
            
            // Servo Online Status (1 byte) - bitmask
            frameBuffer.put(servoOnline.toByte())
            
            // Tracking (8 bytes) - int16 × 2, uint16 × 2
            frameBuffer.putShort(targetX.toShort())
            frameBuffer.putShort(targetY.toShort())
            frameBuffer.putShort(targetW.toShort())
            frameBuffer.putShort(targetH.toShort())
            
            // Battery (4 bytes)
            frameBuffer.put(batteryPercent.toByte())
            frameBuffer.put(if (isCharging) 1.toByte() else 0.toByte())
            frameBuffer.putShort(batteryVoltage.toShort())
            
            // Temperature (2 bytes) - int16, °C × 10
            frameBuffer.putShort((temperature * 10).toInt().toShort())
            
            // Calculate checksum (XOR of all bytes except checksum itself)
            val data = frameBuffer.array()
            var checksum: Byte = 0
            for (i in 0 until TOTAL_FRAME_SIZE - 1) {
                checksum = (checksum.toInt() xor data[i].toInt()).toByte()
            }
            frameBuffer.put(checksum)
            
            // Copy frame data for background thread
            val frameData = frameBuffer.array().copyOf()
            val currentFrameCount = frameCount
            val currentRoll = roll
            val currentPitch = pitch
            
            // Send on background thread
            isWriting.set(true)
            val port = serialPort ?: return
            
            writeExecutor.execute {
                try {
                    port.write(frameData, 20)
                    frameCount++
                    consecutiveErrors = 0
                    
                    // Log every 60 frames (1 second)
                    if (currentFrameCount % 60 == 0L) {
                        Log.d(TAG, "📡 $currentFrameCount frames, roll=$currentRoll, pitch=$currentPitch")
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors % 60 == 1) {
                        Log.e(TAG, "Write error: ${e.message}")
                    }
                    
                    // After 5 consecutive errors, try to reconnect
                    if (consecutiveErrors == 5) {
                        Log.w(TAG, "Too many errors - reconnecting...")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            reconnect()
                        }
                    }
                } finally {
                    isWriting.set(false)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Frame build error: ${e.message}")
        }
    }
    
    /**
     * Update orientation data (from GyroManager)
     */
    fun updateOrientation(rollDeg: Float, pitchDeg: Float, yawDeg: Float) {
        roll = rollDeg
        pitch = pitchDeg
        yaw = yawDeg
    }
    
    /**
     * Update accelerometer data
     */
    fun updateAccelerometer(x: Float, y: Float, z: Float) {
        accX = x
        accY = y
        accZ = z
    }
    
    /**
     * Update pressure/barometer data
     */
    fun updatePressure(pressureHpa: Float, altitudeMeters: Float) {
        pressure = pressureHpa
        baroAltitude = altitudeMeters
    }
    
    /**
     * Update GPS data
     */
    fun updateGPS(
        lat: Double, lon: Double, alt: Float,
        spd: Float, hdg: Float,
        sats: Int, fix: Int, hdopVal: Float
    ) {
        latitude = lat
        longitude = lon
        gpsAltitude = alt
        speed = spd
        heading = hdg
        satellites = sats
        gpsFix = fix
        hdop = hdopVal
    }
    
    /**
     * Update servo commands (what we send to servos)
     */
    fun updateServoCommands(s1: Float, s2: Float, s3: Float, s4: Float) {
        s1Cmd = s1
        s2Cmd = s2
        s3Cmd = s3
        s4Cmd = s4
    }
    
    /**
     * Update servo feedback (what we receive from servos)
     */
    fun updateServoFeedback(s1: Float, s2: Float, s3: Float, s4: Float) {
        s1Fb = s1
        s2Fb = s2
        s3Fb = s3
        s4Fb = s4
    }
    
    /**
     * Update servo online status
     * @param online Bitmask: bit0=S1, bit1=S2, bit2=S3, bit3=S4
     */
    fun updateServoStatus(online: Int) {
        servoOnline = online
    }
    
    /**
     * Update tracking data
     */
    fun updateTracking(x: Int, y: Int, w: Int, h: Int) {
        targetX = x
        targetY = y
        targetW = w
        targetH = h
    }
    
    /**
     * Update battery data
     */
    fun updateBattery(percent: Int, charging: Boolean, voltageMv: Int) {
        batteryPercent = percent
        isCharging = charging
        batteryVoltage = voltageMv
    }
    
    /**
     * Update temperature
     */
    fun updateTemperature(tempCelsius: Float) {
        temperature = tempCelsius
    }
}

