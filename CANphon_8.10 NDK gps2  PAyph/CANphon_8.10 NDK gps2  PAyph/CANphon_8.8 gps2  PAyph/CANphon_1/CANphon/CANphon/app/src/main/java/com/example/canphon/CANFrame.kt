package com.example.canphon

/**
 * CAN Frame data structure
 * @param id CAN ID (e.g., 0x601 for Servo 1)
 * @param data 8-byte payload
 */
data class CANFrame(
    val id: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CANFrame
        return id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return 31 * id + data.contentHashCode()
    }
}
