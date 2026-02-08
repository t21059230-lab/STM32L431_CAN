package com.example.canphon.tracking

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * نظام تمييز الأهداف المتقدم (Target Discrimination)
 * يستخدم في أنظمة السيكر العسكرية لتمييز الأهداف الحقيقية من الضوضاء
 */
class TargetDiscriminator {
    
    // معايير التمييز
    data class TargetScore(
        val rect: Rect,
        val confidence: Float,
        val sizeScore: Float,
        val positionScore: Float,
        val stabilityScore: Float,
        val motionScore: Float,
        val totalScore: Float
    )
    
    // تاريخ الأهداف (لحساب الاستقرار)
    private val targetHistory = mutableMapOf<Int, MutableList<Rect>>()
    private var targetIdCounter = 0
    
    // معايير التقييم
    private val minSize = 20  // الحد الأدنى للحجم
    private val maxSize = 500  // الحد الأقصى للحجم
    private val minAspectRatio = 0.3f  // الحد الأدنى لنسبة العرض/الارتفاع
    private val maxAspectRatio = 3.0f  // الحد الأقصى لنسبة العرض/الارتفاع
    private val stabilityFrames = 3  // عدد الإطارات المطلوبة للاستقرار
    
    /**
     * تقييم الأهداف المكتشفة وترتيبها حسب الأولوية
     */
    fun evaluateTargets(
        detectedRects: List<Rect>,
        lastTrackedRect: Rect? = null,
        imageWidth: Int,
        imageHeight: Int
    ): List<TargetScore> {
        val scores = mutableListOf<TargetScore>()
        
        for (rect in detectedRects) {
            val score = calculateTargetScore(rect, lastTrackedRect, imageWidth, imageHeight)
            scores.add(score)
        }
        
        // ترتيب حسب النقاط الإجمالية (من الأعلى للأقل)
        return scores.sortedByDescending { it.totalScore }
    }
    
    /**
     * حساب نقاط الهدف
     */
    private fun calculateTargetScore(
        rect: Rect,
        lastTrackedRect: Rect?,
        imageWidth: Int,
        imageHeight: Int
    ): TargetScore {
        val width = rect.width()
        val height = rect.height()
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val area = width * height
        
        // 1. Size Score (نقاط الحجم) - الأهداف المتوسطة الحجم أفضل
        val sizeScore = when {
            area < minSize * minSize -> 0.0f  // صغير جداً
            area > maxSize * maxSize -> 0.3f  // كبير جداً
            else -> {
                val normalizedSize = (area - minSize * minSize).toFloat() / 
                                    (maxSize * maxSize - minSize * minSize).toFloat()
                1.0f - abs(normalizedSize - 0.5f) * 2.0f  // أفضل في المنتصف
            }
        }
        
        // 2. Aspect Ratio Score (نقاط نسبة العرض/الارتفاع)
        val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1.0f
        val aspectScore = when {
            aspectRatio < minAspectRatio || aspectRatio > maxAspectRatio -> 0.2f
            aspectRatio in 0.8f..1.2f -> 1.0f  // مربع تقريباً (مفضل للدبابات)
            else -> 0.7f
        }
        
        // 3. Position Score (نقاط الموضع) - الأهداف في المركز أفضل
        val centerDistance = sqrt(
            (centerX - imageWidth / 2).toDouble().pow(2) + 
            (centerY - imageHeight / 2).toDouble().pow(2)
        )
        val maxDistance = sqrt(
            (imageWidth / 2).toDouble().pow(2) + 
            (imageHeight / 2).toDouble().pow(2)
        )
        val positionScore = (1.0f - (centerDistance / maxDistance).toFloat()).coerceIn(0.0f, 1.0f)
        
        // 4. Stability Score (نقاط الاستقرار) - الأهداف المستقرة أفضل
        val rectHash = rect.hashCode()
        val history = targetHistory.getOrPut(rectHash) { mutableListOf() }
        history.add(rect)
        if (history.size > stabilityFrames) {
            history.removeAt(0)
        }
        
        val stabilityScore = if (history.size >= stabilityFrames) {
            // حساب التباين في الموضع
            val avgX = history.map { it.centerX() }.average()
            val avgY = history.map { it.centerY() }.average()
            val variance = history.map { 
                sqrt((it.centerX() - avgX).pow(2) + (it.centerY() - avgY).pow(2))
            }.average()
            
            // كلما قل التباين، زادت النقاط
            (1.0f - (variance / 50.0).toFloat().coerceIn(0.0f, 1.0f))
        } else {
            0.3f  // غير مستقر بعد
        }
        
        // 5. Motion Score (نقاط الحركة) - إذا كان هناك هدف متتبع سابق
        val motionScore = if (lastTrackedRect != null) {
            val distance = sqrt(
                (centerX - lastTrackedRect.centerX()).toDouble().pow(2) + 
                (centerY - lastTrackedRect.centerY()).toDouble().pow(2)
            )
            val maxMotion = sqrt(
                (imageWidth / 4).toDouble().pow(2) + 
                (imageHeight / 4).toDouble().pow(2)
            )
            
            // الأهداف القريبة من الهدف السابق أفضل
            (1.0f - (distance / maxMotion).toFloat().coerceIn(0.0f, 1.0f))
        } else {
            0.5f  // لا يوجد هدف سابق
        }
        
        // 6. Confidence Score (من نموذج الكشف)
        val confidence = 0.8f  // افتراضي - يمكن تحسينه من نتائج YOLO
        
        // حساب النقاط الإجمالية (مرجحة)
        val totalScore = (
            sizeScore * 0.20f +
            aspectScore * 0.15f +
            positionScore * 0.15f +
            stabilityScore * 0.25f +
            motionScore * 0.15f +
            confidence * 0.10f
        )
        
        android.util.Log.d("TargetDiscriminator", 
            "🎯 تقييم هدف: size=$sizeScore, aspect=$aspectScore, pos=$positionScore, " +
            "stability=$stabilityScore, motion=$motionScore, total=$totalScore")
        
        return TargetScore(
            rect = rect,
            confidence = confidence,
            sizeScore = sizeScore,
            positionScore = positionScore,
            stabilityScore = stabilityScore,
            motionScore = motionScore,
            totalScore = totalScore
        )
    }
    
    /**
     * تصفية الأهداف الضعيفة (إزالة الضوضاء)
     */
    fun filterWeakTargets(scores: List<TargetScore>, minScore: Float = 0.4f): List<TargetScore> {
        return scores.filter { it.totalScore >= minScore }
    }
    
    /**
     * اختيار أفضل هدف (الأعلى نقاط)
     */
    fun selectBestTarget(scores: List<TargetScore>): TargetScore? {
        return scores.maxByOrNull { it.totalScore }
    }
    
    /**
     * تنظيف التاريخ القديم
     */
    fun cleanupHistory() {
        // إزالة الأهداف التي لم تظهر في آخر 10 إطارات
        targetHistory.entries.removeAll { it.value.isEmpty() }
    }
    
    /**
     * إعادة تعيين
     */
    fun reset() {
        targetHistory.clear()
        targetIdCounter = 0
    }
}

