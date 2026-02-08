package com.example.canphon.tracking

import android.graphics.Rect
import kotlin.math.pow

/**
 * Kalman Filter للتنبؤ بالحركة وتصفية الضوضاء
 * يستخدم في أنظمة السيكر العسكرية للتنبؤ بموقع الهدف
 */
class KalmanFilter {
    
    // State vector: [x, y, vx, vy] (الموضع والسرعة)
    private var state = DoubleArray(4)  // [x, y, vx, vy]
    private var covariance = Array(4) { DoubleArray(4) }
    private var lastPosition = Pair(0.0, 0.0)  // الموضع السابق لحساب السرعة
    
    // Process noise (ضوضاء العملية) - زيادة للحركة فائقة السرعة (صاروخ 400 م/ث)
    // ════════════════════════════════════════════════════════════════════
    // القيمة الأصلية: 2.0 (بطيئة جداً للصواريخ)
    // القيمة الجديدة: 50.0 (للتتبع فائق السرعة)
    // ════════════════════════════════════════════════════════════════════
    private val processNoise = 300.0  // للسرعة الفائقة: 400 م/ث = ~200 بكسل/إطار
    
    // Measurement noise (ضوضاء القياس) - تقليل للدقة
    private val measurementNoise = 1.0  // تقليل من 2.0 إلى 1.0 للدقة العالية
    
    // State transition matrix (مصفوفة الانتقال)
    private val F = arrayOf(
        doubleArrayOf(1.0, 0.0, 1.0, 0.0),  // x = x + vx
        doubleArrayOf(0.0, 1.0, 0.0, 1.0),  // y = y + vy
        doubleArrayOf(0.0, 0.0, 1.0, 0.0),  // vx = vx
        doubleArrayOf(0.0, 0.0, 0.0, 1.0)   // vy = vy
    )
    
    // Measurement matrix (مصفوفة القياس)
    private val H = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),  // نقيس x فقط
        doubleArrayOf(0.0, 1.0, 0.0, 0.0)   // نقيس y فقط
    )
    
    // Process noise covariance (Q)
    private val Q = Array(4) { i ->
        DoubleArray(4) { j ->
            if (i == j) processNoise else 0.0
        }
    }
    
    // Measurement noise covariance (R)
    private val R = arrayOf(
        doubleArrayOf(measurementNoise, 0.0),
        doubleArrayOf(0.0, measurementNoise)
    )
    
    private var isInitialized = false
    
    /**
     * تهيئة Kalman Filter بموضع أولي
     */
    fun initialize(x: Double, y: Double) {
        state[0] = x
        state[1] = y
        state[2] = 0.0  // vx = 0 (السرعة الأولية)
        state[3] = 0.0  // vy = 0 (السرعة الأولية)
        lastPosition = Pair(x, y)  // حفظ الموضع الأولي
        
        // تهيئة covariance matrix (عدم اليقين الأولي)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                covariance[i][j] = if (i == j) {
                    if (i < 2) 100.0 else 10.0  // عدم اليقين في الموضع أكبر من السرعة
                } else {
                    0.0
                }
            }
        }
        
        isInitialized = true
        android.util.Log.d("KalmanFilter", "✅ تم تهيئة Kalman Filter: x=$x, y=$y, vx=${state[2]}, vy=${state[3]}")
    }
    
    /**
     * التنبؤ بموقع الهدف في الإطار التالي
     */
    fun predict(): Pair<Double, Double> {
        if (!isInitialized) {
            return Pair(0.0, 0.0)
        }
        
        // State prediction: x' = F * x
        val predictedState = DoubleArray(4)
        for (i in 0 until 4) {
            predictedState[i] = 0.0
            for (j in 0 until 4) {
                predictedState[i] += F[i][j] * state[j]
            }
        }
        
        // Covariance prediction: P' = F * P * F^T + Q
        val predictedCovariance = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += F[i][k] * covariance[k][j]
                }
                predictedCovariance[i][j] = sum
            }
        }
        
        // إضافة Q
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                predictedCovariance[i][j] += Q[i][j]
            }
        }
        
        state = predictedState
        covariance = predictedCovariance
        
        android.util.Log.d("KalmanFilter", "🔮 التنبؤ: x=${state[0]}, y=${state[1]}, vx=${state[2]}, vy=${state[3]}")
        return Pair(state[0], state[1])
    }
    
    /**
     * تحديث Kalman Filter بقياس جديد
     */
    fun update(measuredX: Double, measuredY: Double) {
        if (!isInitialized) {
            initialize(measuredX, measuredY)
            return
        }
        
        // Measurement vector
        val z = doubleArrayOf(measuredX, measuredY)
        
        // Innovation: y = z - H * x
        val innovation = DoubleArray(2)
        for (i in 0 until 2) {
            var sum = 0.0
            for (j in 0 until 4) {
                sum += H[i][j] * state[j]
            }
            innovation[i] = z[i] - sum
        }
        
        // Innovation covariance: S = H * P * H^T + R
        val S = Array(2) { DoubleArray(2) }
        for (i in 0 until 2) {
            for (j in 0 until 2) {
                var sum = 0.0
                for (k in 0 until 4) {
                    var temp = 0.0
                    for (l in 0 until 4) {
                        temp += H[i][l] * covariance[l][k]
                    }
                    sum += temp * H[j][k]
                }
                S[i][j] = sum + R[i][j]
            }
        }
        
        // Kalman gain: K = P * H^T * S^-1
        val K = Array(4) { DoubleArray(2) }
        val detS = S[0][0] * S[1][1] - S[0][1] * S[1][0]
        if (detS != 0.0) {
            val invS = arrayOf(
                doubleArrayOf(S[1][1] / detS, -S[0][1] / detS),
                doubleArrayOf(-S[1][0] / detS, S[0][0] / detS)
            )
            
            for (i in 0 until 4) {
                for (j in 0 until 2) {
                    var sum = 0.0
                    for (k in 0 until 2) {
                        var temp = 0.0
                        for (l in 0 until 4) {
                            temp += covariance[i][l] * H[k][l]
                        }
                        sum += temp * invS[k][j]
                    }
                    K[i][j] = sum
                }
            }
        }
        
        // State update: x = x + K * y
        val oldX = state[0]
        val oldY = state[1]
        
        for (i in 0 until 4) {
            state[i] += K[i][0] * innovation[0] + K[i][1] * innovation[1]
        }
        
        // حساب السرعة من الفرق بين الموضع الحالي والسابق
        val dx = state[0] - oldX
        val dy = state[1] - oldY
        val dt = 1.0  // Time step (كل إطار)
        
        // تحديث السرعة بشكل ديناميكي (Exponential Moving Average)
        state[2] = state[2] * 0.5 + (dx / dt) * 0.5  // vx
        state[3] = state[3] * 0.5 + (dy / dt) * 0.5  // vy
        
        lastPosition = Pair(state[0], state[1])
        
        // Covariance update: P = (I - K * H) * P
        val IKH = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 2) {
                    sum += K[i][k] * H[k][j]
                }
                IKH[i][j] = if (i == j) 1.0 - sum else -sum
            }
        }
        
        val newCovariance = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += IKH[i][k] * covariance[k][j]
                }
                newCovariance[i][j] = sum
            }
        }
        covariance = newCovariance
        
        android.util.Log.d("KalmanFilter", "🔄 تم تحديث Kalman Filter: x=${state[0]}, y=${state[1]}, vx=${state[2]}, vy=${state[3]}")
    }
    
    /**
     * الحصول على الموضع الحالي (مصفى)
     */
    fun getPosition(): Pair<Double, Double> {
        return Pair(state[0], state[1])
    }
    
    /**
     * الحصول على السرعة الحالية
     */
    fun getVelocity(): Pair<Double, Double> {
        return Pair(state[2], state[3])
    }
    
    /**
     * الحصول على عدم اليقين (uncertainty)
     */
    fun getUncertainty(): Double {
        // جذر مجموع مربعات عناصر القطر الرئيسي
        return kotlin.math.sqrt(covariance[0][0].pow(2) + covariance[1][1].pow(2))
    }
    
    /**
     * إعادة تعيين Kalman Filter
     */
    fun reset() {
        isInitialized = false
        state = DoubleArray(4)
        covariance = Array(4) { DoubleArray(4) }
    }
    
    /**
     * التنبؤ بموقع الهدف في المستقبل (للتخطيط)
     */
    fun predictFuture(steps: Int): Pair<Double, Double> {
        if (!isInitialized) {
            return Pair(0.0, 0.0)
        }
        
        val futureX = state[0] + state[2] * steps
        val futureY = state[1] + state[3] * steps
        
        return Pair(futureX, futureY)
    }
}

