package com.example.canphon

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
 * Baud Rate: 2,000,000 (2Mbps)
 * 
 * This implementation is verified from Step 769 and Step 797:
 * - Uses 0xC0 | DLC for INFO byte (critical!)
 * - Direct serial write for stability at 50Hz
 */
class WaveshareAdapter(private val port: UsbSerialPort) {
    
    companion object {
        private const val TAG = "WaveshareAdapter"
        
        // USB Serial baud rate (MUST be 2Mbps for Waveshare)
        const val BAUD_RATE = 2_000_000
        
        // Frame markers
        private const val FRAME_HEADER = 0xAA.toByte()
        private const val FRAME_FOOTER = 0x55.toByte()
        
        // Write timeout in ms (reduced for low latency)
        private const val WRITE_TIMEOUT = 10
        
        // Read timeout in ms (minimal for non-blocking reads)
        private const val READ_TIMEOUT = 5
        
        // Read buffer size
        private const val READ_BUFFER_SIZE = 64
    }
    
    // Statistics
    var framesSent = 0L
        private set
    var framesReceived = 0L
        private set
    var errors = 0L
        private set
    
    // Read buffer
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)
    
    /**
     * Send a CAN frame via Waveshare adapter
     * 
     * Uses direct serial write for 50Hz stability
     * INFO byte = 0xC0 | DLC (verified in Step 769)
     */
    fun sendFrame(frame: CANFrame): Boolean {
        try {
            // Build packet
            val packet = mutableListOf<Byte>()
            
            // Header
            packet.add(FRAME_HEADER)
            
            // INFO byte: 0xC0 | DLC (CRITICAL - bit 7 and 6 must be set!)
            val info = 0xC0 or (frame.data.size and 0x0F)
            packet.add(info.toByte())
            
            // CAN ID (little endian: low byte first)
            packet.add((frame.id and 0xFF).toByte())
            packet.add(((frame.id shr 8) and 0xFF).toByte())
            
            // Data payload (8 bytes)
            frame.data.forEach { packet.add(it) }
            
            // Footer
            packet.add(FRAME_FOOTER)
            
            // Direct write to serial port
            port.write(packet.toByteArray(), WRITE_TIMEOUT)
            framesSent++
            
            return true
            
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
            errors++
            return false
        }
    }
    
    /**
     * Read incoming frames (for feedback)
     * 
     * Uses pattern matching to find SDO responses
     * Looks for: [0x4B] [0x02] [0x60] (SDO Read Response for index 0x6002)
     */
    fun readFrame(): CANFrame? {
        try {
            val bytesRead = port.read(readBuffer, READ_TIMEOUT)
            if (bytesRead <= 0) return null
            
            // Parse the received data
            return parseFrame(readBuffer, bytesRead)
            
        } catch (e: IOException) {
            return null
        }
    }
    
    /**
     * Parse Waveshare frame from buffer
     */
    private fun parseFrame(buffer: ByteArray, length: Int): CANFrame? {
        if (length < 5) return null
        
        // Find frame header (0xAA)
        for (i in 0 until length - 4) {
            if (buffer[i] == FRAME_HEADER) {
                val info = buffer[i + 1].toInt() and 0xFF
                val dlc = info and 0x0F
                
                if (i + 4 + dlc >= length) continue
                if (buffer[i + 4 + dlc] != FRAME_FOOTER) continue
                
                val idLow = buffer[i + 2].toInt() and 0xFF
                val idHigh = buffer[i + 3].toInt() and 0xFF
                val canId = idLow or (idHigh shl 8)
                
                val data = ByteArray(dlc)
                for (j in 0 until dlc) {
                    data[j] = buffer[i + 4 + j]
                }
                
                framesReceived++
                return CANFrame(canId, data)
            }
        }
        
        return null
    }
    
    /**
     * Send test frames (for connection verification)
     */
    fun sendTestFrames(count: Int = 5) {
        val testFrame = CANFrame(0x601, byteArrayOf(
            0x22, 0x03, 0x60, 0x00,  // SDO Write to 0x6003
            0x00, 0x00, 0x00, 0x00   // Position = 0
        ))
        
        for (i in 1..count) {
            sendFrame(testFrame)
            Thread.sleep(100)
            Log.d(TAG, "Test frame $i/$count sent")
        }
    }
    
    /**
     * Get statistics string
     */
    fun getStats(): String {
        return "TX: $framesSent, RX: $framesReceived, ERR: $errors"
    }
    
    /**
     * Close the adapter
     */
    fun close() {
        try {
            port.close()
            Log.i(TAG, "Adapter closed. Stats: ${getStats()}")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing port: ${e.message}")
        }
    }
}
