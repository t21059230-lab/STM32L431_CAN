package com.example.canphon.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import com.google.android.gms.tasks.Tasks

/**
 * كاشف الأهداف باستخدام TensorFlow Lite Task API
 * يستخدم نفس الطريقة المستخدمة في المشروع المرجعي (ObjectDetectorHelper)
 * يدعم النماذج التالية:
 * - best.tflite (Best Model - افتراضي)
 * - mobilenetv1.tflite (MobileNetV1 SSD)
 * - simple_image_detector.tflite
 * - efficientdet-lite0.tflite
 * - efficientdet-lite1.tflite
 * - efficientdet-lite2.tflite
 * يدعم أيضاً كشف الألوان (Color Detection) للأجسام الحمراء
 */
class YOLODetector(
    val context: Context,
    var threshold: Float = 0.5f,
    var numThreads: Int = 2,
    var maxResults: Int = 3,
    val objectDetectorListener: DetectorListener? = null
) {
    
    // 🚀 وضع السرعة القصوى - يعطل كل Logging
    private val FAST_MODE = true  // true = أسرع (بدون Log)
    
    // النماذج المتاحة
    enum class ModelType {
        MOBILENET_V1,
        SIMPLE_IMAGE_DETECTOR,
        EFFICIENTDET_LITE0,
        EFFICIENTDET_LITE1,
        EFFICIENTDET_LITE2,
        BEST,
        TANK_NOT_TANK  // نموذج تتبع الدبابات فقط
    }
    
    // استخدام TensorFlow Lite Task API (نفس المشروع المرجعي)
    // For this example this needs to be a var so it can be reset on changes. If the ObjectDetector
    // will not change, a lazy val would be preferable.
    private var objectDetector: ObjectDetector? = null
    private var currentModelType: ModelType? = null
    
    // ML Kit Object Detector (مضمون من Google)
    private val mlKitDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    
    // Color Detector (كشف الأجسام الحمراء باستخدام HSV)
    private val colorDetector = ColorDetector()
    
    // نوع الكشف: "tflite" فقط (للتأكد من اكتشاف الدبابات فقط)
    var detectionMode: String = "tflite"  // يمكن تغييره ديناميكياً
        set(value) {
            // إجبار استخدام tflite فقط لضمان اكتشاف الدبابات فقط
            field = "tflite"
            android.util.Log.d("YOLODetector", "🔧 وضع الكشف مقيد بـ: tflite (للاكتشاف الدبابات فقط)")
        }
    
    // اختيار النموذج (افتراضي: EFFICIENTDET_LITE2 - كشف السيارات)
    var selectedModel: ModelType = ModelType.EFFICIENTDET_LITE2
        set(value) {
            field = value
            clearObjectDetector()
            setupObjectDetector()
        }
    
    // استخدام GPU (0 = CPU, 1 = GPU, 2 = NNAPI)
    var currentDelegate: Int = 0
        set(value) {
            field = value
            clearObjectDetector()
            setupObjectDetector()
        }
    
    init {
        // تحميل النموذج الافتراضي (Best Model - best.tflite)
        setupObjectDetector()
    }
    
    /**
     * تنظيف ObjectDetector (نفس المشروع المرجعي)
     */
    fun clearObjectDetector() {
        objectDetector?.close()
        objectDetector = null
    }
    
    /**
     * تهيئة ObjectDetector باستخدام الإعدادات الحالية (نفس المشروع المرجعي)
     * Initialize the object detector using current settings on the
     * thread that is using it. CPU and NNAPI delegates can be used with detectors
     * that are created on the main thread and used on a background thread, but
     * the GPU delegate needs to be used on the thread that initialized the detector
     */
    fun setupObjectDetector() {
        // Create the base options for the detector using specifies max results and score threshold
        val optionsBuilder =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)

        // Set general detection options, including number of used threads
        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            0 -> { // DELEGATE_CPU
                // Default
            }
            1 -> { // DELEGATE_GPU
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    baseOptionsBuilder.useGpu()
                    android.util.Log.d("YOLODetector", "✅ تم تفعيل GPU")
                } else {
                    objectDetectorListener?.onError("GPU is not supported on this device")
                    android.util.Log.w("YOLODetector", "⚠️ GPU غير مدعوم على هذا الجهاز")
                }
            }
            2 -> { // DELEGATE_NNAPI
                baseOptionsBuilder.useNnapi()
                android.util.Log.d("YOLODetector", "✅ تم تفعيل NNAPI")
            }
        }

        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        val modelFileName =
            when (selectedModel) {
                ModelType.MOBILENET_V1 -> "mobilenetv1.tflite"
                ModelType.SIMPLE_IMAGE_DETECTOR -> "simple_image_detector.tflite"
                ModelType.EFFICIENTDET_LITE0 -> "efficientdet-lite0.tflite"
                ModelType.EFFICIENTDET_LITE1 -> "efficientdet-lite1.tflite"
                ModelType.EFFICIENTDET_LITE2 -> "efficientdet-lite2.tflite"
                ModelType.BEST -> "best.tflite"
                ModelType.TANK_NOT_TANK -> "TankNotTank.tflite"
            }

        try {
            objectDetector =
                ObjectDetector.createFromFileAndOptions(context, modelFileName, optionsBuilder.build())
            currentModelType = selectedModel
            android.util.Log.d("YOLODetector", "✅ تم تحميل $modelFileName بنجاح باستخدام Task API")
        } catch (e: IllegalStateException) {
            objectDetectorListener?.onError(
                "Object detector failed to initialize. See error logs for details"
            )
            android.util.Log.e("YOLODetector", "❌ فشل تحميل النموذج: ${e.message}", e)
            objectDetector = null
            currentModelType = null
        }
    }
    
    /**
     * الكشف عن الأهداف (نفس المشروع المرجعي)
     * @param image: Bitmap - الصورة للكشف
     * @param imageRotation: Int - زاوية دوران الصورة (بالدرجات)
     * @return List<Rect> - قائمة المستطيلات المكتشفة
     * يدعم عدة أوضاع: tflite, mlkit, simple, color
     */
    fun detect(image: Bitmap, imageRotation: Int = 0): List<Rect> {
        // إجبار استخدام TensorFlow Lite فقط (لاكتشاف الدبابات فقط)
        // تعطيل جميع أوضاع الكشف الأخرى
        if (objectDetector == null && detectionMode == "tflite") {
            setupObjectDetector()
        }
        
        if (objectDetector != null && detectionMode == "tflite") {
            try {
                val modelName = when (currentModelType) {
                    ModelType.MOBILENET_V1 -> "MobileNetV1"
                    ModelType.SIMPLE_IMAGE_DETECTOR -> "Simple Image Detector"
                    ModelType.EFFICIENTDET_LITE0 -> "EfficientDet Lite0"
                    ModelType.EFFICIENTDET_LITE1 -> "EfficientDet Lite1"
                    ModelType.EFFICIENTDET_LITE2 -> "EfficientDet Lite2"
                    ModelType.BEST -> "Best Model"
                    ModelType.TANK_NOT_TANK -> "TankNotTank (دبابات فقط)"
                    null -> "Unknown"
                }
                android.util.Log.d("YOLODetector", "🔍 استخدام $modelName للكشف في صورة ${image.width}x${image.height}...")
                
                // Inference time is the difference between the system time at the start and finish of the
                // process
                var inferenceTime = SystemClock.uptimeMillis()

                // Create preprocessor for the image.
                // See https://www.tensorflow.org/lite/inference_with_metadata/
                //            lite_support#imageprocessor_architecture
                val imageProcessor =
                    ImageProcessor.Builder()
                        .add(Rot90Op(-imageRotation / 90))
                        .build()

                // Preprocess the image and convert it into a TensorImage for detection.
                val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

                val results = objectDetector?.detect(tensorImage)
                inferenceTime = SystemClock.uptimeMillis() - inferenceTime
                
                // إرجاع النتائج عبر DetectorListener (نفس المشروع المرجعي)
                objectDetectorListener?.onResults(
                    results,
                    inferenceTime,
                    tensorImage.height,
                    tensorImage.width
                )
                
                android.util.Log.d("YOLODetector", "✅ تم تشغيل النموذج بنجاح. وقت الاستدلال: ${inferenceTime}ms")
                
                if (results != null && results.isNotEmpty()) {
                    android.util.Log.d("YOLODetector", "🔍 النموذج أعاد ${results.size} نتيجة قبل التصفية")
                    // طباعة معلومات عن النتائج للتحقق
                    results.take(3).forEachIndexed { idx, detection ->
                        val cat = detection.categories.firstOrNull()
                        android.util.Log.d("YOLODetector", "  النتيجة #$idx: label='${cat?.label}', index=${cat?.index}, score=${cat?.score}")
                    }
                    
                    // تصفية النتائج - كشف السيارات فقط (COCO class 2 = car)
                    // COCO classes: 0=person, 1=bicycle, 2=car, 3=motorcycle, 4=airplane, 5=bus, 6=train, 7=truck
                    val filteredResults = results.filter { detection ->
                        val category = detection.categories.firstOrNull()
                        val label = category?.label?.lowercase() ?: ""
                        val index = category?.index ?: -1
                        val score = category?.score ?: 0f
                        
                        // فلترة السيارات فقط
                        val isCar = when {
                            // COCO class 2 = car
                            index == 2 -> {
                                android.util.Log.d("YOLODetector", "🚗 سيارة مكتشفة (class 2): score=$score")
                                true
                            }
                            // أو إذا كان label = "car"
                            label == "car" -> {
                                android.util.Log.d("YOLODetector", "🚗 سيارة مكتشفة (label): score=$score")
                                true
                            }
                            // غير ذلك = ليس سيارة → رفض
                            else -> {
                                android.util.Log.d("YOLODetector", "❌ تم رفض: label=$label, index=$index, score=$score")
                                false
                            }
                        }
                        
                        isCar
                    }
                    
                    android.util.Log.d("YOLODetector", "✅ $modelName: تم اكتشاف ${results.size} هدف، ${filteredResults.size} دبابة فقط")
                    if (filteredResults.isEmpty() && results.isNotEmpty()) {
                        android.util.Log.w("YOLODetector", "⚠️ تحذير: جميع النتائج تم رفضها! قد تكون التصفية صارمة جداً")
                        android.util.Log.w("YOLODetector", "💡 اقتراح: تحقق من labels و indices في Logcat أعلاه")
                    }
                    
                    // تحويل Detection إلى Rect (boundingBox هو RectF، نحتاج تحويله إلى Rect)
                    val rects = filteredResults.map { detection ->
                        val rectF = detection.boundingBox
                        Rect(
                            rectF.left.toInt(),
                            rectF.top.toInt(),
                            rectF.right.toInt(),
                            rectF.bottom.toInt()
                        )
                    }
                    
                    android.util.Log.d("YOLODetector", "✅ $modelName: تم اكتشاف ${results.size} هدف، ${filteredResults.size} دبابة فقط")
                    android.util.Log.d("YOLODetector", "📊 أول 5 scores: ${filteredResults.take(5).map { det -> det.categories.firstOrNull()?.let { cat -> "${cat.label}:${cat.score}" } }}")
                    
                    // إرجاع النتائج مباشرة (دبابات فقط)
                    android.util.Log.d("YOLODetector", "📦 إجمالي النتائج: ${rects.size} دبابة")
                    return rects
                } else {
                    android.util.Log.d("YOLODetector", "⚠️ $modelName: لم يتم اكتشاف أهداف من النموذج")
                    return emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("YOLODetector", "❌ خطأ في TensorFlow Lite Task API", e)
                e.printStackTrace()
                // إرجاع قائمة فارغة بدلاً من استخدام بدائل أخرى
                return emptyList()
            }
        } else {
            android.util.Log.w("YOLODetector", "⚠️ TensorFlow Lite Task API غير متاح")
            // إرجاع قائمة فارغة بدلاً من استخدام بدائل أخرى
            return emptyList()
        }
        
        // لا نستخدم ML Kit أو Color Detector أو Simple Detection
        // فقط TensorFlow Lite مع نموذج TankNotTank
        android.util.Log.w("YOLODetector", "⚠️ لم يتم اكتشاف أي دبابات")
        return emptyList()
    }
    
    /**
     * كشف الأهداف وإرجاعها كقائمة RectF
     * مناسب للاستخدام مع TrackingActivity
     */
    fun detectAsRects(image: Bitmap, imageRotation: Int = 0): List<android.graphics.RectF> {
        val rects = detect(image, imageRotation)
        return rects.map { rect ->
            android.graphics.RectF(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat()
            )
        }
    }

    
    /**
     * تنظيف الموارد (نفس المشروع المرجعي)
     */
    fun close() {
        clearObjectDetector()
    }
    
    /**
     * DetectorListener interface (نفس المشروع المرجعي)
     */
    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }
    
    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DELEGATE_NNAPI = 2
        const val MODEL_MOBILENETV1 = 0
        const val MODEL_EFFICIENTDETV0 = 1
        const val MODEL_EFFICIENTDETV1 = 2
        const val MODEL_EFFICIENTDETV2 = 3
    }
}
