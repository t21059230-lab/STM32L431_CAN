package com.example.canphon.drivers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Waveshare USB-CAN-A Adapter (Verified Implementation)
 * 
 * Binary Frame Format:
 * [0xAA] [INFO] [ID_LOW] [ID_HIGH] [DATA...8 bytes] [0x55]
 * 
 * INFO Byte: 0xC0 | DLC (e.g., 0xC8 for 8-byte payload)
 */
class WaveshareAdapter(private val port: UsbSerialPort) {

    companion object {
        const val BAUD_RATE = 2000000 // 2Mbps
        private const val TAG = "WaveshareAdapter"
        private const val FRAME_HEADER = 0xAA.toByte()
        private const val FRAME_FOOTER = 0x55.toByte()
        private const val WRITE_TIMEOUT = 50  // Increased for USB stability
        private const val READ_TIMEOUT = 10 // Increased for stability
        
        // Circular Buffer Size (2KB is enough for ~150 frames)
        private const val BUFFER_SIZE = 2048
    }
    
    // Statistics
    var framesSent = 0L
        private set
    var framesReceived = 0L
        private set
    var errors = 0L
        private set
    
    // Read buffer (Circular)
    private val buffer = ByteArray(BUFFER_SIZE)
    private var head = 0 // Write index
    private var tail = 0 // Read index
    private var count = 0 // Number of bytes in buffer
    
    // Temp buffer for USB reads
    private val paramsBuffer = ByteArray(64)
    
    /**
     * Send a CAN frame via Waveshare adapter
     */
    fun sendFrame(frame: CANFrame): Boolean {
        try {
            // Build packet
            val packet = mutableListOf<Byte>()
            
            // Header
            packet.add(FRAME_HEADER)
            
            // INFO/Type byte: 0xC0 | DLC
            val info = 0xC0 or (frame.data.size and 0x0F)
            packet.add(info.toByte())
            
            // CAN ID (little endian)
            packet.add((frame.id and 0xFF).toByte())
            packet.add(((frame.id shr 8) and 0xFF).toByte())
            
            // Data payload
            frame.data.forEach { packet.add(it) }
            
            // Footer
            packet.add(FRAME_FOOTER)
            
            // Direct write to serial port
            port.write(packet.toByteArray(), WRITE_TIMEOUT)
            framesSent++
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Send FAILED: ${e.message}")
            errors++
            return false
        }
    }
    
    /**
     * Read incoming frames (Robust Stream)
     * 
     * Reads from USB into circular buffer, then parses the next valid frame.
     * Returns NULL only if no complete frame is available after reading.
     */
    fun readFrame(): CANFrame? {
        try {
            // 1. Read from USB into temp buffer
            val len = port.read(paramsBuffer, READ_TIMEOUT)
            if (len > 0) {
                pushBytes(paramsBuffer, len)
            }
            
            // 2. Try to parse a frame from the circular buffer
            return parseNextFrame()
            
        } catch (e: IOException) {
            return null // Normal timeout or error
        }
    }
    
    /**
     * Push bytes into circular buffer
     */
    private fun pushBytes(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            if (count < BUFFER_SIZE) {
                buffer[head] = data[i]
                head = (head + 1) % BUFFER_SIZE
                count++
            } else {
                // Buffer overflow - discard oldest byte (advance tail)
                tail = (tail + 1) % BUFFER_SIZE
                // Overwrite head
                buffer[head] = data[i]
                head = (head + 1) % BUFFER_SIZE
                // Count stays max
            }
        }
    }
    
    /**
     * Peek byte at offset from tail
     */
    private fun peek(offset: Int): Byte {
        return buffer[(tail + offset) % BUFFER_SIZE]
    }
    
    /**
     * Advance tail by n bytes
     */
    private fun consume(n: Int) {
        tail = (tail + n) % BUFFER_SIZE
        count -= n
    }
    
    /**
     * Parse next valid CAN frame from buffer
     */
    private fun parseNextFrame(): CANFrame? {
        // Need at least 5 bytes for minimal frame (Header+Info+ID+ID+Footer)
        while (count >= 5) {
            // Check for Header
            if (peek(0) != FRAME_HEADER) {
                consume(1) // Skip garbage
                continue
            }
            
            // We have a header. Check INFO byte for length
            val info = peek(1).toInt() and 0xFF
            val dlc = info and 0x0F
            
            // Total frame length: Header(1) + Info(1) + ID(2) + Data(DLC) + Footer(1)
            val frameLen = 1 + 1 + 2 + dlc + 1
            
            if (count < frameLen) {
                // Wait for more data
                return null
            }
            
            // Check Footer
            if (peek(frameLen - 1) != FRAME_FOOTER) {
                // Invalid frame structure, skip header and retry
                consume(1)
                continue
            }
            
            // Valid Frame! Extract data
            val idLow = peek(2).toInt() and 0xFF
            val idHigh = peek(3).toInt() and 0xFF
            val canId = idLow or (idHigh shl 8)
            
            val data = ByteArray(dlc)
            for (i in 0 until dlc) {
                data[i] = peek(4 + i)
            }
            
            // Consume this frame
            consume(frameLen)
            framesReceived++
            
            return CANFrame(canId, data)
        }
        return null
    }
    
    /**
     * Send test frames
     */
    fun sendTestFrames(count: Int = 5) {
        val testFrame = CANFrame(0x601, byteArrayOf(
            0x22, 0x03, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00
        ))
        for (i in 1..count) {
            sendFrame(testFrame)
            try { Thread.sleep(100) } catch (_: Exception) {}
        }
    }
    
    fun getStats(): String {
        return "TX: $framesSent, RX: $framesReceived, ERR: $errors"
    }

    fun close() {
        try {
            port.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing port: ${e.message}")
        }
    }
}

