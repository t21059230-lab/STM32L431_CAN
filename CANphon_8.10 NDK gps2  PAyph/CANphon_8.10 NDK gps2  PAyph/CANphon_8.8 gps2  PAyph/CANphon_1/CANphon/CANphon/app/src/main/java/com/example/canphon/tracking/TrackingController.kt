package com.example.canphon.tracking

import android.util.Log

/**
 * TrackingController - Singleton للتحكم في التتبع
 * يستقبل الأوامر من GcsSerialService وينقلها إلى TrackingActivity
 */
object TrackingController {
    
    private const val TAG = "TrackingController"
    
    // Listener للـ TrackingActivity
    interface TrackingCommandListener {
        fun onStartSearch()
        fun onStopSearch()
    }
    
    private var listener: TrackingCommandListener? = null
    
    // حالة التتبع
    var isSearching = false
        private set
    
    /**
     * تسجيل listener (يُستدعى من TrackingActivity)
     */
    fun setListener(l: TrackingCommandListener?) {
        listener = l
        Log.d(TAG, "Listener ${if (l != null) "registered" else "unregistered"}")
    }
    
    /**
     * بدء البحث (يُستدعى من GcsSerialService أو MainActivity)
     */
    fun startSearch() {
        Log.i(TAG, "🔍 START_SEARCH command received")
        isSearching = true
        listener?.onStartSearch()
    }
    
    /**
     * إيقاف البحث (يُستدعى من GcsSerialService أو MainActivity)
     */
    fun stopSearch() {
        Log.i(TAG, "⏹️ STOP_SEARCH command received")
        isSearching = false
        listener?.onStopSearch()
    }
    
    /**
     * هل يوجد listener مسجل (TrackingActivity مفتوحة)
     */
    fun hasActiveListener(): Boolean = listener != null
}
