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
 * المنطق الحقيقي الآن في: target_discriminator.cpp
 * استخدم NativeCore.discriminator* بدلاً من هذه الفئة
 * 
 * هذا الملف wrapper فقط للتوافقية مع الكود القديم
 */

import android.graphics.Rect


@Deprecated("Use NativeCore.discriminator* functions instead")
class TargetDiscriminator {
    
    data class TargetScore(
        val rect: Rect,
        val confidence: Float,
        val sizeScore: Float,
        val positionScore: Float,
        val stabilityScore: Float,
        val motionScore: Float,
        val totalScore: Float
    )
    
    init {
        NativeCore.discriminatorInit()
    }
    
    fun evaluateTargets(
        detectedRects: List<Rect>,
        lastTrackedRect: Rect? = null,
        imageWidth: Int,
        imageHeight: Int
    ): List<TargetScore> {
        // Delegate to C++
        val rects = IntArray(detectedRects.size * 4)
        detectedRects.forEachIndexed { i, rect ->
            rects[i * 4 + 0] = rect.centerX()
            rects[i * 4 + 1] = rect.centerY()
            rects[i * 4 + 2] = rect.width()
            rects[i * 4 + 3] = rect.height()
        }
        
        val lastX = lastTrackedRect?.centerX() ?: -1
        val lastY = lastTrackedRect?.centerY() ?: -1
        val lastW = lastTrackedRect?.width() ?: 0
        val lastH = lastTrackedRect?.height() ?: 0
        
        val scores = NativeCore.discriminatorEvaluateMultiple(
            rects, lastX, lastY, lastW, lastH, imageWidth, imageHeight
        )
        
        return detectedRects.mapIndexed { i, rect ->
            TargetScore(rect, 0.8f, scores[i], scores[i], scores[i], scores[i], scores[i])
        }.sortedByDescending { it.totalScore }
    }
    
    fun selectBestTarget(scores: List<TargetScore>): TargetScore? {
        return scores.maxByOrNull { it.totalScore }
    }
    
    fun filterWeakTargets(scores: List<TargetScore>, minScore: Float = 0.4f): List<TargetScore> {
        return scores.filter { it.totalScore >= minScore }
    }
    
    fun reset() {
        NativeCore.discriminatorReset()
    }
    
    fun cleanupHistory() {
        // Now handled in C++
    }
}

