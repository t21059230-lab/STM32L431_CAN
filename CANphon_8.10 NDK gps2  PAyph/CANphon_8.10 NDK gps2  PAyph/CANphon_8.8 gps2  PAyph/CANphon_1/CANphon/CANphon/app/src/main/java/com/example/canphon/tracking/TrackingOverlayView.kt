package com.example.canphon.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.LinkedList
import kotlin.math.max

/**
 * TrackingOverlayView - HUD تكتيكي مطابق للصورة المرجعية
 * يرسم جميع عناصر الواجهة فوق الكاميرا
 */
class TrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== HUD Data (يتم تحديثها من Activity) ====================
    // Top Bar Data
    var modeDisplay: String = "MARIN"      // MD:MARIN
    var trackType: String = "SHIP"         // TR:SHIP
    var cfValue: Int = 7                    // CF:07
    var tnValue: Int = 70                   // TN:70
    var detStatus: String = "OFF"          // DET:OFF
    var standStatus: String = "STAND"      // STAND (الوسط الكبير)
    var trackStatus: String = "OFF"        // TRK:OFF
    var rsStatus: String = "NONE"          // RS:NONE
    var gmStatus: String = "IDLE"          // GM:IDLE
    var motorStatus: String = "ON"         // MOTOR:ON—

    // Center HUD Data
    var focusStatus: String = ""           // مؤشر التركيز التلقائي
    var yawValue: Float = 0f               // القيمة على المقياس الأفقي
    var pitchValue: Float = 0f             // القيمة على المقياس العمودي
    var digitalReadout1: String = "00.00"  // الصندوق الرقمي الأول
    var digitalReadout2: String = "00.05"  // الصندوق الرقمي الثاني

    // Bottom Left Block
    var ftValue: Int = 20                  // FT:0020
    var xPos: Int = 6500                   // X:6500
    var yPos: Int = 900                    // Y:0900
    var zPos: Int = 200                    // Z:0200
    var mtTemp: Float = 32.00f             // MT:32.00°
    var gtTemp: Float = 32.00f             // GT:32.00°

    // Bottom Right Block
    var fovValue: Int = 21                 // FOV:21
    var txValue: Int = 20                  // TX:0020
    var twValue: Int = 20                  // TW:0020
    var tyValue: Int = 20                  // TY:0020
    var thValue: Int = 20                  // TH:0020

    // Bottom Status Line
    var voltageValue: String = "07.00"     // ⚡:07.00
    var stStatus: String = "X"             // ST:X
    var tCounter: Int = 5                  // T:00005
    var gmArrows: Boolean = true           // GM ⟷ 5K
    var skArrows: Boolean = true           // SK ⟷ OC
    var fiveKValue: String = "5K"

    // ==================== Paint Objects ====================
    private val hudGreenColor = Color.parseColor("#00FF00")  // أخضر HUD
    private val hudDarkGreen = Color.parseColor("#009900")   // أخضر داكن
    private val hudYellow = Color.parseColor("#FFFF00")      // أصفر للتحذيرات
    private val hudRed = Color.parseColor("#FF0000")         // أحمر للأخطاء

    // النص الأساسي
    private val textPaint = Paint().apply {
        color = hudGreenColor
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    // النص الكبير (STAND)
    private val largeTextPaint = Paint().apply {
        color = hudGreenColor
        textSize = 48f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // النص الصغير
    private val smallTextPaint = Paint().apply {
        color = hudGreenColor
        textSize = 25f // تم التعديل إلى 25
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    // الخطوط والأشكال
    private val linePaint = Paint().apply {
        color = hudGreenColor
        style = Paint.Style.STROKE
        strokeWidth = 1.8f // تم التعديل إلى 1.8
        isAntiAlias = true
    }

    // صندوق رقمي
    private val boxPaint = Paint().apply {
        color = hudGreenColor
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // خلفية الصندوق
    private val boxFillPaint = Paint().apply {
        color = Color.argb(180, 0, 40, 0)  // أخضر داكن شبه شفاف
        style = Paint.Style.FILL
    }

    // Crosshair paint
    private val crosshairPaint = Paint().apply {
        color = hudGreenColor
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // مثلث المؤشر
    private val trianglePaint = Paint().apply {
        color = hudGreenColor
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ==================== Tracking Data (للتوافق مع الكود القديم) ====================
    private var trackingRects: List<Rect> = emptyList()
    private var detectionResults: List<Detection> = LinkedList<Detection>()
    private var scaleFactor: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var isTracking = false
    private var showCrosshair = true

    init {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = VISIBLE
        setWillNotDraw(false)
    }

    // ==================== Public Methods (للتوافق) ====================
    fun updateTrackingRects(rects: List<Rect>) {
        android.util.Log.d("TrackingOverlayView", "📦 تحديث المستطيلات: ${rects.size} مستطيل")
        if (rects.isNotEmpty()) {
            android.util.Log.d("TrackingOverlayView", "📦 أول مستطيل: ${rects[0]}, imageSize=${imageWidth}x${imageHeight}")
        }
        trackingRects = rects
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    fun setTrackingMode(tracking: Boolean) {
        isTracking = tracking
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    fun setShowCrosshair(show: Boolean) {
        showCrosshair = show
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    fun setResults(detectionResults: MutableList<Detection>, imageHeight: Int, imageWidth: Int) {
        this.detectionResults = detectionResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        calculateScaleFactor()
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    fun setImageDimensions(width: Int, height: Int) {
        android.util.Log.d("TrackingOverlayView", "📐 تحديث أبعاد الصورة: ${width}x${height}")
        this.imageWidth = width
        this.imageHeight = height
        calculateScaleFactor()
        android.util.Log.d("TrackingOverlayView", "📐 scaleFactor=$scaleFactor, offsetX=$offsetX, offsetY=$offsetY")
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    fun clear() {
        detectionResults = LinkedList()
        trackingRects = emptyList()
        invalidate()  // استخدام invalidate() مباشرة للسرعة الفائقة
    }

    // دالة لتحديث جميع بيانات HUD مرة واحدة
    fun updateHUDData(
        mode: String = this.modeDisplay,
        track: String = this.trackType,
        cf: Int = this.cfValue,
        tn: Int = this.tnValue,
        det: String = this.detStatus,
        stand: String = this.standStatus,
        trk: String = this.trackStatus,
        rs: String = this.rsStatus,
        gm: String = this.gmStatus,
        motor: String = this.motorStatus,
        yaw: Float = this.yawValue,
        pitch: Float = this.pitchValue,
        ft: Int = this.ftValue,
        x: Int = this.xPos,
        y: Int = this.yPos,
        z: Int = this.zPos,
        mt: Float = this.mtTemp,
        gt: Float = this.gtTemp,
        fov: Int = this.fovValue,
        tx: Int = this.txValue,
        tw: Int = this.twValue,
        ty: Int = this.tyValue,
        th: Int = this.thValue,
        voltage: String = this.voltageValue,
        st: String = this.stStatus,
        t: Int = this.tCounter
    ) {
        this.modeDisplay = mode
        this.trackType = track
        this.cfValue = cf
        this.tnValue = tn
        this.detStatus = det
        this.standStatus = stand
        this.trackStatus = trk
        this.rsStatus = rs
        this.gmStatus = gm
        this.motorStatus = motor
        this.yawValue = yaw
        this.pitchValue = pitch
        this.ftValue = ft
        this.xPos = x
        this.yPos = y
        this.zPos = z
        this.mtTemp = mt
        this.gtTemp = gt
        this.fovValue = fov
        this.txValue = tx
        this.twValue = tw
        this.tyValue = ty
        this.thValue = th
        this.voltageValue = voltage
        this.stStatus = st
        this.tCounter = t
        // استخدام invalidate() مباشرة للسرعة الفائقة (بدلاً من postInvalidate())
        invalidate()
    }

    private fun calculateScaleFactor() {
        if (imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0) {
            val viewAspect = width.toFloat() / height.toFloat()
            val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()

            if (viewAspect > imageAspect) {
                scaleFactor = height.toFloat() / imageHeight.toFloat()
                offsetX = (width - imageWidth * scaleFactor) / 2f
                offsetY = 0f
            } else {
                scaleFactor = width.toFloat() / imageWidth.toFloat()
                offsetX = 0f
                offsetY = (height - imageHeight * scaleFactor) / 2f
            }
        }
    }

    // ==================== Drawing ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2
        val centerY = h / 2

        // 1. رسم الشريط العلوي
        drawTopBar(canvas, w, h)

        // 2. رسم المقياس الأفقي
        drawHorizontalScale(canvas, centerX, h)

        // 3. رسم المقياس العمودي
        drawVerticalScale(canvas, h)

        // 4. رسم Crosshair المركزي
        if (showCrosshair) {
            drawCrosshair(canvas, centerX, centerY)
        }

        // 5. رسم الشريط السفلي الأيمن
        drawBottomRightBlock(canvas, w, h)

        // 6. رسم شريط الحالة السفلي
        drawBottomStatusLine(canvas, w, h)

        // 7. رسم مستطيلات التتبع (آخر شيء ليكون فوق كل شيء)
        drawTrackingRects(canvas)
    }

    private fun drawTopBar(canvas: Canvas, w: Float, h: Float) {
        val topY = 40f
        val line2Y = topY + 45f
        val innerSpacing = 150f

        // -----------------------------------------------------
        // حساب مواقع TR و TRK أولاً لتحديد عرض "المقصورة"
        // -----------------------------------------------------
        val trText = "TR:$trackType"
        val trWidth = textPaint.measureText(trText)
        val trX = w / 2 - innerSpacing - trWidth // بداية TR (يسار)

        val trkText = "TRK:$trackStatus"
        val trkWidth = textPaint.measureText(trkText)
        val trkX = w / 2 + innerSpacing          // بداية TRK (يمين)
        val trkEnd = trkX + trkWidth             // نهاية TRK

        // -----------------------------------------------------
        // رسم خلفية "المقصورة" (Cockpit Design)
        // -----------------------------------------------------
        val cockpitPaint = Paint().apply {
            color = Color.BLACK // أسود
            style = Paint.Style.FILL
            alpha = 90 // شفافية 90
        }
        val thinBorderPaint = Paint().apply {
            color = hudGreenColor
            style = Paint.Style.STROKE
            strokeWidth = 0.5f // تم التعديل إلى 0.5
            isAntiAlias = true
        }
        val centerBorderPaint = Paint().apply {
            color = hudGreenColor
            style = Paint.Style.STROKE
            strokeWidth = 2.0f // يبقى سميكاً
            isAntiAlias = true
        }

        val baseHeight = 55f
        val centerDepth = 110f
        val slopeWidth = 50f
        
        val padding = 20f
        val leftDipX = trX - padding
        val rightDipX = trkEnd + padding

        // مسار التعبئة (مغلق)
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, baseHeight)
        
        val slopeRightStart = rightDipX + slopeWidth
        path.lineTo(slopeRightStart, baseHeight)
        path.lineTo(rightDipX, centerDepth)
        path.lineTo(leftDipX, centerDepth)
        
        val slopeLeftStart = leftDipX - slopeWidth
        path.lineTo(slopeLeftStart, baseHeight)
        path.lineTo(0f, baseHeight)
        path.close()

        canvas.drawPath(path, cockpitPaint)

        // رسم الحدود (مقسمة)
        
        // 1. اليسار (نحيف 0.5)
        val leftPath = Path()
        leftPath.moveTo(0f, baseHeight)
        leftPath.lineTo(slopeLeftStart, baseHeight)
        leftPath.lineTo(leftDipX, centerDepth)
        canvas.drawPath(leftPath, thinBorderPaint)

        // 2. المنتصف (عريض 2.0)
        canvas.drawLine(leftDipX, centerDepth, rightDipX, centerDepth, centerBorderPaint)

        // 3. اليمين (نحيف 0.5)
        val rightPath = Path()
        rightPath.moveTo(rightDipX, centerDepth)
        rightPath.lineTo(slopeRightStart, baseHeight)
        rightPath.lineTo(w, baseHeight)
        canvas.drawPath(rightPath, thinBorderPaint)

        // -----------------------------------------------------
        // رسم النصوص
        // -----------------------------------------------------

        // الجانب الأيسر: MD
        var leftX = 20f
        val mdText = "MD:$modeDisplay"
        val mdWidth = textPaint.measureText(mdText)
        canvas.drawText(mdText, leftX, topY, textPaint)
        val mdEnd = leftX + mdWidth
        
        // الجانب الأيمن: MOTOR
        val motorText = "MOTOR:$motorStatus"
        val motorWidth = textPaint.measureText(motorText)
        val motorX = w - motorWidth - 20f
        canvas.drawText(motorText, motorX, topY, textPaint)

        // الوسط: CF
        val cfText = "CF:%02d".format(cfValue)
        val cfWidth = textPaint.measureText(cfText)
        val oldGmX = w / 2 - 220f - 80f 
        val cfX = (mdEnd + oldGmX) / 2 - cfWidth / 2
        canvas.drawText(cfText, cfX, topY, textPaint)

        // الوسط: RS
        val rsText = "RS:$rsStatus"
        val rsWidth = textPaint.measureText(rsText)
        val rsX = (trkEnd + motorX) / 2 - rsWidth / 2
        canvas.drawText(rsText, rsX, topY, textPaint)

        // السطر الثاني: TR, STAND, TRK
        canvas.drawText(trText, trX, line2Y, textPaint)
        
        // STAND
        val standWidth = largeTextPaint.measureText(standStatus)
        val standX = w / 2 - standWidth / 2
        canvas.drawText(standStatus, standX, line2Y, largeTextPaint)

        canvas.drawText(trkText, trkX, line2Y, textPaint)

        // رسم مؤشر التركيز (AF-LOCK) - يظهر فقط عند التفعيل
        if (focusStatus.isNotEmpty()) {
            val afPaint = Paint().apply {
                color = hudGreenColor
                textSize = 30f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
                // تأثير وميض بسيط (اختياري)
                if (System.currentTimeMillis() % 1000 < 500) {
                    alpha = 255
                } else {
                    alpha = 100
                }
            }
            val afWidth = afPaint.measureText(focusStatus)
            // نضعه تحت كلمة STAND في المنتصف
            canvas.drawText(focusStatus, w / 2 - afWidth / 2, baseHeight + 100f, afPaint)
        }
    }

    private fun drawHorizontalScale(canvas: Canvas, centerX: Float, h: Float) {
        val scaleY = 180f  // تم إنزالها أكثر (كانت 150f)
        val scaleWidth = 300f
        val tickHeight = 10f
        val majorTickHeight = 15f

        // رسم الخط الأفقي الرئيسي
        canvas.drawLine(centerX - scaleWidth, scaleY, centerX + scaleWidth, scaleY, linePaint)

        // رسم التدريجات من -15 إلى 15 (فوق الخط)
        for (i in -15..15) {
            val x = centerX + (i * (scaleWidth / 15f))
            val isMajor = i % 5 == 0
            val tickH = if (isMajor) majorTickHeight else tickHeight

            // التدريجات فوق الخط (كما كانت)
            canvas.drawLine(x, scaleY - tickH, x, scaleY, linePaint)

            // رسم الأرقام فوق التدريجات
            if (isMajor) {
                val numText = i.toString()
                val textWidth = smallTextPaint.measureText(numText)
                canvas.drawText(numText, x - textWidth / 2, scaleY - majorTickHeight - 5f, smallTextPaint)  // فوق التدريجات
            }
        }

        // رسم المثلث المؤشر (يتحرك مع yawValue) - تحت الخط
        // yawValue يأتي من -15 إلى +15
        // موجب = يمين، سالب = يسار
        val clampedYaw = yawValue.coerceIn(-15f, 15f)
        val indicatorX = centerX + (clampedYaw * (scaleWidth / 15f))
        val trianglePath = Path().apply {
            moveTo(indicatorX, scaleY + 5f)  // تحت الخط
            lineTo(indicatorX - 8f, scaleY + 20f)
            lineTo(indicatorX + 8f, scaleY + 20f)
            close()
        }
        canvas.drawPath(trianglePath, trianglePaint)
        
        // صندوق القيمة الرقمية (أكبر مع 3 خانات عشرية)
        val boxWidth = 75f
        val boxHeight = 30f
        val boxX = indicatorX - boxWidth / 2
        val boxY = scaleY + 25f
        
        canvas.drawRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, boxFillPaint)
        // canvas.drawRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, boxPaint) // تم إزالة الإطار بناءً على الطلب
        
        // عرض قيمة Yaw (0.000)
        val yawText = String.format("%.3f", yawValue)
        val textWidth = smallTextPaint.measureText(yawText)
        canvas.drawText(yawText, boxX + (boxWidth - textWidth) / 2, boxY + 21f, smallTextPaint)
    }

    private fun drawVerticalScale(canvas: Canvas, h: Float) {
        val scaleX = 80f  // تم سحبها للداخل قليلاً لإظهار القيم
        val centerY = h / 2
        val scaleHeight = 300f  // نفس طول مسطرة Yaw
        val tickHeight = 10f
        val majorTickHeight = 15f

        // رسم الخط العمودي
        canvas.drawLine(scaleX, centerY - scaleHeight, scaleX, centerY + scaleHeight, linePaint)

        // رسم التدريجات من -15 إلى 15 (على يسار الخط - مثل الأفقية)
        for (i in -15..15) {
            val y = centerY - (i * (scaleHeight / 15f))
            val isMajor = i % 5 == 0
            val tickW = if (isMajor) majorTickHeight else tickHeight

            // التدريجات على يسار الخط (مثل الأفقية - فوق الخط)
            canvas.drawLine(scaleX - tickW, y, scaleX, y, linePaint)

            // رسم الأرقام فوق/يسار التدريجات (مثل الأفقية)
            if (isMajor) {
                val numText = i.toString()
                val textWidth = smallTextPaint.measureText(numText)
                canvas.drawText(numText, scaleX - tickW - textWidth - 5f, y + 8f, smallTextPaint)
            }
        }

        // رسم المثلث المؤشر (يتحرك مع pitchValue) - على يمين الخط
        // pitchValue يأتي من -15 إلى +15
        // موجب = أعلى، سالب = أسفل
        val clampedPitch = pitchValue.coerceIn(-15f, 15f)
        val indicatorY = centerY - (clampedPitch * (scaleHeight / 15f))
        
        // مثلث يشير لليسار (نحو الخط) - مثل الأفقية
        val trianglePath = Path().apply {
            moveTo(scaleX + 5f, indicatorY)  // رأس المثلث يشير لليسار
            lineTo(scaleX + 20f, indicatorY - 8f)
            lineTo(scaleX + 20f, indicatorY + 8f)
            close()
        }
        canvas.drawPath(trianglePath, trianglePaint)
        
        // صندوق القيمة الرقمية (مطابق لصندوق Yaw - أكبر مع 3 خانات عشرية)
        val boxWidth = 75f
        val boxHeight = 30f
        val boxX = scaleX + 25f
        val boxY = indicatorY - boxHeight / 2
        
        canvas.drawRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, boxFillPaint)
        
        // عرض قيمة Pitch
        val pitchText = String.format("%.3f", pitchValue)
        val textWidth = smallTextPaint.measureText(pitchText)
        canvas.drawText(pitchText, boxX + (boxWidth - textWidth) / 2, boxY + 21f, smallTextPaint)
    }

    private fun drawCrosshair(canvas: Canvas, centerX: Float, centerY: Float) {
        val size = 67f  // زيادة طفيفة جداً (كان 65f)
        val innerGap = 38f  // الفراغ الداخلي ثابت

        // المربع المركزي مع أقواس L في الزوايا
        val rectSize = 43f  // تكبير طفيف (كان 40f)

        // أعلى يسار
        canvas.drawLine(centerX - rectSize, centerY - rectSize, centerX - rectSize, centerY - rectSize + 22f, crosshairPaint)
        canvas.drawLine(centerX - rectSize, centerY - rectSize, centerX - rectSize + 22f, centerY - rectSize, crosshairPaint)

        // أعلى يمين
        canvas.drawLine(centerX + rectSize, centerY - rectSize, centerX + rectSize, centerY - rectSize + 22f, crosshairPaint)
        canvas.drawLine(centerX + rectSize, centerY - rectSize, centerX + rectSize - 22f, centerY - rectSize, crosshairPaint)

        // أسفل يسار
        canvas.drawLine(centerX - rectSize, centerY + rectSize, centerX - rectSize, centerY + rectSize - 22f, crosshairPaint)
        canvas.drawLine(centerX - rectSize, centerY + rectSize, centerX - rectSize + 22f, centerY + rectSize, crosshairPaint)

        // أسفل يمين
        canvas.drawLine(centerX + rectSize, centerY + rectSize, centerX + rectSize, centerY + rectSize - 22f, crosshairPaint)
        canvas.drawLine(centerX + rectSize, centerY + rectSize, centerX + rectSize - 22f, centerY + rectSize, crosshairPaint)

        // خطوط التصويب الأربعة
        // من الأعلى
        canvas.drawLine(centerX, centerY - size, centerX, centerY - innerGap, crosshairPaint)
        // من الأسفل
        canvas.drawLine(centerX, centerY + innerGap, centerX, centerY + size, crosshairPaint)
        // من اليسار
        canvas.drawLine(centerX - size, centerY, centerX - innerGap, centerY, crosshairPaint)
        // من اليمين
        canvas.drawLine(centerX + innerGap, centerY, centerX + size, centerY, crosshairPaint)

        // تم إلغاء النقطة المركزية
    }


    private fun drawBottomRightBlock(canvas: Canvas, w: Float, h: Float) {
        val startY = h - 130f
        val lineHeight = 28f

        // TX - محاذاة لليمين + خلفية
        val txText = "TX:%04d".format(txValue)
        val txWidth = textPaint.measureText(txText)
        val txX = w - txWidth - 20f
        val txBgPaint = Paint().apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val txY = startY + lineHeight * 0.2f
        val txBgY = txY - 32f
        val txBgHeight = 45f
        canvas.drawRect(txX - 5f, txBgY, w - 10f, txBgY + txBgHeight, txBgPaint)
        canvas.drawText(txText, txX, txY, textPaint)

        // TY - محاذاة لليمين + خلفية
        val tyText = "TY:%04d".format(tyValue)
        val tyWidth = textPaint.measureText(tyText)
        val tyX = w - tyWidth - 20f
        val tyBgPaint = Paint().apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val tyY = startY + lineHeight * 2
        val tyBgY = tyY - 32f
        val tyBgHeight = 45f
        canvas.drawRect(tyX - 5f, tyBgY, w - 10f, tyBgY + tyBgHeight, tyBgPaint)
        canvas.drawText(tyText, tyX, tyY, textPaint)
    }

    private fun drawBottomStatusLine(canvas: Canvas, w: Float, h: Float) {
        val y = h - 25f
        val gap = 25f  // مسافة ثابتة بين العناصر
        var x = 20f  // نبدأ من اليسار
        
        // -----------------------------------------------------
        // رسم الخلفية شبه الشفافة للشريط السفلي (أسود شفاف)
        // -----------------------------------------------------
        val bgY = y - 30f  // من -30 بكسل
        val bgPadding = 10f
        val blackBgPaint = Paint().apply {
            color = Color.argb(90, 0, 0, 0)  // أسود شفاف (شفافية 90)
            style = Paint.Style.FILL
        }
        canvas.drawRect(bgPadding, bgY, w - bgPadding, h, blackBgPaint)  // إلى آخر الشاشة

        // خطوط خضراء (أعلى فقط) بسماكة 0.5
        val bottomBorderPaint = Paint().apply {
            color = hudGreenColor
            style = Paint.Style.STROKE
            strokeWidth = 0.5f 
            isAntiAlias = true
        }
        canvas.drawLine(bgPadding, bgY, w - bgPadding, bgY, bottomBorderPaint) // خط علوي فقط

        // TG - درجة الحرارة (فوق T) + خلفية
        val tgText = "TG:%.2f°".format(gtTemp)
        val tgWidth = textPaint.measureText(tgText)
        val tgBgPaint = Paint().apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val tgY = y - 49f
        val tgBgY = tgY - 32f
        val tgBgHeight = 45f
        canvas.drawRect(x - 5f, tgBgY, x + tgWidth + 5f, tgBgY + tgBgHeight, tgBgPaint)
        canvas.drawText(tgText, x, tgY, textPaint)
        
        // T counter
        val tText = "T:%05d".format(tCounter)
        canvas.drawText(tText, x, y, textPaint)
        x += textPaint.measureText(tText) + gap

        // SK arrows - في الزاوية اليمنى السفلى
        val skText = "SK ⟷ OC"
        val skWidth = textPaint.measureText(skText)
        val skX = w - skWidth - 20f  // محاذاة لليمين مع هامش 20f
        canvas.drawText(skText, skX, y, textPaint)
        
        // -----------------------------------------------------
        // وضع TH و TW في الوسط
        // -----------------------------------------------------
        val thText = "TH:%04d".format(thValue)
        val thWidth = textPaint.measureText(thText)
        val twText = "TW:%04d".format(twValue)
        val twWidth = textPaint.measureText(twText)
        
        // TH و TW في منتصف الشاشة
        val thTwTotalWidth = thWidth + 15f + twWidth
        val thTwCenterX = w / 2 - thTwTotalWidth / 2
        
        val thX = thTwCenterX
        canvas.drawText(thText, thX, y, textPaint)
        
        val twX = thX + thWidth + 15f
        canvas.drawText(twText, twX, y, textPaint)
        
        // -----------------------------------------------------
        // أيقونة الكاميرا + FOV
        // -----------------------------------------------------
        val twEnd = twX + twWidth
        
        // FOV في المنتصف بين TW و SK (عرض كقيمة عشرية)
        val fovText = "FOV:%.1f".format(fovValue / 100f)
        val fovWidth = textPaint.measureText(fovText)
        val fovX = (twEnd + skX) / 2 - fovWidth / 2
        canvas.drawText(fovText, fovX, y, textPaint)
        
        // الكاميرا في المنتصف بين TW و FOV
        val camText = "[●]"
        val camWidth = textPaint.measureText(camText)
        val camX = (twEnd + fovX) / 2 - camWidth / 2
        canvas.drawText(camText, camX, y, textPaint)
        
        // -----------------------------------------------------
        // وضع FT بين T و TH
        // -----------------------------------------------------
        val leftEnd = x  // نهاية T
        
        val ftText = "FT:%04d".format(ftValue)
        val ftWidth = textPaint.measureText(ftText)
        val ftX = (leftEnd + thX) / 2 - ftWidth / 2
        canvas.drawText(ftText, ftX, y, textPaint)
    }

    private fun drawTrackingRects(canvas: Canvas) {
        if (trackingRects.isEmpty()) {
            android.util.Log.d("TrackingOverlayView", "⚠️ لا توجد مستطيلات للرسم")
            return
        }

        android.util.Log.d("TrackingOverlayView", "🎨 رسم ${trackingRects.size} مستطيل, isTracking=$isTracking, scaleFactor=$scaleFactor")

        val trackingPaint = Paint().apply {
            color = if (isTracking) hudYellow else hudGreenColor  // أصفر للتتبع، أخضر للبحث
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        trackingRects.forEachIndexed { index, rect ->
            val scaledLeft = rect.left * scaleFactor + offsetX
            val scaledTop = rect.top * scaleFactor + offsetY
            val scaledRight = rect.right * scaleFactor + offsetX
            val scaledBottom = rect.bottom * scaleFactor + offsetY

            android.util.Log.d("TrackingOverlayView", "🎨 مستطيل #$index: rect=$rect, scaled=($scaledLeft, $scaledTop, $scaledRight, $scaledBottom)")

            canvas.drawRect(scaledLeft, scaledTop, scaledRight, scaledBottom, trackingPaint)

            // رسم أقواس الزوايا
            val cornerLength = 15f
            // أعلى يسار
            canvas.drawLine(scaledLeft, scaledTop, scaledLeft + cornerLength, scaledTop, trackingPaint)
            canvas.drawLine(scaledLeft, scaledTop, scaledLeft, scaledTop + cornerLength, trackingPaint)
            // أعلى يمين
            canvas.drawLine(scaledRight, scaledTop, scaledRight - cornerLength, scaledTop, trackingPaint)
            canvas.drawLine(scaledRight, scaledTop, scaledRight, scaledTop + cornerLength, trackingPaint)
            // أسفل يسار
            canvas.drawLine(scaledLeft, scaledBottom, scaledLeft + cornerLength, scaledBottom, trackingPaint)
            canvas.drawLine(scaledLeft, scaledBottom, scaledLeft, scaledBottom - cornerLength, trackingPaint)
            // أسفل يمين
            canvas.drawLine(scaledRight, scaledBottom, scaledRight - cornerLength, scaledBottom, trackingPaint)
            canvas.drawLine(scaledRight, scaledBottom, scaledRight, scaledBottom - cornerLength, trackingPaint)
        }
    }

    // ==================== Deprecated Methods (للتوافق) ====================
    fun setSoTextPositions(sX: Float, sY: Float, xX: Float, xY: Float, oX: Float, oY: Float) {
        // Deprecated - kept for compatibility
    }

    fun setO2scValue(value: Int) {
        // Deprecated - kept for compatibility
    }

    fun setShowSoLine(show: Boolean) {
        // Deprecated - kept for compatibility
    }
}
