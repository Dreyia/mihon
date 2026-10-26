package mihon.feature.translation.core.vision

import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import mihon.feature.translation.core.onnx.Ort
import mihon.feature.translation.core.onnx.Ort.shape
import mihon.feature.translation.core.onnx.Ort.tensor
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Erases text with the AOT inpainting model from manga-image-translator.
 *
 * Model: https://huggingface.co/ogkalu/aot-inpainting (`aot.onnx`, MIT). Inputs `image`
 * `[1,3,H,W]` in -1..1 with the masked pixels zeroed, and `mask` `[1,1,H,W]` with 1 where text is;
 * output `[1,3,H,W]` in -1..1. H and W must be multiples of 8. Pre/post-processing follows
 * `manga_translator/inpainting/inpainting_lama_mpe.py::_infer` in
 * https://github.com/zyddnys/manga-image-translator (GPL-3.0; only the tensor contract is reused).
 *
 * Each text box is inpainted on its own padded neighbourhood (max [MAX_SIDE] px) rather than the
 * whole page: faster, far less memory, and bubbles only need local context.
 */
class AotInpainter(modelFile: File) : Inpainter {

    private val session: OrtSession = Ort.createSession(modelFile)
    private val imageInput: String
    private val maskInput: String

    init {
        val inputs = session.inputInfo.mapValues { (it.value.info as TensorInfo).shape }
        imageInput = inputs.entries.firstOrNull { it.value.getOrNull(1) == 3L }?.key
            ?: inputs.keys.firstOrNull { it in setOf("image", "input_image", "input") }
            ?: inputs.keys.first()
        maskInput = inputs.keys.first { it != imageInput }
    }

    override fun inpaint(image: Bitmap, boxes: List<Rect>): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (group in groupOverlapping(boxes)) {
            val patch = inpaintRegion(out, group) ?: continue
            canvas.drawBitmap(patch.bitmap, patch.region.left.toFloat(), patch.region.top.toFloat(), null)
            patch.bitmap.recycle()
        }
        return out
    }

    private class Patch(val region: Rect, val bitmap: Bitmap)

    private fun inpaintRegion(image: Bitmap, boxes: List<Rect>): Patch? {
        val union = Rect(boxes[0])
        boxes.forEach { union.union(it) }
        val margin = maxOf(MIN_MARGIN, (maxOf(union.width(), union.height()) * MARGIN_RATIO).toInt())
        val region = BitmapOps.clamp(BitmapOps.inflate(union, 0f, margin), image.width, image.height)
        if (region.width() < 8 || region.height() < 8) return null

        // Working resolution: fit in MAX_SIDE, dimensions multiple of 8.
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(region.width(), region.height()))
        val workW = roundUp8((region.width() * scale).roundToInt())
        val workH = roundUp8((region.height() * scale).roundToInt())

        val crop = BitmapOps.crop(image, region)
        val scaled = BitmapOps.scaled(crop, workW, workH)
        crop.recycle()
        val pixels = BitmapOps.pixels(scaled)
        scaled.recycle()

        val mask = FloatArray(workW * workH)
        val sx = workW.toFloat() / region.width()
        val sy = workH.toFloat() / region.height()
        for (box in boxes) {
            val dilated = BitmapOps.inflate(box, DILATE_RATIO, MIN_DILATE)
            val left = ((dilated.left - region.left) * sx).toInt().coerceIn(0, workW)
            val top = ((dilated.top - region.top) * sy).toInt().coerceIn(0, workH)
            val right = ceil((dilated.right - region.left) * sx).toInt().coerceIn(0, workW)
            val bottom = ceil((dilated.bottom - region.top) * sy).toInt().coerceIn(0, workH)
            for (y in top until bottom) {
                for (x in left until right) mask[y * workW + x] = 1f
            }
        }

        val plane = workW * workH
        val imageBuffer = Ort.floatBuffer(3 * plane)
        val maskBuffer = Ort.floatBuffer(plane)
        for (i in 0 until plane) {
            val keep = 1f - mask[i]
            val p = pixels[i]
            imageBuffer.put(i, ((p shr 16 and 0xFF) / 127.5f - 1f) * keep)
            imageBuffer.put(plane + i, ((p shr 8 and 0xFF) / 127.5f - 1f) * keep)
            imageBuffer.put(2 * plane + i, ((p and 0xFF) / 127.5f - 1f) * keep)
            maskBuffer.put(i, mask[i])
        }

        val imageTensor = Ort.floatTensor(imageBuffer, 1, 3, workH.toLong(), workW.toLong())
        val maskTensor = Ort.floatTensor(maskBuffer, 1, 1, workH.toLong(), workW.toLong())
        val result = try {
            session.run(mapOf(imageInput to imageTensor, maskInput to maskTensor)).use { res ->
                val output = res.tensor(0)
                val outShape = output.shape
                require(outShape.size == 4 && outShape[2].toInt() == workH && outShape[3].toInt() == workW) {
                    "Unexpected inpainting output shape ${outShape.contentToString()}"
                }
                val buffer = output.floatBuffer
                IntArray(plane) { i ->
                    if (mask[i] == 0f) {
                        pixels[i]
                    } else {
                        val r = ((buffer.get(i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
                        val g = ((buffer.get(plane + i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
                        val b = ((buffer.get(2 * plane + i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        } finally {
            imageTensor.close()
            maskTensor.close()
        }

        val small = BitmapOps.fromPixels(result, workW, workH)
        val patch = if (workW == region.width() && workH == region.height()) {
            small
        } else {
            BitmapOps.scaled(small, region.width(), region.height()).also { small.recycle() }
        }
        // Outside the mask keep the original pixels so the resize never blurs untouched art.
        restoreUnmasked(patch, image, region, boxes)
        return Patch(region, patch)
    }

    private fun restoreUnmasked(patch: Bitmap, original: Bitmap, region: Rect, boxes: List<Rect>) {
        val w = region.width()
        val h = region.height()
        val out = BitmapOps.pixels(patch)
        val src = IntArray(w * h)
        original.getPixels(src, 0, w, region.left, region.top, w, h)
        val masked = BooleanArray(w * h)
        for (box in boxes) {
            val d = BitmapOps.clamp(
                BitmapOps.inflate(box, DILATE_RATIO, MIN_DILATE).apply { offset(-region.left, -region.top) },
                w,
                h,
            )
            for (y in d.top until d.bottom) for (x in d.left until d.right) masked[y * w + x] = true
        }
        for (i in out.indices) if (!masked[i]) out[i] = src[i]
        patch.setPixels(out, 0, w, 0, 0, w, h)
    }

    /** Boxes whose dilated bounds touch are inpainted together so seams never cross a bubble. */
    private fun groupOverlapping(boxes: List<Rect>): List<List<Rect>> {
        val groups = ArrayList<MutableList<Rect>>()
        for (box in boxes) {
            val grown = BitmapOps.inflate(box, MARGIN_RATIO, MIN_MARGIN)
            val hit = groups.firstOrNull { g -> g.any { Rect.intersects(BitmapOps.inflate(it, MARGIN_RATIO, MIN_MARGIN), grown) } }
            if (hit != null) hit += box else groups += mutableListOf(box)
        }
        return groups
    }

    private fun roundUp8(v: Int): Int = ((v + 7) / 8 * 8).coerceAtLeast(8)

    override fun close() = session.close()

    companion object {
        private const val MAX_SIDE = 512
        private const val MARGIN_RATIO = 0.35f
        private const val MIN_MARGIN = 48
        private const val DILATE_RATIO = 0.06f
        private const val MIN_DILATE = 3
    }
}
