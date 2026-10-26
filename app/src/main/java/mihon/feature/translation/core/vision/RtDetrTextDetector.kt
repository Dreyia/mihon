package mihon.feature.translation.core.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Rect
import mihon.feature.translation.core.onnx.Ort
import mihon.feature.translation.core.onnx.Ort.shape
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Text and speech-bubble detector.
 *
 * Model: https://huggingface.co/ogkalu/comic-text-and-bubble-detector (RT-DETRv2, 640x640 input,
 * classes 0=bubble, 1=text_bubble, 2=text_free).
 *
 * Input contract (confirmed against the int8 export used by other Android ports): the image is
 * squashed to 640x640, RGB, scaled to 0..1 with no mean/std normalisation
 * (`do_normalize: false` in the model's preprocessor_config.json), plus an int64 `[1,2]` tensor
 * holding the original `[width, height]` so the model returns boxes in page coordinates.
 */
class RtDetrTextDetector(
    modelFile: File,
    private val threshold: Float = 0.5f,
) {
    private val session: OrtSession = Ort.createSession(modelFile)
    private val imageInput: String
    private val sizesInput: String?

    init {
        val inputs = session.inputInfo
        imageInput = inputs.entries.firstOrNull { (it.value.info as? TensorInfo)?.shape?.size == 4 }?.key
            ?: inputs.keys.first()
        sizesInput = inputs.keys.firstOrNull { it != imageInput }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = BitmapOps.scaled(bitmap, INPUT_SIZE, INPUT_SIZE)
        val pixels = BitmapOps.pixels(resized)
        resized.recycle()

        val buffer = Ort.floatBuffer(3 * INPUT_SIZE * INPUT_SIZE)
        BitmapOps.writeNchw(pixels, buffer) { it / 255f }

        val feeds = HashMap<String, OnnxTensor>()
        feeds[imageInput] = Ort.floatTensor(buffer, 1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        sizesInput?.let {
            feeds[it] = Ort.longTensor(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()), 1, 2)
        }
        try {
            session.run(feeds).use { result ->
                return dedupe(parse(result, bitmap.width, bitmap.height))
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    private fun parse(result: OrtSession.Result, width: Int, height: Int): List<Detection> {
        var labels: OnnxTensor? = null
        var boxes: OnnxTensor? = null
        var scoresOrLogits: OnnxTensor? = null
        for (entry in result) {
            val tensor = entry.value as? OnnxTensor ?: continue
            val info = tensor.info
            when {
                info.type == OnnxJavaType.INT64 -> labels = tensor
                info.shape.size == 3 && info.shape.last() == 4L -> boxes = tensor
                else -> scoresOrLogits = tensor
            }
        }
        val boxTensor = boxes ?: return emptyList()
        val scoreTensor = scoresOrLogits ?: return emptyList()
        val count = boxTensor.shape[1].toInt()
        val boxBuffer = boxTensor.floatBuffer
        val scoreBuffer = scoreTensor.floatBuffer
        val detections = ArrayList<Detection>()

        if (labels != null) {
            // Export with built-in post-processing: labels [1,N] int64, boxes [1,N,4] xyxy pixels, scores [1,N].
            val labelBuffer = labels.longBuffer
            for (i in 0 until count) {
                val score = scoreBuffer.get(i)
                if (score.isNaN() || score < threshold) continue
                val rect = Rect(
                    boxBuffer.get(i * 4).roundToInt(),
                    boxBuffer.get(i * 4 + 1).roundToInt(),
                    boxBuffer.get(i * 4 + 2).roundToInt(),
                    boxBuffer.get(i * 4 + 3).roundToInt(),
                )
                addIfValid(detections, labelBuffer.get(i).toInt(), score, rect, width, height)
            }
        } else {
            // Raw export: logits [1,N,C] and normalised cx,cy,w,h boxes in 0..1.
            val classes = scoreTensor.shape.last().toInt()
            for (i in 0 until count) {
                var best = 0
                var bestLogit = Float.NEGATIVE_INFINITY
                for (c in 0 until classes) {
                    val v = scoreBuffer.get(i * classes + c)
                    if (v > bestLogit) {
                        bestLogit = v
                        best = c
                    }
                }
                val score = 1f / (1f + exp(-bestLogit))
                if (score < threshold) continue
                val cx = boxBuffer.get(i * 4) * width
                val cy = boxBuffer.get(i * 4 + 1) * height
                val w = boxBuffer.get(i * 4 + 2) * width
                val h = boxBuffer.get(i * 4 + 3) * height
                val rect = Rect(
                    (cx - w / 2).roundToInt(),
                    (cy - h / 2).roundToInt(),
                    (cx + w / 2).roundToInt(),
                    (cy + h / 2).roundToInt(),
                )
                addIfValid(detections, best, score, rect, width, height)
            }
        }
        return detections
    }

    private fun addIfValid(out: MutableList<Detection>, label: Int, score: Float, rect: Rect, width: Int, height: Int) {
        val clamped = BitmapOps.clamp(rect, width, height)
        if (clamped.width() < MIN_BOX_PX || clamped.height() < MIN_BOX_PX) return
        out += Detection(label, score, clamped)
    }

    /** Drops lower-scored boxes that overlap a kept box of the same label. */
    private fun dedupe(detections: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>()
        for (det in detections.sortedByDescending { it.score }) {
            val duplicate = kept.any { it.label == det.label && BitmapOps.iou(it.box, det.box) > NMS_IOU }
            if (!duplicate) kept += det
        }
        return kept
    }

    fun close() = session.close()

    companion object {
        const val INPUT_SIZE = 640
        private const val MIN_BOX_PX = 6
        private const val NMS_IOU = 0.6f
    }
}
