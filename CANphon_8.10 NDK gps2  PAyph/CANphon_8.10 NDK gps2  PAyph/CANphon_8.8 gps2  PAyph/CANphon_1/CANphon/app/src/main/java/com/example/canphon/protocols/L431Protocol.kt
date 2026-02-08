package com.example.canphon.protocols
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

/**
 * L431 PUI CAN Protocol
 * 
 * Custom protocol for controlling STM32L431 GPIO pins via CAN bus.
 * Uses unique CAN IDs to differentiate from servo communication.
 * 
 * CAN IDs:
 * - TX (Android → L431): 0x100
 * - RX (L431 → Android): 0x101
 * 
 * Commands:
 * - 0x01: ON  - PA9=HIGH, PA10=HIGH
 * - 0x02: OFF - PA5=HIGH, PA9=LOW, PA10=LOW
 * - 0x03: Heartbeat - Toggle PB6
 */
object L431Protocol {
    
    private const val TAG = "L431Protocol"
    
    // ============ CAN IDs (Unique for L431, away from servo range) ============
    const val L431_TX_ID = 0x100   // Android → L431
    const val L431_RX_ID = 0x101   // L431 → Android (Acknowledgment)
    
    // ============ Commands ============
    const val CMD_POWER_ON: Byte = 0x01     // Turn ON: PA9=HIGH, PA10=HIGH
    const val CMD_POWER_OFF: Byte = 0x02    // Turn OFF: PA5=HIGH, PA9=LOW, PA10=LOW  
    const val CMD_HEARTBEAT: Byte = 0x03    // Toggle PB6 (Servo feedback indicator)
    
    // ============ Acknowledgment responses ============
    const val ACK_POWER_ON: Byte = 0xAA.toByte()
    const val ACK_POWER_OFF: Byte = 0xBB.toByte()
    const val ACK_HEARTBEAT: Byte = 0xCC.toByte()
    
    /**
     * Create a Power ON command frame
     * Activates PA9 and PA10 on STM32
     */
    fun createPowerOnCommand(): CANFrame {
        return CANFrame(
            L431_TX_ID,
            byteArrayOf(
                CMD_POWER_ON,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
        )
    }
    
    /**
     * Create a Power OFF command frame
     * Activates PA5 and deactivates PA9, PA10 on STM32
     */
    fun createPowerOffCommand(): CANFrame {
        return CANFrame(
            L431_TX_ID,
            byteArrayOf(
                CMD_POWER_OFF,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
        )
    }
    
    /**
     * Create a Heartbeat command frame
     * Toggles PB6 on STM32 (used to indicate servo feedback received)
     */
    fun createHeartbeatCommand(): CANFrame {
        return CANFrame(
            L431_TX_ID,
            byteArrayOf(
                CMD_HEARTBEAT,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
        )
    }
    
    /**
     * Parse acknowledgment from L431
     * @param frame Received CAN frame
     * @return Command type that was acknowledged, or null if not an L431 response
     */
    fun parseAcknowledgment(frame: CANFrame): Byte? {
        if (frame.id != L431_RX_ID) return null
        if (frame.data.isEmpty()) return null
        
        return when (frame.data[0]) {
            ACK_POWER_ON -> CMD_POWER_ON
            ACK_POWER_OFF -> CMD_POWER_OFF
            ACK_HEARTBEAT -> CMD_HEARTBEAT
            else -> null
        }
    }
}

