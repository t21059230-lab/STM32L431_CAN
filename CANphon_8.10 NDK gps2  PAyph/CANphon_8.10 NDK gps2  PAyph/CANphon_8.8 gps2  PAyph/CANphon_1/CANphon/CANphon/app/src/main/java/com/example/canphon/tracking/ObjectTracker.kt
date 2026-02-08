package com.example.canphon.tracking

import android.graphics.Rect
import android.graphics.Bitmap
import java.util.*
import kotlin.math.abs

/**
 * نظام تتبع الأهداف المتقدم (Military-Grade Seeker)
 * يستخدم Kalman Filter و Target Discrimination للدقة العالية
 */
class ObjectTracker {
    
    // Kalman Filter للتنبؤ بالحركة وتصفية الضوضاء
    private val kalmanFilter = KalmanFilter()
    
    // ❌ تعطيل التنبؤ بـ Kalman Filter
    private var enableKalmanPrediction = false  // إذا false = لا تنبؤ، فقط الموقع الحقيقي
    
    // Target Discriminator لتمييز الأهداف الحقيقية
    private val targetDiscriminator = TargetDiscriminator()
    
    private val trackedObjects = mutableListOf<TrackedObject>()
    private var lastCoords: Rect? = null
    private var lastPredictedCoords: Rect? = null
    
    // حالات التتبع (مشابهة للكود Python)
    enum class TrackingMode {
        OFF,
        SEARCH,
        TRACK,
        LOST_TARGET
    }
    
    private var mode = TrackingMode.OFF
    private var searchState = "stop_search"
    private var trackState = "stop"
    
    data class TrackedObject(
        val id: Int,
        val centerX: Int,
        val centerY: Int,
        val width: Int,
        val height: Int,
        val confidence: Float = 1.0f,
        var frameCount: Int = 0,
        var status: String = "close", // 'open' or 'close' (مشابه للكود Python)
        var history: MutableList<ObjectState> = mutableListOf() // تاريخ الهدف (مشابه لـ objects[indox])
    )
    
    data class ObjectState(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        var status: String = "close",
        var lostCount: Int = 0
    )
    
    /**
     * بدء البحث عن الأهداف (مشابه لـ search == 'start_search')
     */
    fun startSearch(detectedObjects: List<Rect>, imageWidth: Int, imageHeight: Int) {
        if (mode != TrackingMode.SEARCH && searchState != "start_search") {
            return
        }
        
        searchState = "start_search"
        mode = TrackingMode.SEARCH
        
        // معالجة الأهداف المكتشفة (مشابه للكود Python)
        processDetectedObjects(detectedObjects, imageWidth, imageHeight)
    }
    
    /**
     * معالجة الأهداف المكتشفة (مشابه تماماً للكود في track_prog_N.py)
     */
    fun processDetectedObjects(
        allCoords: List<Rect>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ObjectState> {
        val listTargets = mutableListOf<ObjectState>()
        val temeTarg = mutableListOf<ObjectState>()
        
        for (coord in allCoords) {
            // حساب المركز والأبعاد (مشابه للكود Python)
            val x = coord.centerX()
            val y = coord.centerY()
            val w = coord.width()
            val h = coord.height()
            
            val newOpj = ObjectState(x, y, w, h, "close", 0)
            val sizeObject = w + h
            
            // التحقق من صحة الإحداثيات (مطابق تماماً للكود Python: x > 10 && x < im_width && y > 10 && y < im_height)
            // تقليل الحد الأدنى للسماح باكتشاف الأهداف الصغيرة (نقطة بيضاء صغيرة)
            if (w > 2 && h > 2 && x > 5 && x < imageWidth && y > 5 && y < imageHeight) {
                android.util.Log.d("ObjectTracker", "✅ هدف صالح: x=$x (${(x * 100 / imageWidth)}%), y=$y (${(y * 100 / imageHeight)}%), w=$w, h=$h")
                // إذا لم يكن هناك أهداف، أضف أول هدف
                if (trackedObjects.isEmpty()) {
                    val newObj = TrackedObject(
                        id = 0,
                        centerX = x,
                        centerY = y,
                        width = w,
                        height = h
                    )
                    newObj.history.add(newOpj)
                    trackedObjects.add(newObj)
                }
                
                var noObject = false
                
                // تتبع الأهداف (مشابه للكود Python)
                for (trackedObj in trackedObjects) {
                    val lastState = trackedObj.history.lastOrNull()
                    if (lastState != null) {
                        // التحقق من قرب الهدف (نطاق ±1/16 من الصورة)
                        if (newOpj.x >= lastState.x - imageWidth / 16 &&
                            newOpj.x <= lastState.x + imageWidth / 16 &&
                            newOpj.y >= lastState.y - imageHeight / 16 &&
                            newOpj.y <= lastState.y + imageHeight / 16
                        ) {
                            noObject = true
                            
                            if (trackedObj.history.size >= 1) {
                                // إضافة إلى قائمة الأهداف الموثوقة
                                temeTarg.add(lastState)
                                listTargets.addAll(temeTarg)
                            }
                            
                            // إضافة الهدف إلى التاريخ (مشابه للكود Python)
                            if (trackedObj.history.size < 100) {
                                trackedObj.history.add(newOpj)
                            } else {
                                trackedObj.history.removeAt(0)
                                trackedObj.history.add(newOpj)
                            }
                            
                            trackedObj.history.last().status = "open"
                            break
                        }
                    }
                }
                
                // إضافة هدف جديد
                if (!noObject && trackedObjects.size < 100) {
                    newOpj.status = "open"
                    val newObj = TrackedObject(
                        id = trackedObjects.size,
                        centerX = x,
                        centerY = y,
                        width = w,
                        height = h
                    )
                    newObj.history.add(newOpj)
                    trackedObjects.add(newObj)
                }
            }
        }
        
        // إزالة الأهداف المفقودة (مشابه للكود Python)
        for (trackedObj in trackedObjects) {
            val lastState = trackedObj.history.lastOrNull()
            if (lastState != null) {
                if (lastState.status == "open") {
                    lastState.status = "close"
                } else {
                    lastState.lostCount++
                    if (lastState.lostCount > 6) {
                        trackedObj.history.clear()
                    }
                }
            }
        }
        
        // حذف الأهداف الفارغة
        trackedObjects.removeAll { it.history.isEmpty() }
        
        return listTargets
    }
    
    /**
     * بدء التتبع (Military-Grade Seeker)
     * يستخدم Kalman Filter للتنبؤ بالحركة
     */
    fun startTracking(target: Rect, frame: Bitmap?): Boolean {
        try {
            // حفظ الإحداثيات الأولية
            lastCoords = target
            
            // تهيئة Kalman Filter بالموضع الأولي
            kalmanFilter.initialize(
                target.centerX().toDouble(),
                target.centerY().toDouble()
            )
            
            // تعيين الحالات
            mode = TrackingMode.TRACK
            trackState = "start_track"
            
            android.util.Log.d("ObjectTracker", "✅ تم بدء التتبع المتقدم: $target, lastCoords=$lastCoords, trackState=$trackState")
            return true
        } catch (e: Exception) {
            android.util.Log.e("ObjectTracker", "❌ خطأ في بدء التتبع", e)
            e.printStackTrace()
        }
        
        return false
    }
    
    /**
     * تحديث التتبع (Military-Grade Seeker)
     * يستخدم Kalman Filter للتنبؤ و Target Discrimination للتمييز
     */
    fun updateTracking(
        detectedObjects: List<ObjectState>,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<Boolean, Rect?> {
        android.util.Log.d("ObjectTracker", "🔄 updateTracking: trackState=$trackState, lastCoords=$lastCoords, detectedObjects=${detectedObjects.size}")
        
        if (trackState != "start_track") {
            android.util.Log.w("ObjectTracker", "⚠️ trackState != start_track: $trackState")
            return Pair(false, null)
        }
        
        if (lastCoords == null) {
            android.util.Log.w("ObjectTracker", "⚠️ lastCoords == null")
            return Pair(false, null)
        }
        
        try {
            // 1. التنبؤ بموقع الهدف باستخدام Kalman Filter
            val (predictedX, predictedY) = kalmanFilter.predict()
            val predictedRect = Rect(
                (predictedX - lastCoords!!.width() / 2).toInt(),
                (predictedY - lastCoords!!.height() / 2).toInt(),
                (predictedX + lastCoords!!.width() / 2).toInt(),
                (predictedY + lastCoords!!.height() / 2).toInt()
            )
            lastPredictedCoords = predictedRect
            
            android.util.Log.d("ObjectTracker", "🔮 التنبؤ بموقع الهدف: ($predictedX, $predictedY)")
            
            // 2. البحث المباشر عن الهدف الأقرب للتنبؤ (مبسط وسريع)
            var bestTarget: ObjectState? = null
            var minDistance = Double.MAX_VALUE
            
            // نطاق البحث الموسع (بناءً على التنبؤ) - زيادة للحركة السريعة
            val searchRadius = maxOf(frameWidth / 2, frameHeight / 2, 500)  // نصف الصورة على الأقل
            
            android.util.Log.d("ObjectTracker", "🔍 البحث في ${detectedObjects.size} هدف، نطاق البحث: $searchRadius, التنبؤ: ($predictedX, $predictedY)")
            
            // إذا لم توجد أهداف مكتشفة
            if (detectedObjects.isEmpty()) {
                // ❌ تعطيل التنبؤ - فقدان الهدف مباشرة
                if (!enableKalmanPrediction) {
                    android.util.Log.w("ObjectTracker", "⚠️ لا توجد أهداف - فقدان الهدف (التنبؤ معطّل)")
                    return Pair(false, null)
                }
                
                // التنبؤ (إذا مفعّل)
                android.util.Log.d("ObjectTracker", "⚠️ لا توجد أهداف مكتشفة، استخدام التنبؤ")
                val uncertainty = kalmanFilter.getUncertainty()
                if (uncertainty < 200.0) {
                    lastCoords = predictedRect
                    return Pair(true, predictedRect)
                } else {
                    return Pair(false, null)
                }
            }
            
            // البحث المباشر عن الهدف الأقرب (بدون TargetDiscriminator المعقد)
            // إذا لم توجد أهداف، استخدم التنبؤ مباشرة
            if (detectedObjects.isEmpty()) {
                android.util.Log.d("ObjectTracker", "⚠️ لا توجد أهداف مكتشفة، استخدام التنبؤ")
                val uncertainty = kalmanFilter.getUncertainty()
                if (uncertainty < 500.0) {  // زيادة الحد الأقصى
                    lastCoords = predictedRect
                    return Pair(true, predictedRect)
                } else {
                    return Pair(false, null)
                }
            }
            
            for (state in detectedObjects) {
                val distance = calculateDistance(
                    predictedX.toInt(),
                    predictedY.toInt(),
                    state.x,
                    state.y
                )
                
                android.util.Log.d("ObjectTracker", "🔍 فحص هدف: ($state.x, $state.y), مسافة: $distance, نطاق: $searchRadius, التنبؤ: ($predictedX, $predictedY)")
                
                // قبول أي هدف في نطاق البحث (أكثر مرونة) - حتى لو كان بعيداً قليلاً
                if (distance < searchRadius && distance < minDistance) {
                    minDistance = distance
                    bestTarget = state
                    android.util.Log.d("ObjectTracker", "✅ هدف محتمل: ($state.x, $state.y), مسافة: $distance")
                }
            }
            
            android.util.Log.d("ObjectTracker", "🎯 أفضل هدف: ${if (bestTarget != null) "(${bestTarget.x}, ${bestTarget.y})" else "لا يوجد"}, مسافة: $minDistance")
            
            // إذا لم نجد هدفاً قريباً، لكن لدينا أهداف - استخدم الأقرب حتى لو كان خارج النطاق
            if (bestTarget == null && detectedObjects.isNotEmpty()) {
                android.util.Log.d("ObjectTracker", "⚠️ لا يوجد هدف في النطاق، استخدام الأقرب على الإطلاق")
                for (state in detectedObjects) {
                    val distance = calculateDistance(
                        predictedX.toInt(),
                        predictedY.toInt(),
                        state.x,
                        state.y
                    )
                    if (distance < minDistance) {
                        minDistance = distance
                        bestTarget = state
                    }
                }
                android.util.Log.d("ObjectTracker", "✅ تم اختيار الأقرب: ${bestTarget?.let { "(${it.x}, ${it.y})" }}, مسافة: $minDistance")
            }
            
            if (bestTarget != null) {
                val rect = objectStateToRect(bestTarget)
                
                // تحديث Kalman Filter بالقياس الجديد (حتى لو معطّل، للتوافق)
                kalmanFilter.update(
                    bestTarget.x.toDouble(),
                    bestTarget.y.toDouble()
                )
                
                lastCoords = rect
                
                // ❌ إذا كان Kalman معطّل، نرجع الموقع الحقيقي (raw) بدون تصفية
                if (!enableKalmanPrediction) {
                    android.util.Log.d("ObjectTracker", 
                        "✅ تم تحديث التتبع (RAW): (${bestTarget.x}, ${bestTarget.y}), distance=$minDistance")
                    return Pair(true, rect)
                }
                
                // الحصول على الموضع المصفى من Kalman Filter
                val (filteredX, filteredY) = kalmanFilter.getPosition()
                val filteredRect = Rect(
                    (filteredX - rect.width() / 2).toInt(),
                    (filteredY - rect.height() / 2).toInt(),
                    (filteredX + rect.width() / 2).toInt(),
                    (filteredY + rect.height() / 2).toInt()
                )
                
                android.util.Log.d("ObjectTracker", 
                    "✅ تم تحديث التتبع: raw=(${bestTarget.x}, ${bestTarget.y}), " +
                    "filtered=($filteredX, $filteredY), distance=$minDistance")
                
                return Pair(true, filteredRect)
            } else {
                // ❌ تعطيل التنبؤ - فقدان الهدف مباشرة
                if (!enableKalmanPrediction) {
                    android.util.Log.w("ObjectTracker", "⚠️ لم يتم العثور على هدف قريب - فقدان (التنبؤ معطّل)")
                    mode = TrackingMode.LOST_TARGET
                    trackState = "stop"
                    searchState = "start_search"
                    return Pair(false, null)
                }
                
                // استخدام التنبؤ إذا لم نجد هدفاً (تتبع بالتنبؤ) - أكثر مرونة للحركة السريعة
                android.util.Log.d("ObjectTracker", "⚠️ لم يتم العثور على هدف، استخدام التنبؤ")
                android.util.Log.d("ObjectTracker", "📊 الأهداف المفحوصة: ${detectedObjects.size}, نطاق البحث: $searchRadius")
                
                val uncertainty = kalmanFilter.getUncertainty()
                android.util.Log.d("ObjectTracker", "📊 Uncertainty: $uncertainty")
                
                // زيادة الحد الأقصى للـ uncertainty للسماح بالتنبؤ حتى مع عدم اليقين العالي
                // استخدام التنبؤ دائماً إذا كان هناك lastCoords (حتى مع uncertainty عالي)
                if (lastCoords != null) {
                    android.util.Log.d("ObjectTracker", "🔮 استخدام التنبؤ: uncertainty=$uncertainty, predictedRect=$predictedRect")
                    lastCoords = predictedRect
                    return Pair(true, predictedRect)
                } else {
                    // فقدان الهدف
                    android.util.Log.w("ObjectTracker", "⚠️ فقدان الهدف: uncertainty=$uncertainty, لا يوجد lastCoords")
                    mode = TrackingMode.LOST_TARGET
                    trackState = "stop"
                    searchState = "start_search"
                    kalmanFilter.reset()
                    return Pair(false, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(false, null)
        }
    }
    
    // أبعاد الصورة (يتم تحديثها من Activity)
    private var imageWidth = 1280
    private var imageHeight = 720
    
    /**
     * تحديث أبعاد الصورة
     */
    fun setImageDimensions(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }
    
    /**
     * الحصول على Kalman Filter للاستخدام في UltraFastTracker
     */
    fun getKalmanFilter(): KalmanFilter? {
        return if (trackState == "start_track" && lastCoords != null) {
            kalmanFilter
        } else {
            null
        }
    }
    
    /**
     * اختيار الهدف المناسب (Military-Grade Target Selection)
     * يستخدم Target Discriminator لاختيار أفضل هدف
     */
    fun selectBestTarget(listTargets: List<ObjectState>, lastCoords: Rect?): ObjectState? {
        if (listTargets.isEmpty()) {
            return null
        }
        
        // تحويل ObjectState إلى Rect
        val detectedRects = listTargets.map { objectStateToRect(it) }
        
        // استخدام Target Discriminator لتقييم الأهداف
        val targetScores = targetDiscriminator.evaluateTargets(
            detectedRects,
            lastCoords,
            imageWidth,
            imageHeight
        )
        
        // تصفية الأهداف الضعيفة
        val filteredScores = targetDiscriminator.filterWeakTargets(targetScores, 0.4f)
        
        // اختيار أفضل هدف
        val bestScore = targetDiscriminator.selectBestTarget(filteredScores)
        
        if (bestScore != null) {
            // العثور على ObjectState المقابل
            return listTargets.firstOrNull { 
                objectStateToRect(it) == bestScore.rect 
            }
        }
        
        // إذا لم نجد هدفاً جيداً، نستخدم الطريقة القديمة (للتوافق)
        if (lastCoords != null) {
            var nearest: ObjectState? = null
            var minDistance = Double.MAX_VALUE
            
            listTargets.forEach { target ->
                val distance = calculateDistance(
                    lastCoords.centerX(),
                    lastCoords.centerY(),
                    target.x,
                    target.y
                )
                
                if (distance < minDistance) {
                    minDistance = distance
                    nearest = target
                }
            }
            
            return nearest
        }
        
        // خلاف ذلك، اختر الأكبر
        var largest = listTargets[0]
        for (target in listTargets) {
            if (target.w > largest.w && target.h > largest.h) {
                largest = target
            }
        }
        
        return largest
    }
    
    /**
     * تحويل ObjectState إلى Rect
     */
    fun objectStateToRect(state: ObjectState): Rect {
        return Rect(
            state.x - state.w / 2,
            state.y - state.h / 2,
            state.x + state.w / 2,
            state.y + state.h / 2
        )
    }
    
    private fun calculateDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    
    /**
     * تحويل إحداثيات (مشابه لـ tools.MAP())
     */
    fun mapCoordinates(
        value: Float,
        fromMin: Float,
        fromMax: Float,
        toMin: Float,
        toMax: Float
    ): Float {
        return (value - fromMin) * (toMax - toMin) / (fromMax - fromMin) + toMin
    }
    
    /**
     * الحصول على الأهداف المتبعة
     */
    fun getTrackedObjects(): List<ObjectState> {
        val result = mutableListOf<ObjectState>()
        trackedObjects.forEach { obj ->
            obj.history.lastOrNull()?.let { state ->
                if (state.status == "open") {
                    result.add(state)
                }
            }
        }
        return result
    }
    
    /**
     * إعادة تعيين التتبع
     */
    fun reset() {
        trackedObjects.clear()
        lastCoords = null
        lastPredictedCoords = null
        kalmanFilter.reset()
        targetDiscriminator.reset()
        mode = TrackingMode.OFF
        searchState = "stop_search"
        trackState = "stop"
        android.util.Log.d("ObjectTracker", "🔄 تم إعادة تعيين التتبع")
    }
    
    // Getters and Setters
    fun getMode(): TrackingMode = mode
    fun setMode(newMode: TrackingMode) {
        mode = newMode
    }
    
    fun getSearchState(): String = searchState
    fun setSearchState(state: String) {
        searchState = state
    }
    
    fun getTrackState(): String = trackState
    fun setTrackState(state: String) {
        trackState = state
    }

    /**
     * الحصول على دقة التتبع (من 0.0 إلى 1.0)
     */
    val trackerConfidence: Float
        get() = if (trackState == "start_track" && lastCoords != null) {
            // يمكن تحسين هذا لاستخدام Uncertainty من Kalman Filter
            // حالياً: كلما قل Uncertainty زادت الثقة
            val uncertainty = kalmanFilter.getUncertainty()
            // Uncertainty 0 -> Confidence 1.0
            // Uncertainty 500 -> Confidence 0.0
            ((500.0 - uncertainty) / 500.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            0.0f
        }
}

