package mihon.feature.translation.core.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.translation.core.PageTranslator
import mihon.feature.translation.core.TextTranslator
import mihon.feature.translation.core.TranslationConfig
import mihon.feature.translation.core.TranslationException
import mihon.feature.translation.core.render.BubbleTextRenderer
import mihon.feature.translation.core.vision.BitmapOps
import mihon.feature.translation.core.vision.Detection
import mihon.feature.translation.core.vision.Inpainter
import mihon.feature.translation.core.vision.RtDetrTextDetector
import mihon.feature.translation.core.vision.TextRecognizer
import java.io.ByteArrayOutputStream

/**
 * Full on-device pipeline: detect text boxes -> OCR each box -> translate -> erase -> draw.
 */
class OnDevicePageTranslator(
    private val detector: RtDetrTextDetector,
    private val recognizer: TextRecognizer,
    private val translator: TextTranslator,
    private val inpainter: Inpainter,
    private val config: TranslationConfig,
    private val renderer: BubbleTextRenderer = BubbleTextRenderer(config.fontScale),
) : PageTranslator {

    private class Region(val detection: Detection, val text: String)

    override suspend fun translate(imageBytes: ByteArray): ByteArray? {
        val page = withContext(Dispatchers.Default) { decode(imageBytes) }
        try {
            val detections = withContext(Dispatchers.Default) { detector.detect(page) }
            val bubbles = detections.filter { it.label == Detection.LABEL_BUBBLE }
            val textBoxes = detections.filter {
                it.label == Detection.LABEL_TEXT_BUBBLE ||
                    (config.translateFreeText && it.label == Detection.LABEL_TEXT_FREE)
            }
            if (textBoxes.isEmpty()) return null

            val regions = withContext(Dispatchers.Default) {
                textBoxes.mapNotNull { det ->
                    val crop = BitmapOps.crop(page, BitmapOps.inflate(det.box, OCR_MARGIN, 2))
                    val text = try {
                        recognizer.recognize(crop)
                    } finally {
                        crop.recycle()
                    }
                    text.trim().takeIf { it.isNotEmpty() }?.let { Region(det, it) }
                }
            }
            if (regions.isEmpty()) return null

            val translations = translator.translate(
                regions.map { it.text },
                config.sourceLanguage,
                config.targetLanguage,
            )
            require(translations.size == regions.size) {
                "Translator returned ${translations.size} lines for ${regions.size} inputs"
            }

            return withContext(Dispatchers.Default) {
                val cleaned = inpainter.inpaint(page, regions.map { it.detection.box })
                try {
                    val canvas = Canvas(cleaned)
                    for ((region, translated) in regions.zip(translations)) {
                        if (translated.isBlank()) continue
                        val area = layoutArea(region.detection, bubbles, cleaned.width, cleaned.height)
                        renderer.draw(
                            canvas = canvas,
                            area = area,
                            text = translated,
                            darkText = isLight(cleaned, area),
                            outline = region.detection.label == Detection.LABEL_TEXT_FREE,
                        )
                    }
                    encode(cleaned)
                } finally {
                    cleaned.recycle()
                }
            }
        } finally {
            page.recycle()
        }
    }

    /**
     * Where the translation may be drawn. Text inside a detected bubble can use most of the bubble,
     * which matters because English usually needs more room than the Japanese it replaces.
     */
    private fun layoutArea(text: Detection, bubbles: List<Detection>, width: Int, height: Int): Rect {
        val bubble = bubbles
            .filter { BitmapOps.containment(text.box, it.box) >= BUBBLE_CONTAINMENT }
            .minByOrNull { it.box.width().toLong() * it.box.height() }
        val area = if (bubble != null) {
            val inner = BitmapOps.inflate(bubble.box, -BUBBLE_INSET, 0)
            // Never shrink below the original text box.
            inner.union(text.box)
            inner
        } else {
            BitmapOps.inflate(text.box, FREE_TEXT_GROW, 2)
        }
        return BitmapOps.clamp(area, width, height)
    }

    private fun isLight(bitmap: Bitmap, area: Rect): Boolean {
        val step = maxOf(1, minOf(area.width(), area.height()) / 16)
        var sum = 0L
        var count = 0
        var y = area.top
        while (y < area.bottom) {
            var x = area.left
            while (x < area.right) {
                sum += BitmapOps.luma(bitmap.getPixel(x, y))
                count++
                x += step
            }
            y += step
        }
        return count == 0 || sum / count > LIGHT_THRESHOLD
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        var sample = 1
        while (pixels / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
            inMutable = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw TranslationException("Could not decode page image")
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    override fun close() {
        detector.close()
        recognizer.close()
        translator.close()
        inpainter.close()
    }

    private companion object {
        const val OCR_MARGIN = 0.04f
        const val BUBBLE_CONTAINMENT = 0.8f
        const val BUBBLE_INSET = 0.12f
        const val FREE_TEXT_GROW = 0.15f
        const val LIGHT_THRESHOLD = 140
        const val JPEG_QUALITY = 92

        /** Pages above this are decoded at a lower sample size to bound memory (~128 MB ARGB). */
        const val MAX_PIXELS = 32_000_000L
    }
}
