package mihon.feature.translation.core.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Draws translated text centred inside a box, sized to fit.
 */
class BubbleTextRenderer(
    private val fontScale: Float = 1f,
    typeface: Typeface? = null,
) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * @param area box the text must stay inside, in bitmap pixels.
     * @param darkText `true` for black text on a light background.
     * @param outline draw a contrasting outline (used for text sitting on artwork).
     */
    fun draw(canvas: Canvas, area: Rect, text: String, darkText: Boolean, outline: Boolean) {
        if (text.isBlank() || area.width() < 4 || area.height() < 4) return
        val padding = (minOf(area.width(), area.height()) * PADDING_RATIO).coerceAtLeast(1f)
        val maxWidth = area.width() - padding * 2
        val maxHeight = area.height() - padding * 2
        if (maxWidth <= 0 || maxHeight <= 0) return

        val maxSize = (maxHeight / 1.2f).coerceAtMost(area.width() / 2f) * fontScale
        val fitted = TextFitter.fit(
            paint = fill,
            text = text,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            minSize = MIN_TEXT_SIZE,
            maxSize = maxOf(MIN_TEXT_SIZE, maxSize),
        )
        fill.textSize = fitted.textSize
        fill.color = if (darkText) Color.BLACK else Color.WHITE
        stroke.textSize = fitted.textSize
        stroke.color = if (darkText) Color.WHITE else Color.BLACK
        stroke.strokeWidth = (fitted.textSize * OUTLINE_RATIO).coerceAtLeast(1.5f)

        val lineHeight = fitted.textSize * TextFitter.LINE_SPACING
        val blockHeight = fitted.lines.size * lineHeight
        val metrics = fill.fontMetrics
        var baseline = area.exactCenterY() - blockHeight / 2f + lineHeight / 2f - (metrics.ascent + metrics.descent) / 2f
        val x = area.exactCenterX()
        for (line in fitted.lines) {
            if (outline) canvas.drawText(line, x, baseline, stroke)
            canvas.drawText(line, x, baseline, fill)
            baseline += lineHeight
        }
    }

    private companion object {
        const val PADDING_RATIO = 0.06f
        const val MIN_TEXT_SIZE = 9f
        const val OUTLINE_RATIO = 0.12f
    }
}
