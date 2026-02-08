package com.example.canphon

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data Logger for Servo Feedback
 * 
 * Saves feedback data to CSV file in Downloads folder
 * Compatible with Android 10+ (Scoped Storage)
 */
class DataLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "DataLogger"
    }
    
    private var writer: OutputStreamWriter? = null
    private var isRecording = false
    private var fileName = ""
    private var recordCount = 0
    private var startTime = 0L
    
    /**
     * Start recording to a new CSV file
     */
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            fileName = "CANphon_Log_$timestamp.csv"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                if (uri != null) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    writer = OutputStreamWriter(outputStream)
                }
            } else {
                // Android 9 and below
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val file = File(downloadsDir, fileName)
                writer = OutputStreamWriter(FileOutputStream(file))
            }
            
            // Write CSV header
            writer?.write("Timestamp,ElapsedMs,Roll,Pitch,Servo1_Cmd,Servo2_Cmd,Servo3_Cmd,Servo4_Cmd,Servo1_FB,Servo2_FB,Servo3_FB,Servo4_FB\n")
            writer?.flush()
            
            isRecording = true
            recordCount = 0
            startTime = System.currentTimeMillis()
            
            Log.i(TAG, "📝 Started recording to $fileName")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return false
        }
    }
    
    /**
     * Log a data row
     */
    fun logData(
        roll: Float,
        pitch: Float,
        servo1Cmd: Float,
        servo2Cmd: Float,
        servo3Cmd: Float,
        servo4Cmd: Float,
        servo1Fb: Float = 0f,
        servo2Fb: Float = 0f,
        servo3Fb: Float = 0f,
        servo4Fb: Float = 0f
    ) {
        if (!isRecording || writer == null) return
        
        try {
            val elapsed = System.currentTimeMillis() - startTime
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            
            val line = String.format(
                Locale.US,
                "%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                timestamp, elapsed,
                roll, pitch,
                servo1Cmd, servo2Cmd, servo3Cmd, servo4Cmd,
                servo1Fb, servo2Fb, servo3Fb, servo4Fb
            )
            
            writer?.write(line)
            recordCount++
            
            // Flush every 50 records
            if (recordCount % 50 == 0) {
                writer?.flush()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log data", e)
        }
    }
    
    /**
     * Stop recording and close file
     */
    fun stopRecording(): String {
        if (!isRecording) return ""
        
        try {
            writer?.flush()
            writer?.close()
            writer = null
            isRecording = false
            
            val duration = (System.currentTimeMillis() - startTime) / 1000
            Log.i(TAG, "📁 Stopped recording. $recordCount records, ${duration}s duration")
            Log.i(TAG, "📂 File saved: Downloads/$fileName")
            
            return fileName
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            return ""
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get record count
     */
    fun getRecordCount(): Int = recordCount
    
    /**
     * Get file name
     */
    fun getFileName(): String = fileName
}
