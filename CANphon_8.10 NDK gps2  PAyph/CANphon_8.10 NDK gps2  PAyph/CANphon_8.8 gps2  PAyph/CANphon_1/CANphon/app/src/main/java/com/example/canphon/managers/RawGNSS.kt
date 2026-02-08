package com.example.canphon.managers
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat

/**
 * Raw GNSS Measurements - بيانات GPS الخام
 * 
 * يوفر الوصول للبيانات الخام من أقمار GPS:
 * - Pseudorange (المسافة الخام للقمر)
 * - Carrier Phase (طور الموجة الحاملة)
 * - Doppler Shift (تغير التردد)
 * - CN0 (قوة الإشارة)
 * 
 * يتطلب Android 7.0+ (API 24)
 */
@RequiresApi(Build.VERSION_CODES.N)
class RawGNSS(private val context: Context) {

    companion object {
        private const val TAG = "RawGNSS"
        
        // Constellation types
        const val CONSTELLATION_GPS = 1
        const val CONSTELLATION_SBAS = 2
        const val CONSTELLATION_GLONASS = 3
        const val CONSTELLATION_QZSS = 4
        const val CONSTELLATION_BEIDOU = 5
        const val CONSTELLATION_GALILEO = 6
        const val CONSTELLATION_IRNSS = 7
    }

    // Location Manager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    
    // State
    private var isRunning = false
    
    // === Raw Measurements Data ===
    data class SatelliteMeasurement(
        val svid: Int,                    // Satellite Vehicle ID
        val constellation: Int,           // GPS, GLONASS, Galileo, etc.
        val constellationName: String,    // Human readable
        val cn0DbHz: Double,              // Signal strength (dB-Hz)
        val pseudorangeMeters: Double,    // Distance to satellite (meters)
        val pseudorangeRateMetersPerSec: Double,  // Doppler-derived velocity
        val carrierFrequencyHz: Double,   // Carrier frequency
        val accumulatedDeltaRangeMeters: Double,  // Carrier phase (meters)
        val accumulatedDeltaRangeState: Int,      // ADR state flags
        val receivedSvTimeNanos: Long,    // Time of reception
        val state: Int,                   // Measurement state flags
        val multipath: Int,               // Multipath indicator
        val hasCarrierPhase: Boolean,     // If carrier phase is available
        val hasValidPseudorange: Boolean  // If pseudorange is valid
    )
    
    // Current measurements
    @Volatile var measurements = listOf<SatelliteMeasurement>()
        private set
    
    @Volatile var satelliteCount = 0
        private set
    
    @Volatile var measurementCount = 0L
        private set
    
    @Volatile var clockBiasNanos = 0.0
        private set
    
    @Volatile var clockDriftNanosPerSec = 0.0
        private set
    
    @Volatile var hardwareClockDiscontinuityCount = 0
        private set
    
    // Satellite status
    @Volatile var visibleSatellites = 0
        private set
    
    @Volatile var usedInFix = 0
        private set
    
    // === Callbacks ===
    var onMeasurementReceived: ((List<SatelliteMeasurement>) -> Unit)? = null
    var onSatelliteStatusChanged: ((visible: Int, usedInFix: Int) -> Unit)? = null
    
    // === GNSS Measurements Callback ===
    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            processMeasurements(event)
        }
        
        override fun onStatusChanged(status: Int) {
            Log.d(TAG, "GNSS Measurements status changed: $status")
        }
    }
    
    // === GNSS Status Callback ===
    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            visibleSatellites = status.satelliteCount
            usedInFix = (0 until status.satelliteCount).count { status.usedInFix(it) }
            onSatelliteStatusChanged?.invoke(visibleSatellites, usedInFix)
        }
        
        override fun onStarted() {
            Log.i(TAG, "GNSS Status started")
        }
        
        override fun onStopped() {
            Log.i(TAG, "GNSS Status stopped")
        }
    }
    
    /**
     * Start receiving Raw GNSS measurements
     */
    fun start(): Boolean {
        if (isRunning) return true
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted")
            return false
        }
        
        // Check if raw GNSS is supported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Raw GNSS requires Android 7.0+")
            return false
        }
        
        try {
            // Register for GNSS measurements
            val registered = locationManager.registerGnssMeasurementsCallback(
                measurementsCallback,
                handler
            )
            
            if (!registered) {
                Log.e(TAG, "Failed to register GNSS measurements callback")
                return false
            }
            
            // Register for GNSS status
            locationManager.registerGnssStatusCallback(statusCallback, handler)
            
            isRunning = true
            Log.i(TAG, "Raw GNSS started successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Raw GNSS: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop receiving Raw GNSS measurements
     */
    fun stop() {
        if (!isRunning) return
        
        try {
            locationManager.unregisterGnssMeasurementsCallback(measurementsCallback)
            locationManager.unregisterGnssStatusCallback(statusCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Raw GNSS: ${e.message}")
        }
        
        isRunning = false
        Log.i(TAG, "Raw GNSS stopped")
    }
    
    /**
     * Process raw GNSS measurements
     */
    private fun processMeasurements(event: GnssMeasurementsEvent) {
        val clock = event.clock
        
        // Extract clock data
        clockBiasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        clockDriftNanosPerSec = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else 0.0
        hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount
        
        // Process each satellite measurement
        val newMeasurements = mutableListOf<SatelliteMeasurement>()
        
        for (measurement in event.measurements) {
            val satMeasurement = extractMeasurement(measurement)
            newMeasurements.add(satMeasurement)
        }
        
        // Update state
        measurements = newMeasurements
        satelliteCount = newMeasurements.size
        measurementCount++
        
        // Notify callback
        onMeasurementReceived?.invoke(newMeasurements)
        
        // Log summary
        if (measurementCount % 10 == 0L) {
            Log.d(TAG, "Measurements: $satelliteCount sats, " +
                    "Visible: $visibleSatellites, Used: $usedInFix")
        }
    }
    
    /**
     * Extract measurement data from GnssMeasurement
     */
    private fun extractMeasurement(m: GnssMeasurement): SatelliteMeasurement {
        // Get constellation name
        val constellationName = when (m.constellationType) {
            CONSTELLATION_GPS -> "GPS"
            CONSTELLATION_GLONASS -> "GLONASS"
            CONSTELLATION_GALILEO -> "Galileo"
            CONSTELLATION_BEIDOU -> "BeiDou"
            CONSTELLATION_QZSS -> "QZSS"
            CONSTELLATION_SBAS -> "SBAS"
            CONSTELLATION_IRNSS -> "IRNSS"
            else -> "Unknown"
        }
        
        // Check if pseudorange is valid
        val hasValidPseudorange = (m.state and GnssMeasurement.STATE_CODE_LOCK) != 0
        
        // Check if carrier phase is available
        val hasCarrierPhase = (m.accumulatedDeltaRangeState and 
                GnssMeasurement.ADR_STATE_VALID) != 0
        
        // Calculate pseudorange in meters
        val pseudorangeMeters = if (hasValidPseudorange) {
            m.receivedSvTimeNanos * 0.299792458  // Speed of light * time in ns
        } else {
            0.0
        }
        
        return SatelliteMeasurement(
            svid = m.svid,
            constellation = m.constellationType,
            constellationName = constellationName,
            cn0DbHz = m.cn0DbHz,
            pseudorangeMeters = pseudorangeMeters,
            pseudorangeRateMetersPerSec = m.pseudorangeRateMetersPerSecond,
            carrierFrequencyHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && m.hasCarrierFrequencyHz()) 
                m.carrierFrequencyHz.toDouble() else 0.0,
            accumulatedDeltaRangeMeters = m.accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeState = m.accumulatedDeltaRangeState,
            receivedSvTimeNanos = m.receivedSvTimeNanos,
            state = m.state,
            multipath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) m.multipathIndicator else 0,
            hasCarrierPhase = hasCarrierPhase,
            hasValidPseudorange = hasValidPseudorange
        )
    }
    
    /**
     * Get measurements summary as string
     */
    fun getSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Raw GNSS Summary ===")
        sb.appendLine("Satellites: $satelliteCount visible, $usedInFix used in fix")
        sb.appendLine("Clock Bias: ${String.format("%.3f", clockBiasNanos / 1e9)} sec")
        sb.appendLine("Clock Drift: ${String.format("%.6f", clockDriftNanosPerSec / 1e9)} sec/sec")
        sb.appendLine("Measurements: $measurementCount")
        sb.appendLine()
        
        // Group by constellation
        val byConstellation = measurements.groupBy { it.constellationName }
        for ((name, sats) in byConstellation) {
            sb.appendLine("$name (${sats.size} satellites):")
            for (sat in sats.take(5)) {  // Show first 5
                sb.appendLine("  PRN ${sat.svid}: CN0=${String.format("%.1f", sat.cn0DbHz)}dB-Hz, " +
                        "PR=${String.format("%.0f", sat.pseudorangeMeters / 1000)}km")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Check if Raw GNSS is supported on this device
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
    
    /**
     * Get best satellites for positioning (highest CN0)
     */
    fun getBestSatellites(count: Int = 8): List<SatelliteMeasurement> {
        return measurements
            .filter { it.hasValidPseudorange }
            .sortedByDescending { it.cn0DbHz }
            .take(count)
    }
}

