package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import kotlin.math.*

/**
 * محلل وتحويل بيانات GPS
 * GPS Data Analyzer and Coordinate Transformer
 * 
 * Based on the AnalyzeGPS() function from GPS.cpp
 */
class GpsAnalyzer {
    
    companion object {
        const val FIXED_DELAY = 14236
        
        // WGS84 Ellipsoid constants
        private const val EARTH_RADIUS = 6378137.0          // Semi-major axis (meters)
        private const val FLATTENING = 1.0 / 298.257223563  // Flattening
        private const val ECCENTRICITY_SQ = 2 * FLATTENING - FLATTENING * FLATTENING
    }
    
    // State tracking
    private var gFlag = 0L
    private var firstTime = true
    
    // Reference position for local frame
    var refLatitude = 0.0
        private set
    var refLongitude = 0.0
        private set
    var refAltitude = 0.0
        private set
    private var referenceSet = false
    
    // Statistics
    var gpsUpdateCount = 0L
        private set
    
    /**
     * تحليل بيانات GPS وتحويلها
     * Analyze GPS data and transform coordinates
     */
    fun analyze(navData: NavData, irqCount: Long = 0): GpsResult? {
        // Get latitude/longitude in radians
        val latRad = Math.toRadians(navData.latitude.toDouble())
        val lonRad = Math.toRadians(navData.longitude.toDouble())
        val alt = navData.altitude.toDouble()
        
        // Set reference on first valid fix
        if (!referenceSet && navData.isValid) {
            setReference(latRad, lonRad, alt)
        }
        
        // Calculate rotation matrix from ECEF to NED
        val cen = rotationEcefToNed(latRad, lonRad)
        
        // ECEF position
        val ecefX = navData.x.toDouble()
        val ecefY = navData.y.toDouble()
        val ecefZ = navData.z.toDouble()
        
        // ECEF velocity (propagated values)
        val ecefVx = navData.vxProp.toDouble()
        val ecefVy = navData.vyProp.toDouble()
        val ecefVz = navData.vzProp.toDouble()
        
        // Transform velocity to NED frame
        val vNorth = cen[0][0] * ecefVx + cen[0][1] * ecefVy + cen[0][2] * ecefVz
        val vEast  = cen[1][0] * ecefVx + cen[1][1] * ecefVy + cen[1][2] * ecefVz
        val vDown  = cen[2][0] * ecefVx + cen[2][1] * ecefVy + cen[2][2] * ecefVz
        
        // Calculate parse delay
        val tagGPS = irqCount - 2 * ((navData.packDelay + FIXED_DELAY) * 0.001)
        val parseDelayMs = (irqCount - tagGPS) * 0.5
        
        // Calculate position accuracy
        val positionAccuracy = calculatePositionAccuracy(navData)
        
        // Determine validity
        val isValid = checkValidity(navData, positionAccuracy)
        
        if (isValid) {
            gpsUpdateCount++
        }
        
        return GpsResult(
            navData = navData,
            latitudeRad = latRad,
            longitudeRad = lonRad,
            altitudeM = alt,
            velocityNorth = vNorth,
            velocityEast = vEast,
            velocityDown = vDown,
            positionAccuracy = positionAccuracy,
            parseDelayMs = parseDelayMs,
            isValid = isValid
        )
    }
    
    /**
     * تعيين الموقع المرجعي
     */
    fun setReference(latRad: Double, lonRad: Double, alt: Double) {
        refLatitude = latRad
        refLongitude = lonRad
        refAltitude = alt
        referenceSet = true
    }
    
    /**
     * مصفوفة التدوير من ECEF إلى NED
     * Rotation matrix from ECEF to NED (North-East-Down)
     */
    private fun rotationEcefToNed(lat: Double, lon: Double): Array<DoubleArray> {
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)
        
        return arrayOf(
            doubleArrayOf(-sinLat * cosLon, -sinLat * sinLon,  cosLat),
            doubleArrayOf(-sinLon,           cosLon,           0.0),
            doubleArrayOf(-cosLat * cosLon, -cosLat * sinLon, -sinLat)
        )
    }
    
    /**
     * حساب دقة الموقع بناءً على DOP و SNR
     * Calculate position accuracy based on DOP and SNR values
     */
    private fun calculatePositionAccuracy(navData: NavData): Double {
        if (navData.totalUsedSatellites == 0) {
            return Double.MAX_VALUE
        }
        
        // Find max SNR from used satellites
        val maxSnr = findMaxSnr(navData)
        
        // Calculate SNR factor (from dcomp equivalent)
        val snrFactor = decompressSnr(maxSnr)
        
        // Calculate sigma based on GDOP and SNR
        // Formula from original: sigmaPosi = dcomp(GDOP) * (2 + 0.25 * 10^CalSNmax)
        val calSnMax = ((maxSnr - 10) * -0.1) + 4
        val gdopValue = decompressSnr(navData.gdop.toInt() and 0xFF)
        val sigmaPosi = gdopValue * (2.0 + 0.25 * 10.0.pow(calSnMax))
        
        return sigmaPosi * sigmaPosi
    }
    
    /**
     * البحث عن أعلى SNR
     */
    private fun findMaxSnr(navData: NavData): Int {
        var maxGps = 0
        var maxGlonass = 0
        
        for (i in 0 until 12) {
            val gpsSnr = navData.snr[i].toInt() and 0xFF
            val glonassSnr = navData.glonassSnr[i].toInt() and 0xFF
            if (gpsSnr > maxGps) maxGps = gpsSnr
            if (glonassSnr > maxGlonass) maxGlonass = glonassSnr
        }
        
        return when {
            navData.usedSatCount == 0 -> maxGlonass
            navData.glonassUsedSatCount == 0 -> maxGps
            navData.usedSatCount >= 1 -> maxGps
            else -> maxGlonass
        }
    }
    
    /**
     * فك ضغط قيمة DOP/SNR
     * Decompress DOP/SNR value (dcomp function from C++)
     */
    private fun decompressSnr(dp: Int): Double {
        return when {
            dp < 100 -> 0.05 * dp
            dp < 150 -> (dp - 80) * 0.25
            dp < 200 -> dp - 132.5
            else -> (dp - 197.625) * 20
        }
    }
    
    /**
     * التحقق من صلاحية البيانات
     */
    private fun checkValidity(navData: NavData, sigmaPosi: Double): Boolean {
        if (navData.totalUsedSatellites == 0) {
            return false
        }
        
        // Check if sigma is within acceptable range
        if (sigmaPosi > 196.0 || sigmaPosi < 0.1) {
            return false
        }
        
        return true
    }
    
    /**
     * تحويل إحداثيات ECEF إلى LLA (Lat/Lon/Alt)
     */
    fun ecefToLla(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val p = sqrt(x * x + y * y)
        val lon = atan2(y, x)
        
        // Iterative solution for latitude
        var lat = atan2(z, p * (1 - ECCENTRICITY_SQ))
        var alt: Double
        
        for (i in 0 until 10) {
            val sinLat = sin(lat)
            val n = EARTH_RADIUS / sqrt(1 - ECCENTRICITY_SQ * sinLat * sinLat)
            alt = p / cos(lat) - n
            lat = atan2(z, p * (1 - ECCENTRICITY_SQ * n / (n + alt)))
        }
        
        val sinLat = sin(lat)
        val n = EARTH_RADIUS / sqrt(1 - ECCENTRICITY_SQ * sinLat * sinLat)
        alt = p / cos(lat) - n
        
        return Triple(lat, lon, alt)
    }
    
    /**
     * تحويل إحداثيات LLA إلى ECEF
     */
    fun llaToEcef(lat: Double, lon: Double, alt: Double): Triple<Double, Double, Double> {
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)
        
        val n = EARTH_RADIUS / sqrt(1 - ECCENTRICITY_SQ * sinLat * sinLat)
        
        val x = (n + alt) * cosLat * cosLon
        val y = (n + alt) * cosLat * sinLon
        val z = (n * (1 - ECCENTRICITY_SQ) + alt) * sinLat
        
        return Triple(x, y, z)
    }
    
    /**
     * إعادة تعيين المحلل
     */
    fun reset() {
        gFlag = 0
        firstTime = true
        referenceSet = false
        gpsUpdateCount = 0
    }
}

