package com.gps.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * محلل بروتوكول KCA لاستقبال بيانات GPS
 * KCA Protocol Parser for GPS data reception
 * 
 * Based on the original C++ implementation in GPS.cpp
 */
class KcaParser(
    private val onNavDataReceived: (NavData) -> Unit
) {
    companion object {
        const val KCA_SYNC1: Byte = 0x81.toByte()
        const val KCA_SYNC2: Byte = 0x7E
        const val KCA_MAX_PAYLOAD = 160
        const val FIXED_DELAY = 14236
        
        // CRC-16 CCITT polynomial: X^16 + X^12 + X^5 + 1
        private const val CRC_POLYNOMIAL = 0x1021
    }
    
    private enum class ParserState {
        UNINIT,
        GOT_SYNC1,
        GOT_SYNC2,
        GOT_TYPE,
        GOT_PAYLOAD
    }
    
    private var state = ParserState.UNINIT
    private val messageBuffer = ByteArray(KCA_MAX_PAYLOAD)
    private var bufferIndex = 0
    private var crcIndex = 0
    private var calculatedCrc = 0
    
    // Statistics
    var frameErrorCount = 0L
        private set
    var messageCount = 0L
        private set
    var byteCount = 0L
        private set
    var gpsConnectCount = 0L
        private set
    
    // GPS flags
    private var gFlag = 0L
    
    /**
     * تحليل بايت واحد من تيار البيانات
     * Parse a single byte from the data stream
     */
    fun parseCharacter(c: Byte) {
        byteCount++
        
        when (state) {
            ParserState.UNINIT -> {
                if (c == KCA_SYNC1) {
                    state = ParserState.GOT_SYNC1
                }
            }
            
            ParserState.GOT_SYNC1 -> {
                if (c == KCA_SYNC2) {
                    state = ParserState.GOT_SYNC2
                } else {
                    handleError()
                }
            }
            
            ParserState.GOT_SYNC2 -> {
                bufferIndex = 0
                messageBuffer[bufferIndex++] = c
                state = ParserState.GOT_TYPE
            }
            
            ParserState.GOT_TYPE -> {
                messageBuffer[bufferIndex++] = c
                if (bufferIndex >= KCA_MAX_PAYLOAD) {
                    calculatedCrc = calculateCrc16(messageBuffer, KCA_MAX_PAYLOAD)
                    crcIndex = 0
                    state = ParserState.GOT_PAYLOAD
                }
            }
            
            ParserState.GOT_PAYLOAD -> {
                val expectedByte = if (crcIndex == 0) {
                    (calculatedCrc and 0xFF).toByte()
                } else {
                    ((calculatedCrc shr 8) and 0xFF).toByte()
                }
                
                if (c == expectedByte) {
                    if (crcIndex == 0) {
                        crcIndex++
                    } else {
                        // CRC complete and valid
                        processMessage()
                        gpsConnectCount++
                        restart()
                    }
                } else {
                    handleError()
                }
            }
        }
    }
    
    /**
     * تحليل مصفوفة من البايتات
     * Parse an array of bytes
     */
    fun parseBytes(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        for (i in offset until minOf(offset + length, data.size)) {
            parseCharacter(data[i])
        }
    }
    
    /**
     * حساب CRC-16 (CCITT)
     */
    private fun calculateCrc16(data: ByteArray, count: Int): Int {
        var crc = 0
        for (i in 0 until count) {
            var temp = (data[i].toInt() and 0xFF) shl 8
            crc = crc xor temp
            for (j in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor CRC_POLYNOMIAL
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF
    }
    
    /**
     * معالجة رسالة مكتملة
     */
    private fun processMessage() {
        messageCount++
        
        val navData = parseNavData(messageBuffer)
        
        // Update gFlag based on state
        if (navData.state == 0.toByte()) {
            gFlag = 0
        } else if (navData.state >= 0x01) {
            gFlag++
        }
        
        // Check if we have enough data (like original: gFlag > 75)
        if (navData.state >= 0x01 && gFlag > 75) {
            // Count used satellites
            val gpsCount = countBits(navData.usedSatellites and navData.visibleSatellites)
            val glonassCount = countBits(navData.glonassUsedSat and navData.glonassVisibleSat)
            
            val updatedNavData = navData.copy(
                usedSatCount = gpsCount,
                glonassUsedSatCount = glonassCount
            )
            
            // Check satellite requirements (like original)
            if (updatedNavData.totalUsedSatellites > 4 && 
                (gpsCount > 3 || glonassCount > 3)) {
                onNavDataReceived(updatedNavData)
            }
        }
    }
    
    /**
     * تحليل البايتات إلى NavData
     */
    private fun parseNavData(buffer: ByteArray): NavData {
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        
        return NavData(
            msgType = bb.get(),
            state = bb.get(),
            temperature = bb.get(),
            utcTime = bb.getInt().toLong() and 0xFFFFFFFFL,
            visibleSatellites = bb.getInt().toLong() and 0xFFFFFFFFL,
            usedSatellites = bb.getInt().toLong() and 0xFFFFFFFFL,
            glonassVisibleSat = bb.getInt().toLong() and 0xFFFFFFFFL,
            glonassUsedSat = bb.getInt().toLong() and 0xFFFFFFFFL,
            x = bb.getFloat(),
            xProp = bb.getFloat(),
            y = bb.getFloat(),
            yProp = bb.getFloat(),
            z = bb.getFloat(),
            zProp = bb.getFloat(),
            latitude = bb.getFloat(),
            latProp = bb.getFloat(),
            longitude = bb.getFloat(),
            lonProp = bb.getFloat(),
            altitude = bb.getFloat(),
            altProp = bb.getFloat(),
            vx = bb.getFloat(),
            vxProp = bb.getFloat(),
            vy = bb.getFloat(),
            vyProp = bb.getFloat(),
            vz = bb.getFloat(),
            vzProp = bb.getFloat(),
            ax = bb.getFloat(),
            ay = bb.getFloat(),
            az = bb.getFloat(),
            snr = ByteArray(12).also { bb.get(it) },
            glonassSnr = ByteArray(12).also { bb.get(it) },
            weekNumber = bb.getShort().toInt() and 0xFFFF,
            utcOffset = bb.getShort().toInt() and 0xFFFF,
            localTime = bb.getInt().toLong() and 0xFFFFFFFFL,
            packDelay = bb.getInt(),
            gdop = bb.get(),
            pdop = bb.get(),
            hdop = bb.get(),
            vdop = bb.get(),
            tdop = bb.get()
        )
    }
    
    /**
     * عد البتات المفعلة في قيمة
     */
    private fun countBits(value: Long): Int {
        var count = 0
        var v = value
        while (v != 0L) {
            count += (v and 1).toInt()
            v = v shr 1
        }
        return count
    }
    
    private fun handleError() {
        frameErrorCount++
        restart()
    }
    
    private fun restart() {
        state = ParserState.UNINIT
        byteCount = 0
    }
    
    /**
     * إعادة تعيين المحلل
     */
    fun reset() {
        state = ParserState.UNINIT
        bufferIndex = 0
        crcIndex = 0
        gFlag = 0
        frameErrorCount = 0
        messageCount = 0
        byteCount = 0
        gpsConnectCount = 0
    }
}
