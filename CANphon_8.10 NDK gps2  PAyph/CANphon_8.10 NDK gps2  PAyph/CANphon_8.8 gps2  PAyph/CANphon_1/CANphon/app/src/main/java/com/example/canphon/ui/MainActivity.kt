package com.example.canphon.ui
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.example.canphon.gps.GPSActivity
import com.example.canphon.gps.SerialGpsService

/**
 * Main Activity - CAN Servo Controller
 * 
 * Features:
 * - Auto USB connection (no permission dialogs when possible)
 * - Gyroscope-based servo control
 * - 100Hz control loop
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CONTROL_LOOP_INTERVAL_MS = 33L  // 30Hz for servos (Human reaction limit)
        private const val TELEMETRY_INTERVAL_MS = 33L     // 30Hz for telemetry (synced)
        private const val AUTO_CONNECT_DELAY_MS = 1000L
    }
    
    // Managers
    private val busManager = SharedBusManager.getInstance()
    private lateinit var stm32Manager: STM32Manager
    private lateinit var gyroManager: GyroManager
    private lateinit var usbManager: UsbManager
    private lateinit var dataLogger: DataLogger
    private lateinit var sensorCollector: SensorCollector  // For telemetry
    
    // UI Elements
    private lateinit var statusCAN: TextView
    private lateinit var statusSTM32: TextView
    private lateinit var statusTelemetry: TextView
    private lateinit var statusGPS: TextView
    private lateinit var tvRoll: TextView
    private lateinit var tvPitch: TextView
    private lateinit var tvYaw: TextView
    private lateinit var tvServo1: TextView
    private lateinit var tvServo2: TextView
    private lateinit var tvServo3: TextView
    private lateinit var tvServo4: TextView
    private lateinit var btnLaunch: Button
    private lateinit var btnRecord: Button
    private lateinit var btnTracking: Button
    private lateinit var btnGPS: Button
    private lateinit var btnRawSensors: Button
    
    // L431 GPIO Control Buttons (NEW)
    private lateinit var btnL431PowerOn: Button
    private lateinit var btnL431PowerOff: Button
    
    // Coordinate Input Fields
    private lateinit var etLaunchLat: EditText
    private lateinit var etLaunchLon: EditText
    private lateinit var etTargetLat: EditText
    private lateinit var etTargetLon: EditText
    
    // External GPS Service
    private var gpsService: SerialGpsService? = null
    
    // Control loop (servo commands only - 100Hz)
    private val handler = Handler(Looper.getMainLooper())
    private var isControlLoopRunning = false
    
    // Telemetry loop (separate thread - 100Hz)
    private var telemetryExecutor: ScheduledExecutorService? = null
    private var isTelemetryRunning = false
    private var uiUpdateCounter = 0
    
    // USB Broadcast Receiver for auto-connect and permissions
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached - auto connecting...")
                    // showToast("جهاز USB متصل - جاري الاتصال...") // Toast removed
                    handler.postDelayed({ autoConnect() }, 500)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    // showToast("تم فصل الجهاز") // Toast removed
                    busManager.disconnect()
                    busManager.onConnectionChanged?.invoke(false, "Disconnected") // Trigger UI update
                    updateConnectionStatus()
                    stopControlLoop()
                }
                busManager.getPermissionAction() -> {
                    // Permission granted callback
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.i(TAG, "USB permission granted!")
                        // showToast("تم منح الإذن - جاري الاتصال...") // Toast removed
                        autoConnect()
                    } else {
                        Log.w(TAG, "USB permission denied")
                        showToast("تم رفض إذن USB")
                    }
                }
            }
        }
    }
    
    // 100Hz Control Loop - SERVO COMMANDS ONLY (fast, no blocking)
    private val controlLoop = object : Runnable {
        override fun run() {
            val roll = gyroManager.roll
            val pitch = gyroManager.pitch
            val yaw = gyroManager.yaw
            
            // ALWAYS update servo commands (for telemetry)
            // sendAllServoCommands now updates lastS1Cmd even if CAN not connected
            busManager.sendAllServoCommands(roll, pitch, yaw)
            
            // Fixed: Feedback is now processed in background thread (SharedBusManager)
            // No need to call manually here to avoid contention
            
            // Update UI every 3 frames (~20Hz) to reduce load
            if (uiUpdateCounter++ % 3 == 0) {
                updateServoPositions()
            }
            
            // Log data if recording
            if (dataLogger.isRecording()) {
                val positions = busManager.getServoPositions(roll, pitch)
                dataLogger.logData(
                    roll = roll,
                    pitch = pitch,
                    servo1Cmd = positions[0],
                    servo2Cmd = positions[1],
                    servo3Cmd = positions[2],
                    servo4Cmd = positions[3]
                )
                if (dataLogger.getRecordCount() % 60 == 0) {
                    updateRecordButton()
                }
            }
            
            if (isControlLoopRunning) {
                handler.postDelayed(this, CONTROL_LOOP_INTERVAL_MS)
            }
        }
    }
    
    // 100Hz Telemetry Loop - SEPARATE THREAD (can block without affecting servos)
    private val telemetryLoop = Runnable {
        try {
            val roll = gyroManager.roll
            val pitch = gyroManager.pitch
            val yaw = gyroManager.yaw
            
            // Update orientation
            TelemetryStreamer.updateOrientation(roll, pitch, yaw)
            
            // Calculate servo commands directly (like OLD project!)
            // X-Configuration Mixing: ±25° range
            val s1Cmd = (-roll + pitch).coerceIn(-25f, 25f)  // Front-Left
            val s2Cmd = (+roll + pitch).coerceIn(-25f, 25f)  // Front-Right  
            val s3Cmd = (-roll - pitch).coerceIn(-25f, 25f)  // Back-Left
            val s4Cmd = (+roll - pitch).coerceIn(-25f, 25f)  // Back-Right
            
            // Update servo commands
            TelemetryStreamer.updateServoCommands(s1Cmd, s2Cmd, s3Cmd, s4Cmd)
            
            // Update sensor data (includes battery - slow operation)
            sensorCollector.updateTelemetry()
            
            // Update feedback values (read in sendAllServoCommands on same thread!)
            if (busManager.isConnected) {
                busManager.updateTelemetry()
            }
            
            // Send telemetry frame
            TelemetryStreamer.sendFrame()
            
        } catch (e: Exception) {
            Log.e(TAG, "Telemetry error: ${e.message}")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e(TAG, "enableEdgeToEdge failed: ${e.message}")
        }
        
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            Log.e(TAG, "setContentView failed: ${e.message}")
            Toast.makeText(this, "Layout error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        
        try {
            // Handle window insets
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        } catch (e: Exception) {
            Log.e(TAG, "WindowInsets failed: ${e.message}")
        }
        
        try {
            initializeUI()
        } catch (e: Exception) {
            Log.e(TAG, "initializeUI failed: ${e.message}")
            Toast.makeText(this, "UI error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        try {
            initializeManagers()
        } catch (e: Exception) {
            Log.e(TAG, "initializeManagers failed: ${e.message}")
            Toast.makeText(this, "Managers error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        try {
            registerUsbReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "registerUsbReceiver failed: ${e.message}")
        }
        
        // Set connection callback
        busManager.onConnectionChanged = { connected, message ->
            runOnUiThread {
                updateConnectionStatus()
                if (!connected && message.isNotEmpty() && message != "Disconnected") {
                   // showToast(message)
                }
            }
        }
        
        // Auto-connect after short delay (for USB stability)
        handler.postDelayed({ autoConnect() }, AUTO_CONNECT_DELAY_MS)
    }
    
    private fun initializeUI() {
        statusCAN = findViewById(R.id.statusCAN)
        statusSTM32 = findViewById(R.id.statusSTM32)
        statusTelemetry = findViewById(R.id.statusTelemetry)
        statusGPS = findViewById(R.id.statusGPS)
        
        tvRoll = findViewById(R.id.tvRoll)
        tvPitch = findViewById(R.id.tvPitch)
        tvYaw = findViewById(R.id.tvYaw)
        tvServo1 = findViewById(R.id.tvServo1)
        tvServo2 = findViewById(R.id.tvServo2)
        tvServo3 = findViewById(R.id.tvServo3)
        tvServo4 = findViewById(R.id.tvServo4)
        btnLaunch = findViewById(R.id.btnLaunch)
        btnRecord = findViewById(R.id.btnRecord)
        btnTracking = findViewById(R.id.btnTracking)
        btnGPS = findViewById(R.id.btnGPS)
        btnRawSensors = findViewById(R.id.btnRawSensors)
        
        // Initialize Coordinate Fields
        etLaunchLat = findViewById(R.id.etLaunchLat)
        etLaunchLon = findViewById(R.id.etLaunchLon)
        etTargetLat = findViewById(R.id.etTargetLat)
        etTargetLon = findViewById(R.id.etTargetLon)
        
        // LAUNCH Button Long Click - Diagnostic: Show Active Connections mapping
        btnLaunch.setOnLongClickListener {
            val sb = StringBuilder("🔍 USB Diagnostics:\n")
            
            val devices = usbManager.deviceList
            devices.values.forEachIndexed { index, device ->
                sb.append("#$index: ${device.deviceName} (ID: ${String.format("%04X", device.vendorId)})\n")
            }
            
            sb.append("\n-- Active Map --\n")
            sb.append("🚀 STM32: ${stm32Manager.getConnectedDeviceName() ?: "❌"}\n")
            sb.append("🤖 CAN: ${busManager.getConnectedDeviceName() ?: "❌"}\n")
            
            showToast(sb.toString())
            true
        }
        
        // LAUNCH Button click listener - Toggles STM32 LED
        btnLaunch.setOnClickListener {
            // Active Connect: If not connected, try to connect first!
            if (!stm32Manager.isConnected) {
                if (!stm32Manager.connect(this)) {
                    val error = if (stm32Manager.lastError.isNotEmpty()) stm32Manager.lastError else "فشل الاتصال"
                    showToast("❌ $error")
                    // Update indicators
                    updateConnectionStatus()
                    return@setOnClickListener
                }
            }
            // Update indicators on successful connect
            updateConnectionStatus()
            
            // Toggle Logic
            if (stm32Manager.toggleLed()) {
                if (stm32Manager.isLedOn) {
                    showToast("🚀 LAUNCH ACTIVATED!")
                    btnLaunch.text = "✖ ABORT LAUNCH"
                    btnLaunch.background.setTint(0xFFFF6B6B.toInt()) // Red
                } else {
                    showToast("🛑 LAUNCH STOPPED")
                    btnLaunch.text = "🚀 LAUNCH"
                    btnLaunch.background.setTint(0xFFF44336.toInt()) // Original Red/Orange
                }
            } else {
                showToast("❌ STM32 Disconnected abruptly")
                updateConnectionStatus()
            }
        }

        // Record Button click listener
        btnRecord.setOnClickListener {
            if (dataLogger.isRecording()) {
                val fileName = dataLogger.stopRecording()
                showToast("📁 تم الحفظ: Downloads/$fileName")
                updateRecordButton()
            } else {
                if (dataLogger.startRecording()) {
                    showToast("⏺ بدأ التسجيل...")
                    updateRecordButton()
                } else {
                    showToast("❌ فشل بدء التسجيل")
                }
            }
        }

        // Tracking Button click listener
        btnTracking.setOnClickListener {
            startActivity(Intent(this, com.example.canphon.tracking.TrackingActivity::class.java))
        }
        
        // GPS Button click listener - Open GPS Viewer
        btnGPS.setOnClickListener {
            startActivity(Intent(this, GPSActivity::class.java))
        }
        
        // Raw Sensors Button click listener - Open Raw Sensors Activity
        btnRawSensors.setOnClickListener {
            startActivity(Intent(this, RawSensorsActivity::class.java))
        }

        // Graph Button click listener
        findViewById<Button>(R.id.btnGraph).setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java))
        }
        
        // ==================== L431 GPIO Control (NEW) ====================
        btnL431PowerOn = findViewById(R.id.btnL431PowerOn)
        btnL431PowerOff = findViewById(R.id.btnL431PowerOff)
        
        // L431 Power ON Button click listener
        btnL431PowerOn.setOnClickListener {
            // Check connection status with specific messages
            if (!busManager.isConnected) {
                showToast("❌ CAN غير متصل - تأكد من توصيل Waveshare")
                return@setOnClickListener
            }
            if (busManager.isSerialMode) {
                showToast("❌ الوضع Serial! L431 يتطلب Waveshare CAN")
                return@setOnClickListener
            }
            
            if (busManager.sendL431PowerOn()) {
                showToast("⚡ تم التشغيل (PA9, PA10)")
                btnL431PowerOn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E8449.toInt())
            } else {
                showToast("❌ فشل الإرسال - تحقق من Logcat")
            }
        }
        
        // L431 Power OFF Button click listener
        btnL431PowerOff.setOnClickListener {
            if (!busManager.isConnected) {
                showToast("❌ CAN غير متصل - تأكد من توصيل Waveshare")
                return@setOnClickListener
            }
            if (busManager.isSerialMode) {
                showToast("❌ الوضع Serial! L431 يتطلب Waveshare CAN")
                return@setOnClickListener
            }
            
            if (busManager.sendL431PowerOff()) {
                showToast("🔴 تم الإطفاء (PA5)")
                btnL431PowerOn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF27AE60.toInt())
            } else {
                showToast("❌ فشل الإرسال - تحقق من Logcat")
            }
        }
    }
    
    private fun initializeManagers() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        stm32Manager = STM32Manager(usbManager)
        gyroManager = GyroManager(this)
        dataLogger = DataLogger(this)
        sensorCollector = SensorCollector(this)
        
        // Initialize GPS Service (singleton)
        gpsService = SerialGpsService.getInstance(this)
        // Pass GPS service to SensorCollector for telemetry integration
        sensorCollector.externalGpsService = gpsService
        
        // Set orientation callback for UI updates
        gyroManager.onOrientationChanged = { roll, pitch, yaw ->
            runOnUiThread {
                tvRoll.text = String.format(java.util.Locale.US, "%.1f°", roll)
                tvPitch.text = String.format(java.util.Locale.US, "%.1f°", pitch)
                tvYaw.text = String.format(java.util.Locale.US, "%.1f°", yaw)
            }
        }
        
        Log.i(TAG, "Managers initialized")
    }
    
    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(busManager.getPermissionAction())
        }
        // Android 13+ requires RECEIVER_NOT_EXPORTED flag
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }
    
    /**
     * Auto-connect to USB devices with SMART PRIORITY:
     * 1. STM32 (Specific Handshake "ID?")
     * 2. CAN/Serial Adapter (Generic Probing, Excludes STM32)
     * 3. Telemetry (Excludes STM32 and Serial Servo device)
     */
    private fun autoConnect() {
        Log.d(TAG, "Attempting auto-connect with Smart Priority...")
        
        // Show current USB devices
        val devices = usbManager.deviceList
        Log.d(TAG, "Connected USB devices: ${devices.size}")
        devices.forEach { (name, device) ->
            Log.d(TAG, "  $name: VID=${device.vendorId}, PID=${device.productId}")
        }
        
        // ===================================
        // PRIORITY 1: STM32 (Most Specific)
        // ===================================
        var stm32Name: String? = null
        if (stm32Manager.connect(this)) {
            Log.i(TAG, "✅ STM32 Verified & Connected!")
            stm32Name = stm32Manager.getConnectedDeviceName()
        } else {
            Log.w(TAG, "STM32 not found or not connected")
        }
        
        // ===================================
        // PRIORITY 2: CAN/Serial Adapter (Excluded STM32)
        // ===================================
        // Avoid "stealing" the STM32 device if CAN probe is aggressive
        val excludedForCAN = if (stm32Name != null) listOf(stm32Name) else emptyList()
        
        // Connect CAN/Serial (using the new excludedDevices param)
        val canSuccess = busManager.connect(usbManager, this, excludedForCAN)
        
        // Track if Serial mode is active and which device is used
        var canServoDevice: String? = null  // FIXED: Track CAN device too, not just Serial!
        
        if (canSuccess) {
            // ALWAYS get the connected device name, regardless of mode!
            canServoDevice = busManager.getConnectedDeviceName()
            if (busManager.isSerialMode) {
                Log.i(TAG, "✅ Serial Servo Mode connected! Device: $canServoDevice")
            } else {
                Log.i(TAG, "✅ CAN Mode auto-connected! Device: $canServoDevice")
            }
        } else {
            Log.w(TAG, "CAN/Serial Auto-connect failed: ${busManager.lastError}")
        }
        
        // ===================================
        // PRIORITY 3: Telemetry (Excludes STM32 AND CAN/Serial Servo)
        // ===================================
        // Build exclusion list for Telemetry
        val excludedForTelemetry = mutableListOf<String>()
        stm32Name?.let { excludedForTelemetry.add(it) }
        canServoDevice?.let { excludedForTelemetry.add(it) }  // FIXED: Always exclude!
        
        val telemetrySuccess = TelemetryStreamer.connect(usbManager, this, excludedForTelemetry)
        if (telemetrySuccess) {
            Log.i(TAG, "✅ Telemetry Connected!")
        } else {
            Log.w(TAG, "Telemetry not found (excluded: $excludedForTelemetry)")
        }
        
        // ===================================
        // PRIORITY 4: External GPS (Excludes all above)
        // ===================================
        val excludedForGPS = mutableListOf<String>()
        stm32Name?.let { excludedForGPS.add(it) }
        canServoDevice?.let { excludedForGPS.add(it) }  // FIXED: Use canServoDevice
        TelemetryStreamer.getConnectedDeviceName()?.let { excludedForGPS.add(it) }
        
        gpsService?.excludedDeviceNames = excludedForGPS
        gpsService?.start()
        
        if (gpsService?.isConnected == true) {
            Log.i(TAG, "✅ External GPS Connected!")
        } else {
            Log.w(TAG, "External GPS not connected (excluded: $excludedForGPS)")
        }
        
        // ===================================
        // START LOOPS: Even if CAN/Serial is not connected!
        // ===================================
        // Telemetry should always stream if connected, regardless of servo connection
        startControlLoop()
        
        // Update UI
        updateConnectionStatus()
    }
    
    private fun updateConnectionStatus() {
        runOnUiThread {
            // CAN/Serial Status
            if (busManager.isConnected) {
                val mode = if (busManager.isSerialMode) "Serial" else "CAN"
                statusCAN.text = "🟢 $mode (TX:${busManager.txCount} | E:${busManager.errorCount})"
                statusCAN.setTextColor(0xFF4ECCA3.toInt()) // Green
            } else {
                statusCAN.text = "🔴 CAN (TX:${busManager.txCount} | E:${busManager.errorCount})"
                statusCAN.setTextColor(0xFFFF6B6B.toInt()) // Red
            }

            // STM32 Status
            if (stm32Manager.isConnected) {
                statusSTM32.text = "🟢 Launch (Last: ${String.format("%.1f", busManager.lastS1Cmd)})"
                statusSTM32.setTextColor(0xFF4ECCA3.toInt()) // Green
            } else {
                statusSTM32.text = "🔴 Launch (Last: ${String.format("%.1f", busManager.lastS1Cmd)})"
                statusSTM32.setTextColor(0xFFFF6B6B.toInt()) // Red
            }

            // Telemetry Status
            if (TelemetryStreamer.isConnected()) {
                statusTelemetry.text = "🟢 Telemetry"
                statusTelemetry.setTextColor(0xFF4ECCA3.toInt()) // Green
            } else {
                statusTelemetry.text = "🔴 Telemetry"
                statusTelemetry.setTextColor(0xFFFF6B6B.toInt()) // Red
            }

            // GPS Status
            if (gpsService?.isConnected == true) {
                statusGPS.text = "🟢 GPS"
                statusGPS.setTextColor(0xFF4ECCA3.toInt()) // Green
            } else {
                statusGPS.text = "🔴 GPS"
                statusGPS.setTextColor(0xFFFF6B6B.toInt()) // Red
            }
        }
    }
    
    private fun updateRecordButton() {
        runOnUiThread {
            if (dataLogger.isRecording()) {
                btnRecord.text = "⏹ إيقاف (${dataLogger.getRecordCount()})"
                btnRecord.setBackgroundColor(0xFFE74C3C.toInt())  // Red
            } else {
                btnRecord.text = "⏺ بدء التسجيل"
                btnRecord.setBackgroundColor(0xFF3498DB.toInt())  // Blue
            }
        }
    }
    
    private fun updateServoPositions() {
        val positions = busManager.getServoPositions(gyroManager.roll, gyroManager.pitch)
        runOnUiThread {
            tvServo1.text = String.format(java.util.Locale.US, "%.1f°", positions[0])  // Front Right
            tvServo2.text = String.format(java.util.Locale.US, "%.1f°", positions[1])  // Front Left
            tvServo3.text = String.format(java.util.Locale.US, "%.1f°", positions[2])  // Back Right
            tvServo4.text = String.format(java.util.Locale.US, "%.1f°", positions[3])  // Back Left
        }
    }
    
    private fun startControlLoop() {
        if (isControlLoopRunning) return
        
        // Start sensors
        gyroManager.start()
        sensorCollector.start()
        
        // Start servo control loop (60Hz on main thread)
        isControlLoopRunning = true
        handler.post(controlLoop)
        Log.i(TAG, "✅ Servo control loop started @ 100Hz")
        
        // Start telemetry loop (50Hz on background thread)
        startTelemetryLoop()
    }
    
    private fun startTelemetryLoop() {
        if (isTelemetryRunning) return
        
        telemetryExecutor = Executors.newSingleThreadScheduledExecutor()
        telemetryExecutor?.scheduleAtFixedRate(
            telemetryLoop,
            0,
            TELEMETRY_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        isTelemetryRunning = true
        Log.i(TAG, "✅ Telemetry loop started @ 100Hz (background thread)")
    }
    
    private fun stopControlLoop() {
        // Stop servo control loop
        isControlLoopRunning = false
        handler.removeCallbacks(controlLoop)
        
        // Stop telemetry loop
        stopTelemetryLoop()
        
        // Stop sensors
        gyroManager.stop()
        sensorCollector.stop()
        
        Log.i(TAG, "Control loops stopped")
    }
    
    private fun stopTelemetryLoop() {
        isTelemetryRunning = false
        telemetryExecutor?.shutdown()
        try {
            telemetryExecutor?.awaitTermination(100, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        telemetryExecutor = null
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Get validated Launch Coordinates from input fields
     * @return Pair(latitude, longitude) or null if invalid
     */
    fun getLaunchCoordinates(): Pair<Double, Double>? {
        val lat = etLaunchLat.text.toString().toDoubleOrNull() ?: return null
        val lon = etLaunchLon.text.toString().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Pair(lat, lon)
    }
    
    /**
     * Get validated Target Coordinates from input fields
     * @return Pair(latitude, longitude) or null if invalid
     */
    fun getTargetCoordinates(): Pair<Double, Double>? {
        val lat = etTargetLat.text.toString().toDoubleOrNull() ?: return null
        val lon = etTargetLon.text.toString().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Pair(lat, lon)
    }
    
    override fun onResume() {
        super.onResume()
        if (busManager.isConnected) {
            // Ensure loop is running if connected
            if (!isControlLoopRunning) {
               startControlLoop()
            }
        } else {
            // Retry connection on resume if disconnected
            handler.postDelayed({ autoConnect() }, 500)
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopControlLoop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopControlLoop()
        busManager.disconnect()
        stm32Manager.disconnect()
        TelemetryStreamer.disconnect()
        
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        
        Log.i(TAG, "MainActivity destroyed, all connections closed")
    }
}

