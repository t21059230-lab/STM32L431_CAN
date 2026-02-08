package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import kotlin.math.sqrt

/**
 * شاشة عرض بيانات GPS الخارجي والهاتف - مع تسجيل مزدوج
 * Dual GPS Activity - Records both External and Phone GPS to separate files
 */
class GPSActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GPSActivity"
        private const val UPDATE_INTERVAL_MS = 100L // 10 Hz
        private const val LOCATION_PERMISSION_CODE = 1001
    }

    // Views
    private lateinit var recordCard: LinearLayout
    private lateinit var recordIndicator: View
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvRecordInfo: TextView
    private lateinit var tvRecordStats: TextView
    private lateinit var tvConnectionStatus: TextView

    // Data rows
    private lateinit var rowLatitude: View
    private lateinit var rowLongitude: View
    private lateinit var rowAltitude: View
    private lateinit var rowSpeed: View
    private lateinit var rowHeading: View
    private lateinit var rowSatGps: View
    private lateinit var rowSatGlonass: View
    private lateinit var rowSatTotal: View
    private lateinit var rowHdop: View
    private lateinit var rowFix: View
    private lateinit var rowVn: View
    private lateinit var rowVe: View
    private lateinit var rowVd: View
    private lateinit var rowMessages: View
    private lateinit var rowErrors: View

    // Managers - External GPS
    private lateinit var gpsService: SerialGpsService
    private lateinit var externalLogger: GPSDataLogger
    
    // Managers - Phone GPS
    private lateinit var phoneGpsManager: PhoneGPSManager
    private lateinit var phoneLogger: PhoneGPSDataLogger

    // State
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isActive = false

    // Update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isActive) {
                updateUI()
                
                // Log data if recording (both GPS sources)
                if (externalLogger.isRecording) {
                    // Log External GPS
                    val gpsData = gpsService.gpsData.value
                    val navData = gpsService.rawNavData.value
                    externalLogger.logData(gpsData, navData)
                    
                    // Log Phone GPS
                    phoneLogger.logData(phoneGpsManager.currentLocation)
                    
                    // Update recording stats (show both counts)
                    val duration = externalLogger.getRecordingDuration()
                    val extEntries = externalLogger.getEntryCount()
                    val phoneEntries = phoneLogger.getEntryCount()
                    tvRecordStats.text = String.format("%.1f ث\nخارجي: %d | هاتف: %d", duration, extEntries, phoneEntries)
                }
                
                mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps)

        initViews()
        initManagers()
        setupRecordButton()
        requestLocationPermission()
        
        Log.d(TAG, "GPSActivity created (Dual GPS Mode)")
    }

    private fun initViews() {
        recordCard = findViewById(R.id.recordCard)
        recordIndicator = findViewById(R.id.recordIndicator)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        tvRecordInfo = findViewById(R.id.tvRecordInfo)
        tvRecordStats = findViewById(R.id.tvRecordStats)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)

        rowLatitude = findViewById(R.id.rowLatitude)
        rowLongitude = findViewById(R.id.rowLongitude)
        rowAltitude = findViewById(R.id.rowAltitude)
        rowSpeed = findViewById(R.id.rowSpeed)
        rowHeading = findViewById(R.id.rowHeading)
        rowSatGps = findViewById(R.id.rowSatGps)
        rowSatGlonass = findViewById(R.id.rowSatGlonass)
        rowSatTotal = findViewById(R.id.rowSatTotal)
        rowHdop = findViewById(R.id.rowHdop)
        rowFix = findViewById(R.id.rowFix)
        rowVn = findViewById(R.id.rowVn)
        rowVe = findViewById(R.id.rowVe)
        rowVd = findViewById(R.id.rowVd)
        rowMessages = findViewById(R.id.rowMessages)
        rowErrors = findViewById(R.id.rowErrors)

        setRowLabel(rowLatitude, "خط العرض")
        setRowLabel(rowLongitude, "خط الطول")
        setRowLabel(rowAltitude, "الارتفاع")
        setRowLabel(rowSpeed, "السرعة")
        setRowLabel(rowHeading, "الاتجاه")
        setRowLabel(rowSatGps, "GPS")
        setRowLabel(rowSatGlonass, "GLONASS")
        setRowLabel(rowSatTotal, "المجموع")
        setRowLabel(rowHdop, "HDOP")
        setRowLabel(rowFix, "Fix")
        setRowLabel(rowVn, "Vn")
        setRowLabel(rowVe, "Ve")
        setRowLabel(rowVd, "Vd")
        setRowLabel(rowMessages, "رسائل")
        setRowLabel(rowErrors, "أخطاء")
    }

    private fun initManagers() {
        // External GPS
        gpsService = SerialGpsService.getInstance(this)
        externalLogger = GPSDataLogger(this)
        
        // Phone GPS
        phoneGpsManager = PhoneGPSManager(this)
        phoneLogger = PhoneGPSDataLogger(this)
        
        Log.d(TAG, "Managers initialized (External + Phone)")
    }
    
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    private fun setupRecordButton() {
        recordCard.setOnClickListener {
            if (externalLogger.isRecording) {
                // ═══ Stop Recording Both ═══
                val extUri = externalLogger.stopRecording()
                val phoneUri = phoneLogger.stopRecording()
                phoneGpsManager.stop()
                
                recordIndicator.setBackgroundResource(R.drawable.circle_gray)
                tvRecordStatus.text = "⚪ اضغط للتسجيل"
                tvRecordStatus.setTextColor(0xFFFFFFFF.toInt())
                tvRecordInfo.text = "📁 GPS_External + GPS_Phone"
                tvRecordStats.visibility = View.GONE
                
                // Show results
                val extCount = externalLogger.getEntryCount()
                val phoneCount = phoneLogger.getEntryCount()
                
                if (extUri != null || phoneUri != null) {
                    Toast.makeText(this, 
                        "✅ تم الحفظ في Downloads:\n" +
                        "• GPS_External: $extCount نقطة\n" +
                        "• GPS_Phone: $phoneCount نقطة", 
                        Toast.LENGTH_LONG).show()
                } else {
                    if (extCount == 0 && phoneCount == 0) {
                        Toast.makeText(this, "❌ لا توجد بيانات للحفظ", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ فشل الحفظ", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } else {
                // ═══ Start Recording Both ═══
                externalLogger.startRecording()
                phoneLogger.startRecording()
                phoneGpsManager.start()
                
                recordIndicator.setBackgroundResource(R.drawable.circle_red)
                tvRecordStatus.text = "🔴 تسجيل مزدوج..."
                tvRecordStatus.setTextColor(0xFFFF0000.toInt())
                tvRecordInfo.text = "خارجي + هاتف | اضغط للإيقاف"
                tvRecordStats.visibility = View.VISIBLE
                tvRecordStats.text = "0.0 ث\nخارجي: 0 | هاتف: 0"
                
                Toast.makeText(this, "🔴 بدء تسجيل مزدوج\n(GPS خارجي + GPS هاتف)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI() {
        // Connection status
        val state = gpsService.connectionState.value
        tvConnectionStatus.text = when (state) {
            SerialGpsService.ConnectionState.CONNECTED -> "🟢 متصل"
            SerialGpsService.ConnectionState.CONNECTING -> "🟡 جاري الاتصال..."
            SerialGpsService.ConnectionState.SEARCHING -> "🔵 البحث عن جهاز..."
            SerialGpsService.ConnectionState.NO_DEVICE -> "🟠 لا يوجد جهاز GPS"
            SerialGpsService.ConnectionState.REQUESTING_PERMISSION -> "⏳ طلب الإذن..."
            is SerialGpsService.ConnectionState.ERROR -> "🔴 خطأ: ${state.message}"
            else -> "⚪ غير متصل"
        }

        // GPS data
        val nav = gpsService.rawNavData.value
        val gps = gpsService.gpsData.value
        val stats = gpsService.statistics.value

        setRowValue(rowLatitude, String.format("%.7f°", nav?.latitude ?: 0f))
        setRowValue(rowLongitude, String.format("%.7f°", nav?.longitude ?: 0f))
        setRowValue(rowAltitude, String.format("%.2f م", nav?.altitude ?: 0f))

        val speed = gps?.let {
            sqrt(it.velocityNorth * it.velocityNorth + it.velocityEast * it.velocityEast).toFloat() * 3.6f
        } ?: 0f
        val heading = gps?.let {
            kotlin.math.atan2(it.velocityEast, it.velocityNorth)
                .let { rad -> Math.toDegrees(rad).toFloat() }
                .let { deg -> if (deg < 0) deg + 360f else deg }
        } ?: 0f
        setRowValue(rowSpeed, String.format("%.2f km/h", speed))
        setRowValue(rowHeading, String.format("%.1f°", heading))

        setRowValue(rowSatGps, "${nav?.usedSatCount ?: 0}")
        setRowValue(rowSatGlonass, "${nav?.glonassUsedSatCount ?: 0}")
        setRowValue(rowSatTotal, "${nav?.totalUsedSatellites ?: 0}")
        setRowValue(rowHdop, String.format("%.1f", (nav?.hdop?.toInt()?.and(0xFF) ?: 99) / 10f))
        setRowValue(rowFix, when (nav?.state?.toInt() ?: 0) { 0 -> "❌ لا"; 1 -> "✅ 3D"; else -> "🟡" })

        setRowValue(rowVn, String.format("%+.3f m/s", gps?.velocityNorth ?: 0.0))
        setRowValue(rowVe, String.format("%+.3f m/s", gps?.velocityEast ?: 0.0))
        setRowValue(rowVd, String.format("%+.3f m/s", gps?.velocityDown ?: 0.0))

        setRowValue(rowMessages, "${stats?.messagesReceived ?: 0}")
        setRowValue(rowErrors, "${stats?.frameErrors ?: 0}")
    }

    private fun setRowLabel(row: View, label: String) {
        row.findViewById<TextView>(R.id.tvLabel)?.text = label
    }

    private fun setRowValue(row: View, value: String) {
        row.findViewById<TextView>(R.id.tvValue)?.text = value
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        gpsService.start()
        mainHandler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        isActive = false
        mainHandler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (externalLogger.isRecording) {
            externalLogger.stopRecording()
        }
        if (phoneLogger.isRecording) {
            phoneLogger.stopRecording()
        }
        phoneGpsManager.stop()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Location permission granted")
            }
        }
    }
}

