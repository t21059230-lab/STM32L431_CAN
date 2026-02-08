package com.example.canphon.tracking
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.native_sensors.NativeCore
import com.example.canphon.data.*

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DEPRECATED - تم نقل هذا الكود إلى C++
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * المنطق الحقيقي الآن في: kalman_filter.cpp
 * استخدم NativeCore.kalman* بدلاً من هذه الفئة
 * 
 * هذا الملف wrapper فقط للتوافقية مع الكود القديم
 */



@Deprecated("Use NativeCore.kalman* functions instead")
class KalmanFilter {
    
    init {
        NativeCore.kalmanInit(0.0, 0.0, 300.0, 1.0)
    }
    
    fun initialize(x: Double, y: Double) {
        NativeCore.kalmanInit(x, y, 300.0, 1.0)
    }
    
    fun predict(): Pair<Double, Double> {
        val result = NativeCore.kalmanPredict()
        return Pair(result[0], result[1])
    }
    
    fun update(measuredX: Double, measuredY: Double) {
        NativeCore.kalmanUpdate(measuredX, measuredY)
    }
    
    fun getPosition(): Pair<Double, Double> {
        val state = NativeCore.kalmanGetState()
        return Pair(state[0], state[1])
    }
    
    fun getVelocity(): Pair<Double, Double> {
        val state = NativeCore.kalmanGetState()
        return Pair(state[2], state[3])
    }
    
    fun getUncertainty(): Double {
        return NativeCore.kalmanGetUncertainty()
    }
    
    fun reset() {
        NativeCore.kalmanReset()
    }
    
    fun predictFuture(steps: Int): Pair<Double, Double> {
        val result = NativeCore.kalmanPredictFuture(steps)
        return Pair(result[0], result[1])
    }
}

