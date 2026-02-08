package com.example.canphon.protocols
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.util.Log

/**
 * فئة لتحليل Feedback القادم من السيرفوهات Serial
 * متوافق مع Rudder_Parse_Character في CTRL.cpp
 * 
 * صيغة الـ Feedback (7 bytes):
 * [Byte0] [Byte1] [Byte2] [Byte3] [Byte4] [Byte5] [Checksum]
 * 
 * - ID = (Byte0 & 0x03) * 128 + (Byte1 & 0x7F)
 * - OpCode = Byte0 & 0xFC
 *   - 0x88: Position feedback
 *   - 0x90: Temperature
 *   - 0x8C: Current (Ampere)
 */
class FeedbackParser {
    companion object {
        private const val TAG = "FeedbackParser"
        
        // OpCodes
        private const val OPCODE_POSITION: Int = 0x88
        private const val OPCODE_TEMPERATURE: Int = 0x90
        private const val OPCODE_CURRENT: Int = 0x8C
        
        // Sync byte mask
        private const val SYNC_MASK: Int = 0x80
        
        // Frame length
        private const val FRAME_LENGTH = 7
    }

    // حالة الـ Parser
    private var state = ParserState.WAITING_SYNC
    private var bufferIndex = 0
    private val buffer = ByteArray(FRAME_LENGTH)
    private var checkXor: Byte = 0

    // بيانات Feedback المُحللة
    data class ServoFeedback(
        val servoId: Int,
        val position: Float,      // الموضع الفعلي (درجات)
        val temperature: Float?,  // درجة الحرارة (اختياري)
        val current: Float?,      // التيار (اختياري)
        val isReady: Boolean
    )

    // مستمع للـ Feedback
    interface FeedbackListener {
        fun onFeedbackReceived(feedback: ServoFeedback)
    }

    var listener: FeedbackListener? = null

    // حالات الـ Parser
    private enum class ParserState {
        WAITING_SYNC,
        READING_DATA,
        READING_CHECKSUM
    }

    /**
     * تحليل بايت واحد من البيانات القادمة
     */
    fun parseByte(byte: Byte) {
        val b = byte.toInt() and 0xFF
        
        when (state) {
            ParserState.WAITING_SYNC -> {
                if ((b and SYNC_MASK) == SYNC_MASK) {
                    bufferIndex = 0
                    buffer[bufferIndex++] = byte
                    checkXor = byte
                    state = ParserState.READING_DATA
                }
            }
            
            ParserState.READING_DATA -> {
                buffer[bufferIndex++] = byte
                checkXor = (checkXor.toInt() xor b).toByte()
                
                if (bufferIndex >= FRAME_LENGTH - 1) {
                    state = ParserState.READING_CHECKSUM
                }
            }
            
            ParserState.READING_CHECKSUM -> {
                val expectedChecksum = ((checkXor.toInt() and 0x7F) or 0x40).toByte()
                
                if (byte == expectedChecksum) {
                    parseMessage()
                }
                
                state = ParserState.WAITING_SYNC
                bufferIndex = 0
            }
        }
    }

    /**
     * تحليل الرسالة الكاملة
     */
    private fun parseMessage() {
        val byte0 = buffer[0].toInt() and 0xFF
        val byte1 = buffer[1].toInt() and 0xFF
        val byte2 = buffer[2].toInt() and 0xFF
        val byte3 = buffer[3].toInt() and 0xFF
        
        // استخراج ID
        val id = (byte0 and 0x03) * 128 + (byte1 and 0x7F)
        
        // استخراج OpCode
        val opcode = byte0 and 0xFC
        
        when (opcode) {
            OPCODE_POSITION -> {
                val rawPosition = (byte2 and 0x7F) * 128 + (byte3 and 0x7F)
                val angleDegrees = (rawPosition - 8191) * 0.025f
                
                val feedback = ServoFeedback(
                    servoId = id,
                    position = angleDegrees,
                    temperature = null,
                    current = null,
                    isReady = true
                )
                
                listener?.onFeedbackReceived(feedback)
                Log.d(TAG, "Serial Servo $id position: $angleDegrees°")
            }
            
            OPCODE_TEMPERATURE -> {
                val rawTemp = (byte2 and 0x7F) * 128 + (byte3 and 0x7F)
                val temperature = (rawTemp - 8191) * 0.1f
                Log.d(TAG, "Serial Servo $id temperature: $temperature°C")
            }
            
            OPCODE_CURRENT -> {
                val rawCurrent = (byte2 and 0x7F) * 128 + (byte3 and 0x7F)
                val current = (rawCurrent - 8191) * 0.05f
                Log.d(TAG, "Serial Servo $id current: ${current}A")
            }
        }
    }

    /**
     * إعادة تهيئة الـ Parser
     */
    fun reset() {
        state = ParserState.WAITING_SYNC
        bufferIndex = 0
        checkXor = 0
    }
}

