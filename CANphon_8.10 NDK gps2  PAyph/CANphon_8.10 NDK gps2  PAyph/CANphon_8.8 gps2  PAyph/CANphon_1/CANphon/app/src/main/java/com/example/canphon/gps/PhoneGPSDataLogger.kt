package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.content.ContentValues
import android.content.Context
import android.location.Location
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

/**
 * مُسجّل بيانات GPS الهاتف - يحفظ كملف CSV منفصل
 * Phone GPS Data Logger - saves to separate CSV file
 * 
 * اسم الملف: GPS_Phone_*.csv
 */
class PhoneGPSDataLogger(private val context: Context) {

    companion object {
        private const val TAG = "PhoneGPSDataLogger"
    }

    // نقطة بيانات GPS الهاتف
    data class PhoneGpsLogEntry(
        val timestamp: Float,       // الزمن بالثواني
        val latitude: Double,       // خط العرض
        val longitude: Double,      // خط الطول
        val altitude: Double,       // الارتفاع
        val speed: Float,           // السرعة (m/s)
        val bearing: Float,         // الاتجاه
        val accuracy: Float,        // الدقة (متر)
        val satellites: Int         // عدد الأقمار (إن توفر)
    )

    var isRecording = false
        private set
    
    private var startTime = 0L
    private val logEntries = CopyOnWriteArrayList<PhoneGpsLogEntry>()
    private var sessionName = ""

    fun startRecording() {
        if (isRecording) return
        
        isRecording = true
        startTime = System.currentTimeMillis()
        logEntries.clear()
        sessionName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        
        Log.d(TAG, "✅ Phone GPS recording started")
    }

    fun stopRecording(): Uri? {
        if (!isRecording) return null
        
        isRecording = false
        Log.d(TAG, "⏹️ Phone GPS recording stopped. Entries: ${logEntries.size}")
        
        return saveToFile()
    }

    /**
     * تسجيل بيانات من Location
     */
    fun logData(location: Location?) {
        if (!isRecording) return
        
        val timestamp = (System.currentTimeMillis() - startTime) / 1000f
        
        logEntries.add(PhoneGpsLogEntry(
            timestamp = timestamp,
            latitude = location?.latitude ?: 0.0,
            longitude = location?.longitude ?: 0.0,
            altitude = location?.altitude ?: 0.0,
            speed = location?.speed ?: 0f,
            bearing = location?.bearing ?: 0f,
            accuracy = location?.accuracy ?: 0f,
            satellites = 0 // غير متوفر مباشرة في Location
        ))
    }

    fun getRecordingDuration(): Float {
        return if (isRecording) (System.currentTimeMillis() - startTime) / 1000f else 0f
    }

    fun getEntryCount(): Int = logEntries.size

    private fun saveToFile(): Uri? {
        if (logEntries.isEmpty()) {
            Log.w(TAG, "No phone GPS data to save")
            return null
        }

        // اسم الملف يحتوي على "Phone" للتمييز
        val filename = "GPS_Phone_$sessionName.csv"
        
        val csvContent = buildString {
            appendLine("Time(s),Latitude,Longitude,Altitude(m),Speed(m/s),Bearing(deg),Accuracy(m)")
            
            for (entry in logEntries) {
                appendLine(String.format(Locale.US,
                    "%.3f,%.7f,%.7f,%.2f,%.2f,%.1f,%.1f",
                    entry.timestamp,
                    entry.latitude,
                    entry.longitude,
                    entry.altitude,
                    entry.speed,
                    entry.bearing,
                    entry.accuracy
                ))
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                
                Log.d(TAG, "✅ Phone GPS file saved: $filename")
                uri
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, filename)
                FileOutputStream(file).use { fos ->
                    fos.write(csvContent.toByteArray())
                }
                Log.d(TAG, "✅ Phone GPS file saved: ${file.absolutePath}")
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving phone GPS file", e)
            null
        }
    }

    fun clear() {
        logEntries.clear()
        isRecording = false
    }
}

