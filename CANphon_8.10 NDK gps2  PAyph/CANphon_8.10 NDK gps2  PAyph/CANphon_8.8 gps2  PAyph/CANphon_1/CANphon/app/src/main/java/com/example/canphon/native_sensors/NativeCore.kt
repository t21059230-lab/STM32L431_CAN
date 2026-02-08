package com.example.canphon.native_sensors
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.util.Log

/**
 * NativeCore - النواة الأصلية عالية الأداء
 * 
 * واجهة موحدة لجميع الدوال الأصلية:
 * - Sensors (الحساسات)
 * - Kalman Filter (التتبع)
 * - Filters (المرشحات)
 * - Guidance (التوجيه)
 * - Sensor Fusion (دمج GPS+IMU)
 */
object NativeCore {
    
    private const val TAG = "NativeCore"
    
    init {
        try {
            System.loadLibrary("canphon_native")
            Log.i(TAG, "✅ Native library loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load native library: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // Kalman Filter
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun kalmanInit(x: Double, y: Double, processNoise: Double, measurementNoise: Double)
    external fun kalmanPredict(): DoubleArray  // [x, y]
    external fun kalmanUpdate(measuredX: Double, measuredY: Double)
    external fun kalmanGetState(): DoubleArray  // [x, y, vx, vy]
    external fun kalmanPredictFuture(steps: Int): DoubleArray  // [x, y]
    external fun kalmanReset()
    external fun kalmanGetUncertainty(): Double
    
    // ═══════════════════════════════════════════════════════════════════════
    // IIR Low-Pass Filter
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun iirInit(id: Int, alpha: Float)
    external fun iirUpdate(id: Int, input: Float): Float
    external fun iirGet(id: Int): Float
    external fun iirReset(id: Int)
    
    // ═══════════════════════════════════════════════════════════════════════
    // Vector Filter (for 3D data)
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun vecFilterInit(id: Int, alpha: Float)
    external fun vecFilterUpdate(id: Int, x: Float, y: Float, z: Float): FloatArray  // [x, y, z]
    
    // ═══════════════════════════════════════════════════════════════════════
    // Moving Average Filter
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun maInit(id: Int, size: Int)
    external fun maUpdate(id: Int, input: Float): Float
    
    // ═══════════════════════════════════════════════════════════════════════
    // Guidance Controller
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun guidanceInit(alpha: Float, cmdMax: Float)
    external fun guidanceStart()
    external fun guidanceStop()
    external fun guidanceUpdate(errorX: Float, errorY: Float, dt: Float)
    external fun guidanceGetCommands(): FloatArray  // [pitch, yaw]
    external fun guidanceGetServoAngles(): FloatArray  // [s0, s1, s2, s3]
    
    // ═══════════════════════════════════════════════════════════════════════
    // PID Controller
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun pidInit(axis: Int, kp: Float, ki: Float, kd: Float, 
                        outputMin: Float, outputMax: Float, alpha: Float)
    external fun pidUpdate(axis: Int, error: Float, dt: Float): Float
    external fun pidReset(axis: Int)
    
    // ═══════════════════════════════════════════════════════════════════════
    // Sensor Fusion (GPS + IMU)
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun fusionInit(alpha: Float)
    external fun fusionUpdateGps(lat: Double, lon: Double, alt: Double, timestamp: Long)
    external fun fusionIntegrateImu(accelN: Float, accelE: Float, accelD: Float, dt: Float)
    external fun fusionGetPosition(): DoubleArray  // [lat, lon, alt]
    external fun fusionGetVelocity(): DoubleArray  // [velN, velE, velD]
    external fun fusionHasFix(): Boolean
    
    // ═══════════════════════════════════════════════════════════════════════
    // Utility Functions
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun applyDeadzone(value: Float, deadzone: Float, lastValue: Float): Float
    external fun clampf(value: Float, min: Float, max: Float): Float
    
    // ═══════════════════════════════════════════════════════════════════════
    // Object Tracker (Phase 1: Native Tracking Core)
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun trackerInit()
    external fun trackerStart(x: Int, y: Int, w: Int, h: Int)
    external fun trackerSetImageSize(w: Int, h: Int)
    external fun trackerUpdate(detections: IntArray): IntArray  // Returns [found, x, y, w, h, confidence]
    external fun trackerGetPosition(): IntArray  // [x, y, w, h]
    external fun trackerGetPrediction(): IntArray  // [x, y]
    external fun trackerGetMode(): Int  // 0=OFF, 1=SEARCH, 2=TRACK, 3=LOST
    external fun trackerGetConfidence(): Float
    external fun trackerIsTracking(): Boolean
    external fun trackerReset()
    external fun trackerStop()
    external fun trackerEnablePrediction(enable: Boolean)
    
    // ═══════════════════════════════════════════════════════════════════════
    // Target Discriminator
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun discriminatorInit()
    external fun discriminatorEvaluate(
        x: Int, y: Int, w: Int, h: Int,
        lastX: Int, lastY: Int, lastW: Int, lastH: Int,
        imgW: Int, imgH: Int
    ): Float
    external fun discriminatorEvaluateMultiple(
        rects: IntArray,  // [x1,y1,w1,h1, x2,y2,w2,h2, ...]
        lastX: Int, lastY: Int, lastW: Int, lastH: Int,
        imgW: Int, imgH: Int
    ): FloatArray  // Score for each target
    external fun discriminatorSelectBest(scores: FloatArray, minScore: Float): Int  // Best index or -1
    external fun discriminatorReset()
    
    // ═══════════════════════════════════════════════════════════════════════
    // Telemetry (Phase 2: Native Frame Builder)
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun telemetryInit()
    external fun telemetrySetOrientation(roll: Float, pitch: Float, yaw: Float)
    external fun telemetrySetAccelerometer(x: Float, y: Float, z: Float)
    external fun telemetrySetGPS(
        lat: Double, lon: Double, alt: Float, speed: Float, heading: Float,
        satellites: Int, fix: Int, hdop: Float
    )
    external fun telemetrySetServoCmd(s1: Float, s2: Float, s3: Float, s4: Float)
    external fun telemetrySetServoFb(s1: Float, s2: Float, s3: Float, s4: Float)
    external fun telemetrySetServoStatus(online: Int)
    external fun telemetrySetTracking(x: Int, y: Int, w: Int, h: Int)
    external fun telemetrySetBattery(percent: Int, charging: Int, voltageMv: Int)
    external fun telemetryBuildFrame(): ByteArray  // Returns 73-byte frame ready to send
    
    // ═══════════════════════════════════════════════════════════════════════
    // Servo Protocol (Phase 3: Native Command Formatting)
    // ═══════════════════════════════════════════════════════════════════════
    
    external fun servoFormatCommand(servoId: Int, angleDegrees: Float): ByteArray  // Returns 5-byte command
    external fun servoFormatFeedbackRequest(servoId: Int): ByteArray  // Returns 5-byte request
    external fun servoAngleToPosition(angleDegrees: Float): Int  // -25° to +25° → 0-16383
    external fun servoPositionToAngle(position: Int): Float  // 0-16383 → -25° to +25°
}

