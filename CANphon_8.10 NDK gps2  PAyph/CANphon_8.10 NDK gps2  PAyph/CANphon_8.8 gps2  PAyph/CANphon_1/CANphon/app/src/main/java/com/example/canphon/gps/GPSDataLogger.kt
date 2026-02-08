package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * مُسجّل بيانات GPS - يسجل الموقع والسرعة والارتفاع مع الزمن
 * يحفظ كملف CSV في مجلد Downloads
 * 
 * Based on servocontroller8 DataLogger pattern
 */
class GPSDataLogger(private val context: Context) {

    companion object {
        private const val TAG = "GPSDataLogger"
    }

    // نقطة بيانات GPS واحدة
    data class GpsLogEntry(
        val timestamp: Float,       // الزمن بالثواني من بداية التسجيل
        val latitude: Float,        // خط العرض
        val longitude: Float,       // خط الطول
        val altitude: Float,        // الارتفاع (م)
        val speedKmh: Float,        // السرعة (km/h)
        val heading: Float,         // الاتجاه (درجة)
        val satellites: Int,        // عدد الأقمار
        val hdop: Float,            // دقة أفقية
        val velocityN: Float,       // السرعة شمال (m/s)
        val velocityE: Float,       // السرعة شرق (m/s)
        val velocityD: Float,       // السرعة أسفل (m/s)
        val ax: Float,              // تسارع X
        val ay: Float,              // تسارع Y
        val az: Float               // تسارع Z
    )

    // حالة التسجيل
    var isRecording = false
        private set
    
    private var startTime = 0L
    private val logEntries = CopyOnWriteArrayList<GpsLogEntry>()
    private var sessionName = ""

    /**
     * بدء التسجيل
     */
    fun startRecording() {
        if (isRecording) return
        
        isRecording = true
        startTime = System.currentTimeMillis()
        logEntries.clear()
        sessionName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        
        Log.d(TAG, "✅ Recording started: $sessionName")
    }

    /**
     * إيقاف التسجيل وحفظ الملف
     */
    fun stopRecording(): Uri? {
        if (!isRecording) return null
        
        isRecording = false
        Log.d(TAG, "⏹️ Recording stopped. Entries: ${logEntries.size}")
        
        return saveToFile()
    }

    /**
     * إضافة نقطة بيانات GPS (يُنادى عند كل تحديث GPS)
     * يسجل البيانات حتى لو كانت أصفار
     */
    fun logData(gpsResult: GpsResult?, navData: NavData?) {
        if (!isRecording) return
        
        val timestamp = (System.currentTimeMillis() - startTime) / 1000f
        
        // حساب السرعة والاتجاه من الـ velocity (أو صفر)
        val speedKmh = gpsResult?.let {
            sqrt(it.velocityNorth * it.velocityNorth + it.velocityEast * it.velocityEast).toFloat() * 3.6f
        } ?: 0f
        
        val heading = gpsResult?.let {
            kotlin.math.atan2(it.velocityEast, it.velocityNorth)
                .let { rad -> Math.toDegrees(rad).toFloat() }
                .let { deg -> if (deg < 0) deg + 360f else deg }
        } ?: 0f
        
        val hdop = navData?.let { (it.hdop.toInt() and 0xFF) / 10f } ?: 0f
        
        logEntries.add(GpsLogEntry(
            timestamp = timestamp,
            latitude = navData?.latitude ?: 0f,
            longitude = navData?.longitude ?: 0f,
            altitude = navData?.altitude ?: 0f,
            speedKmh = speedKmh,
            heading = heading,
            satellites = navData?.totalUsedSatellites ?: 0,
            hdop = hdop,
            velocityN = gpsResult?.velocityNorth?.toFloat() ?: 0f,
            velocityE = gpsResult?.velocityEast?.toFloat() ?: 0f,
            velocityD = gpsResult?.velocityDown?.toFloat() ?: 0f,
            ax = navData?.ax ?: 0f,
            ay = navData?.ay ?: 0f,
            az = navData?.az ?: 0f
        ))
    }

    /**
     * الحصول على مدة التسجيل بالثواني
     */
    fun getRecordingDuration(): Float {
        return if (isRecording) {
            (System.currentTimeMillis() - startTime) / 1000f
        } else {
            0f
        }
    }

    /**
     * الحصول على عدد النقاط المسجلة
     */
    fun getEntryCount(): Int = logEntries.size

    /**
     * حفظ البيانات في ملف CSV
     */
    private fun saveToFile(): Uri? {
        if (logEntries.isEmpty()) {
            Log.w(TAG, "No data to save")
            return null
        }

        // اسم الملف يحتوي على "External" للتمييز من GPS الهاتف
        val filename = "GPS_External_$sessionName.csv"
        
        // بناء محتوى CSV
        val csvContent = buildString {
            // Header
            appendLine("Time(s),Latitude,Longitude,Altitude(m),Speed(km/h),Heading(deg),Satellites,HDOP,Vn(m/s),Ve(m/s),Vd(m/s),Ax(m/s2),Ay(m/s2),Az(m/s2)")
            
            // Data
            for (entry in logEntries) {
                appendLine(String.format(Locale.US,
                    "%.3f,%.7f,%.7f,%.2f,%.2f,%.1f,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                    entry.timestamp,
                    entry.latitude,
                    entry.longitude,
                    entry.altitude,
                    entry.speedKmh,
                    entry.heading,
                    entry.satellites,
                    entry.hdop,
                    entry.velocityN,
                    entry.velocityE,
                    entry.velocityD,
                    entry.ax,
                    entry.ay,
                    entry.az
                ))
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - استخدام MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(csvContent.toByteArray())
                    }
                }
                
                Log.d(TAG, "✅ File saved: $uri ($filename)")
                uri
            } else {
                // Android 9 and below
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, filename)
                FileOutputStream(file).use { fos ->
                    fos.write(csvContent.toByteArray())
                }
                Log.d(TAG, "✅ File saved: ${file.absolutePath}")
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving file", e)
            null
        }
    }

    /**
     * مسح البيانات
     */
    fun clear() {
        logEntries.clear()
        isRecording = false
    }
}

