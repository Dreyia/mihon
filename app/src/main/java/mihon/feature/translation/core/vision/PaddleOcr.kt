package mihon.feature.translation.core.vision

import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import mihon.feature.translation.core.onnx.Ort
import mihon.feature.translation.core.onnx.Ort.shape
import mihon.feature.translation.core.onnx.Ort.tensor
import java.io.File
import kotlin.math.roundToInt

/**
 * Chinese (and Latin) text-line recogniser.
 *
 * Model: https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx. Input `x` is `[1,3,48,W]`
 * normalised to -1..1; output is `[1,T,C]` CTC probabilities where class 0 is blank, classes 1..N
 * index the dictionary from `inference.yml` and the last class is a space.
 *
 * The model reads one horizontal line. [LineSegmenter] splits a text block into lines (or turns
 * vertical columns into synthetic lines) before calling [recognize].
 */
class PaddleOcr(
    modelFile: File,
    dictionary: List<String>,
) : TextRecognizer {

    private val session: OrtSession = Ort.createSession(modelFile)
    private val inputName = session.inputNames.first()
    private val decoder = CtcDecoder(dictionary)

    override fun recognize(crop: Bitmap): String {
        val lines = LineSegmenter.segment(crop)
        val parts = lines.mapNotNull { line ->
            val (text, confidence) = recognizeLine(line)
            line.recycle()
            text.takeIf { it.isNotBlank() && confidence >= MIN_CONFIDENCE }
        }
        return parts.joinToString("")
    }

    /** Recognises a single horizontal line. */
    fun recognizeLine(line: Bitmap): Pair<String, Float> {
        val width = targetWidth(line.width, line.height)
        val resized = BitmapOps.scaled(line, width, HEIGHT)
        val pixels = BitmapOps.pixels(resized)
        resized.recycle()
        val buffer = Ort.floatBuffer(3 * HEIGHT * width)
        BitmapOps.writeNchw(pixels, buffer) { (it / 255f - 0.5f) / 0.5f }

        Ort.floatTensor(buffer, 1, 3, HEIGHT.toLong(), width.toLong()).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val output = result.tensor(0)
                val steps = output.shape[1].toInt()
                val classes = output.shape[2].toInt()
                return decoder.decode(output.floatBuffer, steps, classes)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val HEIGHT = 48
        private const val MIN_WIDTH = 16
        private const val MAX_WIDTH = 1600
        private const val MIN_CONFIDENCE = 0.3f

        fun targetWidth(width: Int, height: Int): Int =
            (HEIGHT.toDouble() * width.coerceAtLeast(1) / height.coerceAtLeast(1)).roundToInt()
                .coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}
