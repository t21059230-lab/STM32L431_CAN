package com.example.canphon.protocols
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.native_sensors.NativeCore
import com.example.canphon.data.*

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DEPRECATED - تم نقل هذا الكود إلى C++
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * المنطق الحقيقي الآن في: servo_protocol.cpp
 * استخدم NativeCore.servo* بدلاً من هذه الفئة
 * 
 * هذا الملف wrapper فقط للتوافقية مع الكود القديم
 */



@Deprecated("Use NativeCore.servo* functions instead")
class UnifiedProtocol(
    val servoId: Int,
    val axisName: String
) {
    companion object {
        private const val TAG = "UnifiedProtocol"
        const val BAUD_RATE = 115200
        
        fun createRoll() = UnifiedProtocol(1, "Roll")
        fun createPitch() = UnifiedProtocol(2, "Pitch")
        fun createYaw() = UnifiedProtocol(3, "Yaw")
        fun createExtra() = UnifiedProtocol(4, "Extra")
        
        val SERIAL_VENDORS = listOf(
            0x0403,  // FTDI
            0x2341,  // Arduino
            0x2A03,  // Arduino.org
            0x0483,  // STMicroelectronics
            0x10C4   // Silicon Labs CP210x
        )
        
        const val WAVESHARE_VID = 0x1A86
    }

    fun formatCommand(angleDegrees: Float): ByteArray {
        return NativeCore.servoFormatCommand(servoId, angleDegrees)
    }

    fun formatPositionCommand(position: Int): ByteArray {
        val angle = NativeCore.servoPositionToAngle(position)
        return NativeCore.servoFormatCommand(servoId, angle)
    }
    
    fun formatFeedbackRequest(): ByteArray {
        return NativeCore.servoFormatFeedbackRequest(servoId)
    }
}

