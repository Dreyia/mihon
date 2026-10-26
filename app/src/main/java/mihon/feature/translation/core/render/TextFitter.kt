package mihon.feature.translation.core.render

import android.graphics.Paint

data class FittedText(val textSize: Float, val lines: List<String>) {
    fun height(lineSpacing: Float): Float = lines.size * textSize * lineSpacing
}

/**
 * Picks the largest font size at which [text] fits inside a box, and the line breaks to use.
 */
object TextFitter {

    fun fit(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        minSize: Float,
        maxSize: Float,
        lineSpacing: Float = LINE_SPACING,
    ): FittedText {
        var lo = minSize
        var hi = maxOf(minSize, maxSize)
        var best = layout(paint, text, maxWidth, lo)
        for (i in 0 until ITERATIONS) {
            val mid = (lo + hi) / 2f
            val candidate = layout(paint, text, maxWidth, mid)
            if (fits(paint, candidate, maxWidth, maxHeight, lineSpacing)) {
                best = candidate
                lo = mid
            } else {
                hi = mid
            }
            if (hi - lo < 0.5f) break
        }
        return best
    }

    private fun layout(paint: Paint, text: String, maxWidth: Float, size: Float): FittedText {
        paint.textSize = size
        return FittedText(size, wrap(paint, text, maxWidth))
    }

    private fun fits(paint: Paint, fitted: FittedText, maxWidth: Float, maxHeight: Float, lineSpacing: Float): Boolean {
        if (fitted.height(lineSpacing) > maxHeight) return false
        paint.textSize = fitted.textSize
        return fitted.lines.all { paint.measureText(it) <= maxWidth }
    }

    /**
     * Greedy word wrap at the paint's current text size. Words wider than the box are broken by
     * character; text without spaces (CJK) is wrapped per character.
     */
    fun wrap(paint: Paint, text: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                    continue
                }
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                if (paint.measureText(word) <= maxWidth) {
                    current.append(word)
                } else {
                    // Break the long word by code point.
                    var piece = StringBuilder()
                    var i = 0
                    while (i < word.length) {
                        val cp = word.codePointAt(i)
                        val chars = Character.toChars(cp)
                        val next = piece.toString() + String(chars)
                        if (piece.isNotEmpty() && paint.measureText(next) > maxWidth) {
                            lines += piece.toString()
                            piece = StringBuilder()
                        }
                        piece.append(chars)
                        i += chars.size
                    }
                    current = piece
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines
    }

    const val LINE_SPACING = 1.12f
    private const val ITERATIONS = 12
}
