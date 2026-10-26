package mihon.feature.translation.core.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Splits a text block into single lines for a line-based recogniser (PP-OCR).
 *
 * Uses ink projection profiles, which is enough for the clean, high-contrast lettering found in
 * speech bubbles. Blocks that are much taller than wide are treated as vertical CJK text: each
 * column is cut into characters that are laid out side by side into one synthetic horizontal line.
 *
 * ASSUMPTION — to verify on real pages: the tall/wide ratio and gap heuristics below were chosen by
 * reasoning, not measured against a dataset.
 */
object LineSegmenter {

    private class Run(val start: Int, val end: Int) {
        val length get() = end - start
    }

    fun segment(block: Bitmap): List<Bitmap> {
        val width = block.width
        val height = block.height
        if (width < 4 || height < 4) return listOf(copy(block))

        val pixels = BitmapOps.pixels(block)
        val luma = IntArray(pixels.size) { BitmapOps.luma(pixels[it]) }
        val threshold = otsu(luma)
        val lightBackground = luma.count { it > threshold } >= luma.size / 2
        val ink = BooleanArray(luma.size) { if (lightBackground) luma[it] <= threshold else luma[it] > threshold }
        val background = if (lightBackground) WHITE else BLACK

        val vertical = height > width * VERTICAL_RATIO
        return if (vertical) {
            segmentVertical(block, ink, width, height, background)
        } else {
            segmentHorizontal(block, ink, width, height)
        }
    }

    private fun segmentHorizontal(block: Bitmap, ink: BooleanArray, width: Int, height: Int): List<Bitmap> {
        val rows = IntArray(height) { y -> (0 until width).count { x -> ink[y * width + x] } }
        val runs = runs(rows, minInk = maxOf(1, width / 50), minLength = 3)
        if (runs.size <= 1) return listOf(copy(block))
        return runs.map { run ->
            val pad = maxOf(1, run.length / 6)
            BitmapOps.crop(block, Rect(0, run.start - pad, width, run.end + pad))
        }
    }

    private fun segmentVertical(block: Bitmap, ink: BooleanArray, width: Int, height: Int, background: Int): List<Bitmap> {
        val cols = IntArray(width) { x -> (0 until height).count { y -> ink[y * width + x] } }
        val columns = runs(cols, minInk = maxOf(1, height / 50), minLength = 3)
        if (columns.isEmpty()) return listOf(copy(block))
        // Vertical CJK text is read right to left.
        return columns.reversed().map { column ->
            val pad = maxOf(1, column.length / 6)
            val colRect = BitmapOps.clamp(Rect(column.start - pad, 0, column.end + pad, height), width, height)
            val rows = IntArray(height) { y ->
                (colRect.left until colRect.right).count { x -> ink[y * width + x] }
            }
            val cells = runs(rows, minInk = 1, minLength = 2)
            if (cells.size <= 1) {
                BitmapOps.crop(block, colRect)
            } else {
                stitch(block, colRect, cells, background)
            }
        }
    }

    /** Lays the character cells of one column out left to right as a horizontal line. */
    private fun stitch(block: Bitmap, column: Rect, cells: List<Run>, background: Int): Bitmap {
        val cellSize = PaddleOcr.HEIGHT
        val gap = cellSize / 8
        val out = Bitmap.createBitmap(cells.size * (cellSize + gap) + gap, cellSize + gap * 2, Bitmap.Config.ARGB_8888)
        out.eraseColor(background)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var x = gap
        for (cell in cells) {
            val pad = maxOf(1, cell.length / 8)
            val src = BitmapOps.clamp(
                Rect(column.left, cell.start - pad, column.right, cell.end + pad),
                block.width,
                block.height,
            )
            val scale = cellSize.toFloat() / maxOf(src.width(), src.height())
            val w = src.width() * scale
            val h = src.height() * scale
            val left = x + (cellSize - w) / 2
            val top = gap + (cellSize - h) / 2
            canvas.drawBitmap(block, src, RectF(left, top, left + w, top + h), paint)
            x += cellSize + gap
        }
        return out
    }

    /** Groups consecutive indices whose ink count exceeds [minInk]; closes small gaps. */
    private fun runs(profile: IntArray, minInk: Int, minLength: Int): List<Run> {
        val raw = ArrayList<Run>()
        var start = -1
        for (i in profile.indices) {
            val on = profile[i] >= minInk
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                raw += Run(start, i)
                start = -1
            }
        }
        if (start >= 0) raw += Run(start, profile.size)
        if (raw.isEmpty()) return raw

        // Merge runs separated by gaps smaller than a fraction of the typical run length
        // (handles the small breaks inside a glyph).
        val typical = raw.map { it.length }.sorted()[raw.size / 2].coerceAtLeast(1)
        val merged = ArrayList<Run>()
        for (run in raw) {
            val last = merged.lastOrNull()
            if (last != null && run.start - last.end < typical * MERGE_GAP_RATIO) {
                merged[merged.size - 1] = Run(last.start, run.end)
            } else {
                merged += run
            }
        }
        return merged.filter { it.length >= minLength }
    }

    private fun otsu(luma: IntArray): Int {
        val hist = IntArray(256)
        for (v in luma) hist[v]++
        val total = luma.size
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0L
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun copy(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private const val VERTICAL_RATIO = 1.3f
    private const val MERGE_GAP_RATIO = 0.35f
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
}
