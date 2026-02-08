package com.example.canphon.protocols
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

/**
 * CANOpen SDO Protocol for KST X20 / XQPOWER Servos
 * 
 * Based on verified protocols from the original SERVOSTM32 project.
 * 
 * Protocol: CANOpen SDO Write to index 0x6003 (Position Target)
 * Resolution: 0.1° per unit (e.g., 500 = 50.0°)
 * CAN Baud Rate: 500 Kbps
 */
object CANServoProtocol {
    
    // ============ Node IDs (Production Seeker Mapping) ============
    // These are the actual Node IDs used on the CAN bus
    const val SERVO_1 = 0x01  // Roll - Front Right
    const val SERVO_2 = 0x02  // Pitch - Front Left
    const val SERVO_3 = 0x03  // Yaw - Back Right
    const val SERVO_4 = 0x04  // Aux - Back Left
    
    // Alias names for clarity
    const val SERVO_ROLL = SERVO_1
    const val SERVO_PITCH = SERVO_2
    const val SERVO_YAW = SERVO_3
    const val SERVO_AUX = SERVO_4
    
    // ============ CAN ID Offsets (CANOpen Standard) ============
    const val TX_OFFSET = 0x600  // Master -> Servo (Control)
    const val RX_OFFSET = 0x580  // Servo -> Master (Feedback)
    
    // ============ SDO Commands ============
    const val SDO_WRITE = 0x22.toByte()        // Write command
    const val SDO_READ = 0x40.toByte()         // Read command
    const val SDO_READ_RESPONSE = 0x4B.toByte() // Read response
    
    // ============ Object Dictionary Indices ============
    const val INDEX_POSITION_TARGET_LOW = 0x03.toByte()   // 0x6003 - Position Target
    const val INDEX_POSITION_TARGET_HIGH = 0x60.toByte()
    const val INDEX_POSITION_ACTUAL_LOW = 0x02.toByte()   // 0x6002 - Position Actual
    const val INDEX_POSITION_ACTUAL_HIGH = 0x60.toByte()
    
    // ============ NMT Commands (for Auto Reporting) ============
    const val NMT_BASE_ID = 0x000  // NMT broadcast
    const val INDEX_REPORT_INTERVAL_LOW = 0x00.toByte()  // 0x2200
    const val INDEX_REPORT_INTERVAL_HIGH = 0x22.toByte()
    
    // ============ Angle Limits ============
    const val MIN_ANGLE = -25f  // Configurable limit
    const val MAX_ANGLE = 25f   // Configurable limit
    const val RESOLUTION = 0.1f // 0.1° per unit
    
    /**
     * أمر بدء الإرسال التلقائي للـ Feedback
     * يجب إرساله عند الاتصال لتفعيل الـ Feedback
     * 
     * @param nodeId Node ID of the servo
     */
    fun createStartReportingCommand(nodeId: Int): CANFrame {
        return CANFrame(
            NMT_BASE_ID + nodeId,
            byteArrayOf(0x01, 0x00)
        )
    }
    
    /**
     * ضبط فترة إرسال الـ Feedback التلقائي
     * 
     * @param nodeId Node ID of the servo
     * @param intervalMs الفترة بالميلي ثانية (10-255)
     */
    fun createSetReportIntervalCommand(nodeId: Int, intervalMs: Int): CANFrame {
        val canId = TX_OFFSET + nodeId
        val clampedInterval = intervalMs.coerceIn(10, 255)
        return CANFrame(
            canId,
            byteArrayOf(
                SDO_WRITE,
                INDEX_REPORT_INTERVAL_LOW,
                INDEX_REPORT_INTERVAL_HIGH,
                0x00.toByte(),
                clampedInterval.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )
        )
    }

    /**
     * Creates a CAN position command for a servo (SDO Write to 0x6003)
     * 
     * Frame Format:
     * [0x22] [0x03] [0x60] [0x00] [Pos_Low] [Pos_ML] [Pos_MH] [Pos_High]
     * 
     * STM32 Bridge Conversion: pos = (canValue * 4) + 8191
     * So we send: canValue = (targetPos - 8191) / 4
     * 
     * Angle ±25° maps to position 0-16383 (14-bit servo range)
     * 
     * @param nodeId Servo Node ID (0x01-0x04)
     * @param angleDegrees Target angle in degrees
     * @return CANFrame ready to send via Waveshare adapter to STM32
     */
    fun createPositionCommand(nodeId: Int, angleDegrees: Float): CANFrame {
        val canId = TX_OFFSET + nodeId
        val clampedAngle = angleDegrees.coerceIn(MIN_ANGLE, MAX_ANGLE)
        
        // Convert angle to 14-bit position (0-16383)
        // -25° -> 0, 0° -> 8191, +25° -> 16383
        val targetPos = ((clampedAngle + 25f) / 50f * 16383f).toInt().coerceIn(0, 16383)
        
        // Convert to CAN value (STM32 will do: pos = canValue*4 + 8191)
        // So: canValue = (targetPos - 8191) / 4
        val canValue = (targetPos - 8191) / 4
        
        val data = byteArrayOf(
            SDO_WRITE,                                  // SDO Write command
            INDEX_POSITION_TARGET_LOW,                  // Index low byte (0x03)
            INDEX_POSITION_TARGET_HIGH,                 // Index high byte (0x60)
            0x00.toByte(),                              // Subindex
            (canValue and 0xFF).toByte(),               // Value byte 0 (LSB)
            ((canValue shr 8) and 0xFF).toByte(),       // Value byte 1
            ((canValue shr 16) and 0xFF).toByte(),      // Value byte 2
            ((canValue shr 24) and 0xFF).toByte()       // Value byte 3 (MSB)
        )
        return CANFrame(canId, data)
    }
    
    /**
     * Creates a position read request (SDO Read from 0x6002)
     * 
     * @param nodeId Servo Node ID
     * @return CANFrame for position feedback request
     */
    fun createPositionReadRequest(nodeId: Int): CANFrame {
        val canId = TX_OFFSET + nodeId
        val data = byteArrayOf(
            SDO_READ,                               // SDO Read command
            INDEX_POSITION_ACTUAL_LOW,              // Index low byte (0x02)
            INDEX_POSITION_ACTUAL_HIGH,             // Index high byte (0x60)
            0x00.toByte(),                          // Subindex
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )
        return CANFrame(canId, data)
    }
    
    /**
     * Parse position feedback from STM32 Bridge
     * 
     * STM32 Feedback Format (CAN ID 0x581-0x584):
     * [Pos_Low] [Pos_High] [Debug0] [Debug1] [0] [0] [0] [0]
     * 
     * Position is 14-bit (0-16383) where:
     * 0 = -25°, 8191 = 0°, 16383 = +25°
     * 
     * @param data 8-byte CAN feedback payload
     * @return Position in degrees, or null if invalid
     */
    fun parsePositionFeedback(data: ByteArray): Float? {
        if (data.size < 2) return null
        
        // Extract 14-bit position from first 2 bytes
        val posLow = data[0].toInt() and 0xFF
        val posHigh = data[1].toInt() and 0xFF
        val rawPosition = posLow or (posHigh shl 8)
        
        // Convert 14-bit position (0-16383) to angle (-25° to +25°)
        // 0 -> -25°, 8191 -> 0°, 16383 -> +25°
        val angle = (rawPosition.toFloat() / 16383f * 50f) - 25f
        
        return angle.coerceIn(MIN_ANGLE, MAX_ANGLE)
    }
    
    /**
     * Get TX CAN ID for a servo
     */
    fun getTxId(nodeId: Int): Int = TX_OFFSET + nodeId
    
    /**
     * Get RX CAN ID for a servo (feedback)
     */
    fun getRxId(nodeId: Int): Int = RX_OFFSET + nodeId
    
    /**
     * Maps axis type to servo Node ID
     */
    fun getNodeId(axis: Axis): Int {
        return when (axis) {
            Axis.ROLL -> SERVO_ROLL
            Axis.PITCH -> SERVO_PITCH
            Axis.YAW -> SERVO_YAW
            Axis.AUX -> SERVO_AUX
        }
    }
    
    enum class Axis {
        ROLL, PITCH, YAW, AUX
    }
}

