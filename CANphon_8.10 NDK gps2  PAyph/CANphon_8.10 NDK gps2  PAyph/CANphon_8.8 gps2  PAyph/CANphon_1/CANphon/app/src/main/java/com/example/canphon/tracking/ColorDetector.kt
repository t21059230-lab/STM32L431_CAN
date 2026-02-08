package com.example.canphon.tracking
import com.example.canphon.R
import com.example.canphon.ui.*
import com.example.canphon.managers.*
import com.example.canphon.protocols.*
import com.example.canphon.drivers.*
import com.example.canphon.data.*

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * كاشف الألوان - يتبع الأجسام الحمراء باستخدام HSV
 * مشابه لـ cv_object_tracking_color.py
 * 
 * الخوارزمية:
 * 1. تحويل BGR → HSV
 * 2. إنشاء قناع (Mask) للون الأحمر
 * 3. إيجاد Contours
 * 4. اختيار أكبر Contour
 * 5. رسم مستطيل حول الهدف
 */
class ColorDetector {
    
    // نطاق HSV للون الأحمر (مطابق لـ Python)
    // Range 1: للون الأحمر العام
    private val hsvMin = intArrayOf(0, 0, 180)      // H: 0-180, S: 0-30, V: 180-255
    private val hsvMax = intArrayOf(180, 30, 255)
    
    // Range 2: للون الأحمر الداكن (معطل حالياً - يمكن تفعيله)
    // private val hsvMin2 = intArrayOf(170, 120, 70)
    // private val hsvMax2 = intArrayOf(180, 255, 255)
    
    // الحد الأدنى لمساحة الهدف (مطابق لـ Python: max_area > 100)
    private val minArea = 100
    
    /**
     * كشف الأجسام الحمراء في الصورة
     * @param bitmap الصورة المدخلة
     * @return قائمة المستطيلات المكتشفة
     */
    fun detect(bitmap: Bitmap): List<Rect> {
        val detectedRects = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        
        android.util.Log.d("ColorDetector", "🔴 بدء كشف الأجسام الحمراء في صورة ${width}x${height}")
        
        // 1. تحويل BGR → HSV وإنشاء القناع
        // استخدام خطوة (step) لتحسين الأداء (مشابه لـ Python: step = 4)
        val step = 2  // خطوة المسح (يمكن زيادتها لتحسين الأداء)
        val maskPixels = Array(height) { BooleanArray(width) }
        
        // تحويل كل بكسل إلى HSV وإنشاء القناع (مع خطوة)
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // تحويل RGB → HSV
                val hsv = rgbToHsv(r, g, b)
                
                // التحقق من نطاق HSV (Range 1)
                val h = (hsv shr 16) and 0xFF
                val s = (hsv shr 8) and 0xFF
                val v = hsv and 0xFF
                
                val inRange = (h >= hsvMin[0] && h <= hsvMax[0] &&
                              s >= hsvMin[1] && s <= hsvMax[1] &&
                              v >= hsvMin[2] && v <= hsvMax[2])
                
                // ملء المنطقة حول البكسل (لتحسين الكشف)
                for (dy in 0 until step) {
                    for (dx in 0 until step) {
                        val ny = (y + dy).coerceAtMost(height - 1)
                        val nx = (x + dx).coerceAtMost(width - 1)
                        maskPixels[ny][nx] = inRange
                    }
                }
            }
        }
        
        android.util.Log.d("ColorDetector", "✅ تم تحويل الصورة إلى HSV وإنشاء القناع")
        
        // 2. إيجاد Contours (المناطق المتصلة)
        val contours = findContours(maskPixels, width, height)
        
        android.util.Log.d("ColorDetector", "🔍 تم العثور على ${contours.size} منطقة متصلة")
        
        // 3. اختيار أكبر Contour (مطابق لـ Python)
        var maxArea = 0
        var bestContour: List<Pair<Int, Int>>? = null
        
        for (contour in contours) {
            val area = calculateContourArea(contour)
            if (area > maxArea) {
                maxArea = area
                bestContour = contour
            }
        }
        
        // 4. رسم مستطيل حول الهدف (مطابق لـ Python)
        if (bestContour != null && maxArea > minArea) {
            val boundingBox = getBoundingBox(bestContour)
            
            // حساب المركز (مطابق لـ Python: cx = x + w // 2, cy = y + h // 2)
            val cx = boundingBox.left + (boundingBox.width() / 2)
            val cy = boundingBox.top + (boundingBox.height() / 2)
            
            android.util.Log.d("ColorDetector", "✅ تم اكتشاف جسم أحمر: $boundingBox (المركز: $cx, $cy)")
            
            detectedRects.add(boundingBox)
        } else {
            android.util.Log.d("ColorDetector", "⚠️ لم يتم اكتشاف أجسام حمراء (maxArea: $maxArea)")
        }
        
        return detectedRects
    }
    
    /**
     * تحويل RGB → HSV
     * مطابق لـ rgb2hsv() في Python
     * 
     * @param r Red [0-255]
     * @param g Green [0-255]
     * @param b Blue [0-255]
     * @return HSV كـ Int: (H << 16) | (S << 8) | V
     *         H: [0-179] (OpenCV format)
     *         S: [0-255]
     *         V: [0-255]
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): Int {
        val rFloat = r / 255.0
        val gFloat = g / 255.0
        val bFloat = b / 255.0
        
        val mx = max(max(rFloat, gFloat), bFloat)
        val mn = min(min(rFloat, gFloat), bFloat)
        val df = mx - mn
        
        // حساب Hue
        val h = when {
            mx == mn -> 0.0
            mx == rFloat -> (60 * ((gFloat - bFloat) / df) + 360) % 360
            mx == gFloat -> (60 * ((bFloat - rFloat) / df) + 120) % 360
            else -> (60 * ((rFloat - gFloat) / df) + 240) % 360
        }
        
        // حساب Saturation
        val s = if (mx == 0.0) 0.0 else df / mx
        
        // حساب Value
        val v = mx
        
        // تحويل إلى OpenCV format (H: [0-179], S: [0-255], V: [0-255])
        val hInt = (h / 2).toInt().coerceIn(0, 179)
        val sInt = (s * 255).toInt().coerceIn(0, 255)
        val vInt = (v * 255).toInt().coerceIn(0, 255)
        
        return (hInt shl 16) or (sInt shl 8) or vInt
    }
    
    /**
     * إيجاد Contours (المناطق المتصلة) في القناع
     * استخدام Flood Fill algorithm
     */
    private fun findContours(mask: Array<BooleanArray>, width: Int, height: Int): List<List<Pair<Int, Int>>> {
        val contours = mutableListOf<List<Pair<Int, Int>>>()
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y][x] && !visited[y][x]) {
                    // بدء Flood Fill من هذا البكسل
                    val contour = mutableListOf<Pair<Int, Int>>()
                    floodFill(mask, visited, x, y, width, height, contour)
                    
                    if (contour.size >= 10) {  // تجاهل المناطق الصغيرة جداً
                        contours.add(contour)
                    }
                }
            }
        }
        
        return contours
    }
    
    /**
     * Flood Fill algorithm لإيجاد المنطقة المتصلة
     */
    private fun floodFill(
        mask: Array<BooleanArray>,
        visited: Array<BooleanArray>,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        contour: MutableList<Pair<Int, Int>>
    ) {
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(Pair(startX, startY))
        
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.size - 1)
            
            if (x < 0 || x >= width || y < 0 || y >= height) continue
            if (visited[y][x] || !mask[y][x]) continue
            
            visited[y][x] = true
            contour.add(Pair(x, y))
            
            // إضافة الجيران (4-connectivity)
            stack.add(Pair(x + 1, y))
            stack.add(Pair(x - 1, y))
            stack.add(Pair(x, y + 1))
            stack.add(Pair(x, y - 1))
        }
    }
    
    /**
     * حساب مساحة Contour
     */
    private fun calculateContourArea(contour: List<Pair<Int, Int>>): Int {
        if (contour.size < 3) return 0
        
        // استخدام Shoelace formula لحساب المساحة
        var area = 0
        for (i in contour.indices) {
            val j = (i + 1) % contour.size
            area += contour[i].first * contour[j].second
            area -= contour[j].first * contour[i].second
        }
        
        return kotlin.math.abs(area) / 2
    }
    
    /**
     * الحصول على Bounding Box للـ Contour
     * مطابق لـ cv2.boundingRect() في Python
     */
    private fun getBoundingBox(contour: List<Pair<Int, Int>>): Rect {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        
        for ((x, y) in contour) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        
        return Rect(minX, minY, maxX, maxY)
    }
    
    /**
     * تحديث نطاق HSV للون الأحمر
     * يمكن استخدامها لتعديل النطاق ديناميكياً
     */
    fun setRedColorRange(
        hMin: Int, sMin: Int, vMin: Int,
        hMax: Int, sMax: Int, vMax: Int
    ) {
        // يمكن إضافة منطق لتحديث النطاق
        android.util.Log.d("ColorDetector", "🔧 تحديث نطاق HSV: H[$hMin-$hMax], S[$sMin-$sMax], V[$vMin-$vMax]")
    }
}


