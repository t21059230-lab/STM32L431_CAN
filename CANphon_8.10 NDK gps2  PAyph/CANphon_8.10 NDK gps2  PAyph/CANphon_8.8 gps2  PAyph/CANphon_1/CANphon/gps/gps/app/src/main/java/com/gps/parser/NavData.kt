package com.gps.parser

/**
 * بيانات الملاحة المستلمة من مستقبل GPS عبر بروتوكول KCA
 * Navigation data received from GPS receiver via KCA protocol
 */
data class NavData(
    // Message info
    val msgType: Byte = 0,
    val state: Byte = 0,
    val temperature: Byte = 0,
    
    // Time
    val utcTime: Long = 0,
    val localTime: Long = 0,
    val weekNumber: Int = 0,
    val utcOffset: Int = 0,
    
    // Satellites - GPS
    val visibleSatellites: Long = 0,
    val usedSatellites: Long = 0,
    val usedSatCount: Int = 0,
    val snr: ByteArray = ByteArray(12),
    
    // Satellites - GLONASS
    val glonassVisibleSat: Long = 0,
    val glonassUsedSat: Long = 0,
    val glonassUsedSatCount: Int = 0,
    val glonassSnr: ByteArray = ByteArray(12),
    
    // Position - ECEF (Earth-Centered Earth-Fixed)
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    
    // Position - Geographic
    val latitude: Float = 0f,      // degrees
    val longitude: Float = 0f,     // degrees
    val altitude: Float = 0f,      // meters
    
    // Velocity
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    
    // Acceleration
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    
    // Propagated values (predicted)
    val xProp: Float = 0f,
    val yProp: Float = 0f,
    val zProp: Float = 0f,
    val latProp: Float = 0f,
    val lonProp: Float = 0f,
    val altProp: Float = 0f,
    val vxProp: Float = 0f,
    val vyProp: Float = 0f,
    val vzProp: Float = 0f,
    
    // DOP (Dilution of Precision)
    val gdop: Byte = 0,
    val pdop: Byte = 0,
    val hdop: Byte = 0,
    val vdop: Byte = 0,
    val tdop: Byte = 0,
    
    // Delay
    val packDelay: Int = 0
) {
    /**
     * إجمالي الأقمار الصناعية المستخدمة (GPS + GLONASS)
     */
    val totalUsedSatellites: Int
        get() = usedSatCount + glonassUsedSatCount
    
    /**
     * هل البيانات صالحة للاستخدام؟
     */
    val isValid: Boolean
        get() = state >= 0x01 && totalUsedSatellites >= 4
    
    /**
     * خط العرض بالراديان
     */
    val latitudeRad: Double
        get() = Math.toRadians(latitude.toDouble())
    
    /**
     * خط الطول بالراديان
     */
    val longitudeRad: Double
        get() = Math.toRadians(longitude.toDouble())
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavData) return false
        return utcTime == other.utcTime && 
               latitude == other.latitude && 
               longitude == other.longitude
    }
    
    override fun hashCode(): Int {
        return utcTime.hashCode() + latitude.hashCode() + longitude.hashCode()
    }
}

/**
 * حالة استقبال GPS
 */
enum class GpsState(val code: Byte) {
    NO_FIX(0x00),
    FIX_2D(0x01),
    FIX_3D(0x02);
    
    companion object {
        fun fromCode(code: Byte): GpsState = 
            entries.find { it.code == code } ?: NO_FIX
    }
}

/**
 * نتيجة تحليل GPS
 */
data class GpsResult(
    val navData: NavData,
    val latitudeRad: Double,    // Latitude in radians
    val longitudeRad: Double,   // Longitude in radians
    val altitudeM: Double,      // Altitude in meters
    val velocityNorth: Double,  // Velocity North (m/s)
    val velocityEast: Double,   // Velocity East (m/s)
    val velocityDown: Double,   // Velocity Down (m/s)
    val positionAccuracy: Double, // Estimated position accuracy (meters)
    val parseDelayMs: Double,   // Parse delay in milliseconds
    val isValid: Boolean        // Is data valid for navigation
)
