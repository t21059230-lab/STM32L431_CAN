package com.example.canphon.tracking

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.canphon.R
import com.example.canphon.tracking.GuidanceController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TrackingActivity - Camera-based target tracking screen
 * 
 * Features:
 * - CameraX Preview (Full screen)
 * - HUD Overlay (Military-style)
 * - YOLO Object Detection
 * - Kalman Filter Tracking
 * - Servo control output (Yaw/Pitch)
 */
class TrackingActivity : AppCompatActivity(), TrackingController.TrackingCommandListener {
    
    companion object {
        private const val TAG = "TrackingActivity"
        private const val CAMERA_PERMISSION_CODE = 1001
    }
    
    // UI Elements
    private lateinit var previewView: PreviewView
    private lateinit var trackingOverlay: TrackingOverlayView
    
    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Tracking Components
    private lateinit var guidanceController: GuidanceController
    private lateinit var yoloDetector: YOLODetector
    private lateinit var objectTracker: ObjectTracker
    
    // State
    private var isTracking = false
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFPS = 0
    private var imWidth = 1280
    private var imHeight = 720
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracking)
        
        initUI()
        initComponents()
        
        // تسجيل listener لاستقبال الأوامر
        TrackingController.setListener(this)
        
        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }
    
    private fun initUI() {
        previewView = findViewById(R.id.cameraPreview)
        trackingOverlay = findViewById(R.id.trackingOverlay)
        
        // تفعيل Crosshair
        trackingOverlay.setShowCrosshair(true)
        
        // الحالة الأولية
        trackingOverlay.standStatus = "STAND"
        trackingOverlay.trackStatus = "OFF"
        trackingOverlay.detStatus = "OFF"
        
        // ==================== أزرار التحكم ====================
        
        // زر TRACK
        findViewById<android.widget.Button>(R.id.btnTrack).apply {
            setOnClickListener {
                if (isTracking) {
                    stopTracking()
                    text = "🎯 TRACK"
                    setBackgroundColor(0xFF4CAF50.toInt())
                } else {
                    startTracking()
                    text = "⏹️ STOP"
                    setBackgroundColor(0xFFFF9800.toInt())
                }
            }
        }
        
        // زر LAUNCH
        findViewById<android.widget.Button>(R.id.btnLaunch).setOnClickListener {
            onLaunchPressed()
        }
        
        // زر Back
        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
    
    /**
     * معالج زر LAUNCH
     */
    private fun onLaunchPressed() {
        Log.i(TAG, "🚀 LAUNCH button pressed!")
        
        // إرسال أمر الإطلاق عبر SerialService
        // TODO: نحتاج الوصول لـ SerialService
        Toast.makeText(this, "🚀 LAUNCH!", Toast.LENGTH_SHORT).show()
        
        // تحديث HUD
        trackingOverlay.standStatus = "LAUNCH"
    }
    
    private fun initComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize GuidanceController
        guidanceController = GuidanceController(this)
        guidanceController.init()
        
        // Initialize YOLO Detector
        yoloDetector = YOLODetector(this)
        
        // Initialize Object Tracker
        objectTracker = ObjectTracker()
    }
    
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            
            // Image Analysis for detection
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }
            
            // Use back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                
                // البدء بوضع STAND - انتظار أمر START_TRACK
                // يبدأ البحث فقط عند وصول أمر من PC GCS
                trackingOverlay.standStatus = "STAND"
                trackingOverlay.detStatus = "OFF"
                trackingOverlay.trackStatus = "OFF"
                
                Log.i(TAG, "Camera started - waiting for START_TRACK command")
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // تحديث أبعاد الصورة
            imWidth = imageProxy.width
            imHeight = imageProxy.height
            
            if (isTracking) {
                // Convert to bitmap for detection
                val bitmap = imageProxy.toBitmap()
                
                // Run YOLO detection
                val detections = yoloDetector.detect(bitmap)
                
                // تحويل إلى Rect list
                val rects = detections.map { it }
                
                // تحديث الـ tracker
                if (rects.isNotEmpty()) {
                    val bestTarget = rects.maxByOrNull { it.width() * it.height() }
                    
                    if (bestTarget != null) {
                        // حساب خطأ التتبع
                        val centerX = imWidth / 2f
                        val centerY = imHeight / 2f
                        val targetCenterX = bestTarget.centerX().toFloat()
                        val targetCenterY = bestTarget.centerY().toFloat()
                        
                        val errorX = (targetCenterX - centerX) / centerX
                        val errorY = (targetCenterY - centerY) / centerY
                        
                        // تحديث التحكم
                        guidanceController.updateTrackingError(errorX, errorY)
                        
                        // Get servo angles for display (scale to ±15 for HUD)
                        val servoAngles = guidanceController.getServoAngles()
                        // Use normalized error * 15 for scale display
                        val yaw = (errorX * 15f).coerceIn(-15f, 15f)
                        val pitch = (-errorY * 15f).coerceIn(-15f, 15f)
                        
                        // تحديث HUD
                        handler.post {
                            trackingOverlay.setImageDimensions(imWidth, imHeight)
                            trackingOverlay.updateTrackingRects(listOf(bestTarget))
                            trackingOverlay.setTrackingMode(true)
                            trackingOverlay.yawValue = yaw
                            trackingOverlay.pitchValue = pitch
                            trackingOverlay.standStatus = "TRACK"
                            trackingOverlay.trackStatus = "ON"
                            trackingOverlay.txValue = bestTarget.centerX()
                            trackingOverlay.tyValue = bestTarget.centerY()
                            trackingOverlay.twValue = bestTarget.width()
                            trackingOverlay.thValue = bestTarget.height()
                            trackingOverlay.invalidate()  // إعادة الرسم!
                        }
                    }
                } else {
                    // لا توجد أهداف
                    handler.post {
                        trackingOverlay.setImageDimensions(imWidth, imHeight)
                        trackingOverlay.updateTrackingRects(emptyList())
                        trackingOverlay.setTrackingMode(false)
                        trackingOverlay.standStatus = "SEARCH"
                        trackingOverlay.trackStatus = "OFF"
                        trackingOverlay.yawValue = 0f
                        trackingOverlay.pitchValue = 0f
                        trackingOverlay.invalidate()  // إعادة الرسم!
                    }
                }
            }
            
            // تحديث FPS
            updateFps()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }
    
    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFPS = frameCount
            frameCount = 0
            lastFpsTime = now
            
            // تحديث HUD
            handler.post {
                trackingOverlay.tCounter = currentFPS
            }
        }
    }
    
    private fun startTracking() {
        isTracking = true
        guidanceController.startTracking()
        
        trackingOverlay.standStatus = "SEARCH"
        trackingOverlay.detStatus = "ON"
        
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopTracking() {
        isTracking = false
        guidanceController.stopTracking()
        
        trackingOverlay.standStatus = "STAND"
        trackingOverlay.trackStatus = "OFF"
        trackingOverlay.detStatus = "OFF"
        trackingOverlay.updateTrackingRects(emptyList())
        
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // إلغاء تسجيل listener
        TrackingController.setListener(null)
        
        cameraExecutor.shutdown()
        guidanceController.stop()
    }
    
    // ==================== TrackingController.TrackingCommandListener ====================
    
    /**
     * يُستدعى عند وصول أمر START_TRACK من PC GCS
     */
    override fun onStartSearch() {
        Log.i(TAG, "🔍 onStartSearch() - بدء البحث والتتبع")
        handler.post {
            startTracking()
        }
    }
    
    /**
     * يُستدعى عند وصول أمر STOP_TRACK من PC GCS
     */
    override fun onStopSearch() {
        Log.i(TAG, "⏹️ onStopSearch() - إيقاف البحث والتتبع")
        handler.post {
            stopTracking()
        }
    }
}
