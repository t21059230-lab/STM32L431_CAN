package com.example.canphon

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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Main Activity - CAN Servo Controller
 * 
 * Features:
 * - Auto USB connection (no permission dialogs when possible)
 * - Gyroscope-based servo control
 * - 50Hz control loop
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CONTROL_LOOP_INTERVAL_MS = 16L  // 60Hz for servos
        private const val TELEMETRY_INTERVAL_MS = 20L     // 50Hz for telemetry (separate thread)
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
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvRoll: TextView
    private lateinit var tvPitch: TextView
    private lateinit var tvYaw: TextView
    private lateinit var tvServo1: TextView
    private lateinit var tvServo2: TextView
    private lateinit var tvServo3: TextView
    private lateinit var tvServo4: TextView
    private lateinit var tvServo1Fb: TextView
    private lateinit var tvServo2Fb: TextView
    private lateinit var tvServo3Fb: TextView
    private lateinit var tvServo4Fb: TextView
    private lateinit var btnLaunch: Button
    private lateinit var btnRecord: Button
    private lateinit var btnTracking: Button
    
    // Control loop (servo commands only - 60Hz)
    private val handler = Handler(Looper.getMainLooper())
    private var isControlLoopRunning = false
    
    // Telemetry loop (separate thread - 50Hz)
    private var telemetryExecutor: ScheduledExecutorService? = null
    private var isTelemetryRunning = false
    private var uiUpdateCounter = 0
    
    // USB Broadcast Receiver for auto-connect and permissions
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached - auto connecting...")
                    showToast("جهاز USB متصل - جاري الاتصال...")
                    handler.postDelayed({ autoConnect() }, 500)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    showToast("تم فصل الجهاز")
                    busManager.disconnect()
                    updateConnectionStatus()
                    stopControlLoop()
                }
                busManager.getPermissionAction() -> {
                    // Permission granted callback
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Log.i(TAG, "USB permission granted!")
                        showToast("تم منح الإذن - جاري الاتصال...")
                        autoConnect()
                    } else {
                        Log.w(TAG, "USB permission denied")
                        showToast("تم رفض إذن USB")
                    }
                }
            }
        }
    }
    
    // 60Hz Control Loop - SERVO COMMANDS ONLY (fast, no blocking)
    private val controlLoop = object : Runnable {
        override fun run() {
            if (busManager.isConnected) {
                val roll = gyroManager.roll
                val pitch = gyroManager.pitch
                val yaw = gyroManager.yaw
                
                // Send servo commands (non-blocking via background thread)
                busManager.sendAllServoCommands(roll, pitch, yaw)
                
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
            }
            
            if (isControlLoopRunning) {
                handler.postDelayed(this, CONTROL_LOOP_INTERVAL_MS)
            }
        }
    }
    
    // 50Hz Telemetry Loop - SEPARATE THREAD (can block without affecting servos)
    private val telemetryLoop = Runnable {
        try {
            val roll = gyroManager.roll
            val pitch = gyroManager.pitch
            val yaw = gyroManager.yaw
            
            // Update orientation
            TelemetryStreamer.updateOrientation(roll, pitch, yaw)
            
            // Update sensor data (includes battery - slow operation)
            sensorCollector.updateTelemetry()
            
            // Process feedback and update telemetry
            if (busManager.isConnected) {
                busManager.processFeedback()
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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // Initialize
        initializeUI()
        initializeManagers()
        registerUsbReceiver()
        
        // Set connection callback
        busManager.onConnectionChanged = { connected, message ->
            runOnUiThread {
                updateConnectionStatus()
                if (!connected && message.isNotEmpty()) {
                    showToast(message)
                }
            }
        }
        
        // Auto-connect after short delay (for USB stability)
        handler.postDelayed({ autoConnect() }, AUTO_CONNECT_DELAY_MS)
    }
    
    private fun initializeUI() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvRoll = findViewById(R.id.tvRoll)
        tvPitch = findViewById(R.id.tvPitch)
        tvYaw = findViewById(R.id.tvYaw)
        tvServo1 = findViewById(R.id.tvServo1)
        tvServo2 = findViewById(R.id.tvServo2)
        tvServo3 = findViewById(R.id.tvServo3)
        tvServo4 = findViewById(R.id.tvServo4)
        tvServo1Fb = findViewById(R.id.tvServo1Fb)
        tvServo2Fb = findViewById(R.id.tvServo2Fb)
        tvServo3Fb = findViewById(R.id.tvServo3Fb)
        tvServo4Fb = findViewById(R.id.tvServo4Fb)
        btnLaunch = findViewById(R.id.btnLaunch)
        btnRecord = findViewById(R.id.btnRecord)
        btnTracking = findViewById(R.id.btnTracking)
        
        // LAUNCH Button click listener
        btnLaunch.setOnClickListener {
            showToast("🚀 LAUNCH Command Sent!")
            // TODO: Send LAUNCH command via CAN/STM32
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
    }
    
    private fun initializeManagers() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        stm32Manager = STM32Manager(usbManager)
        gyroManager = GyroManager(this)
        dataLogger = DataLogger(this)
        sensorCollector = SensorCollector(this)
        
        // Set orientation callback for UI updates
        gyroManager.onOrientationChanged = { roll, pitch, yaw ->
            runOnUiThread {
                tvRoll.text = String.format("%.1f°", roll)
                tvPitch.text = String.format("%.1f°", pitch)
                tvYaw.text = String.format("%.1f°", yaw)
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
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
    }
    
    /**
     * Auto-connect to USB devices: Telemetry Radio first, then CAN adapter
     */
    private fun autoConnect() {
        Log.d(TAG, "Attempting auto-connect...")
        
        // Show current USB devices
        val devices = usbManager.deviceList
        Log.d(TAG, "Connected USB devices: ${devices.size}")
        devices.forEach { (name, device) ->
            Log.d(TAG, "  $name: VID=${device.vendorId}, PID=${device.productId}")
        }
        
        // 1. Connect to Telemetry Radio FIRST (priority for single device)
        val telemetrySuccess = TelemetryStreamer.connect(usbManager, this)
        if (telemetrySuccess) {
            Log.i(TAG, "✅ Telemetry Radio connected!")
            showToast("📡 تم الاتصال بالتلمتري!")
        } else {
            Log.w(TAG, "Telemetry Radio not found or not connected")
        }
        
        // 2. Connect to CAN adapter (if different device available)
        val canSuccess = busManager.connect(usbManager, this)
        
        if (canSuccess) {
            Log.i(TAG, "✅ CAN Auto-connected successfully!")
            showToast("✅ تم الاتصال بالسيرفوهات!")
        } else {
            Log.w(TAG, "CAN Auto-connect failed: ${busManager.lastError}")
        }
        
        // 3. Connect STM32
        if (stm32Manager.connect()) {
            Log.i(TAG, "✅ STM32 Auto-connected successfully!")
        } else {
            Log.w(TAG, "STM32 not found or not connected")
        }
        
        // Start control loop (for telemetry, even without CAN)
        startControlLoop()
        
        updateConnectionStatus()
    }
    
    private fun updateConnectionStatus() {
        runOnUiThread {
            if (busManager.isConnected) {
                tvConnectionStatus.text = "🟢 متصل (${busManager.connectedServos} سيرفوهات)"
                tvConnectionStatus.setTextColor(0xFF4ECCA3.toInt())
            } else {
                val errorMsg = if (busManager.lastError.isNotEmpty()) {
                    "🔴 ${busManager.lastError}"
                } else {
                    "🔴 غير متصل"
                }
                tvConnectionStatus.text = errorMsg
                tvConnectionStatus.setTextColor(0xFFFF6B6B.toInt())
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
            // Update Commands
            tvServo1.text = String.format("%.1f°", positions[0])  // Front Right
            tvServo2.text = String.format("%.1f°", positions[1])  // Front Left
            tvServo3.text = String.format("%.1f°", positions[2])  // Back Right
            tvServo4.text = String.format("%.1f°", positions[3])  // Back Left
            
            // Update Feedback from SharedBusManager
            updateFeedbackDisplay()
        }
    }
    
    private fun updateFeedbackDisplay() {
        // Get feedback values from SharedBusManager
        val s1Fb = busManager.s1Feedback
        val s2Fb = busManager.s2Feedback
        val s3Fb = busManager.s3Feedback
        val s4Fb = busManager.s4Feedback
        
        // Format feedback: show "--" if no feedback received yet (-999.9 is default)
        tvServo1Fb.text = if (s1Fb > -900f) String.format("%.1f°", s1Fb) else "--"
        tvServo2Fb.text = if (s2Fb > -900f) String.format("%.1f°", s2Fb) else "--"
        tvServo3Fb.text = if (s3Fb > -900f) String.format("%.1f°", s3Fb) else "--"
        tvServo4Fb.text = if (s4Fb > -900f) String.format("%.1f°", s4Fb) else "--"
        
        // Color feedback based on online status
        val status = busManager.servoOnlineStatus
        tvServo1Fb.setTextColor(if (status and 0x01 != 0) 0xFF4ECCA3.toInt() else 0xFFFF6B6B.toInt())
        tvServo2Fb.setTextColor(if (status and 0x02 != 0) 0xFF4ECCA3.toInt() else 0xFFFF6B6B.toInt())
        tvServo3Fb.setTextColor(if (status and 0x04 != 0) 0xFF4ECCA3.toInt() else 0xFFFF6B6B.toInt())
        tvServo4Fb.setTextColor(if (status and 0x08 != 0) 0xFF4ECCA3.toInt() else 0xFFFF6B6B.toInt())
    }
    
    private fun startControlLoop() {
        if (isControlLoopRunning) return
        
        // Start sensors
        gyroManager.start()
        sensorCollector.start()
        
        // Start servo control loop (60Hz on main thread)
        isControlLoopRunning = true
        handler.post(controlLoop)
        Log.i(TAG, "✅ Servo control loop started @ 60Hz")
        
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
        Log.i(TAG, "✅ Telemetry loop started @ 50Hz (background thread)")
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
    
    override fun onResume() {
        super.onResume()
        if (busManager.isConnected) {
            startControlLoop()
        } else {
            // Retry connection on resume
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
        TelemetryStreamer.disconnect()
        
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        
        Log.i(TAG, "MainActivity destroyed, all connections closed")
    }
}