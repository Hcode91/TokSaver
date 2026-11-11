package com.hcode91.toksaver

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Settings for "only keep frames with people". */
data class PersonFilter(
    val enabled: Boolean = false,
    val minConfidence: Float = 0.5f,   // 0..1
    val minAreaPercent: Int = 10,      // person box must cover at least this % of the frame
)

/**
 * On-device person detector (EfficientDet-Lite0, COCO "person" class).
 * Not thread-safe: use from one thread and close() when finished.
 */
class PersonDetector(context: Context, private val filter: PersonFilter) : AutoCloseable {

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf("person"))
            .setScoreThreshold(filter.minConfidence)
            .setMaxResults(10)
            .build(),
    )

    /** True if the frame contains at least one person big enough to count. */
    fun hasPerson(bmp: Bitmap): Boolean {
        // MediaPipe needs ARGB_8888.
        val input = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val result = try {
            detector.detect(BitmapImageBuilder(input).build())
        } finally {
            if (input !== bmp) input.recycle()
        }
        val frameArea = bmp.width.toFloat() * bmp.height.toFloat()
        val minArea = frameArea * filter.minAreaPercent / 100f
        return result.detections().any { d ->
            val score = d.categories().firstOrNull()?.score() ?: 0f
            val box = d.boundingBox()
            score >= filter.minConfidence && box.width() * box.height() >= minArea
        }
    }

    override fun close() = detector.close()
}
