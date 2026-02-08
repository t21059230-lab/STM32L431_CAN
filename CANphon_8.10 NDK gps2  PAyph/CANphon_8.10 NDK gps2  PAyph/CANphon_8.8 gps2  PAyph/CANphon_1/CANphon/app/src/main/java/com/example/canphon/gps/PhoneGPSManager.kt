package com.example.canphon.gps
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * مدير GPS الهاتف الداخلي
 * Phone Internal GPS Manager
 */
class PhoneGPSManager(private val context: Context) : LocationListener {

    companion object {
        private const val TAG = "PhoneGPSManager"
        private const val MIN_TIME_MS = 100L      // 10 Hz
        private const val MIN_DISTANCE_M = 0f     // أي تغيير
    }

    // البيانات الحالية
    var currentLocation: Location? = null
        private set
    
    var isActive = false
        private set

    private var locationManager: LocationManager? = null

    /**
     * بدء استقبال بيانات GPS الهاتف
     */
    fun start(): Boolean {
        if (isActive) return true

        // تحقق من الإذن
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "❌ No location permission")
            return false
        }

        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // طلب تحديثات من GPS
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                this,
                Looper.getMainLooper()
            )

            isActive = true
            Log.d(TAG, "✅ Phone GPS started")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start: ${e.message}")
            return false
        }
    }

    /**
     * إيقاف استقبال البيانات
     */
    fun stop() {
        if (!isActive) return

        try {
            locationManager?.removeUpdates(this)
            isActive = false
            Log.d(TAG, "⏹️ Phone GPS stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
    }

    // LocationListener
    override fun onLocationChanged(location: Location) {
        currentLocation = location
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Provider disabled: $provider")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Deprecated but required for older APIs
    }
}

