package mihon.feature.translation.core.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Model-free text eraser: fills each box with the median colour of the ring just outside it.
 * Good enough inside plain speech bubbles; used when the inpainting pack is not installed.
 */
class FlatFillInpainter : Inpainter {

    override fun inpaint(image: Bitmap, boxes: List<Rect>): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint()
        for (box in boxes) {
            val fill = BitmapOps.clamp(BitmapOps.inflate(box, 0.05f, 2), out.width, out.height)
            paint.color = ringMedian(image, fill)
            canvas.drawRect(fill, paint)
        }
        return out
    }

    private fun ringMedian(image: Bitmap, rect: Rect): Int {
        val ring = BitmapOps.clamp(BitmapOps.inflate(rect, 0f, RING), image.width, image.height)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        val row = IntArray(ring.width())
        for (y in ring.top until ring.bottom) {
            image.getPixels(row, 0, ring.width(), ring.left, y, ring.width(), 1)
            for (x in 0 until ring.width()) {
                val px = ring.left + x
                if (rect.contains(px, y)) continue
                val p = row[x]
                reds += p shr 16 and 0xFF
                greens += p shr 8 and 0xFF
                blues += p and 0xFF
            }
        }
        if (reds.isEmpty()) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or (median(reds) shl 16) or (median(greens) shl 8) or median(blues)
    }

    private fun median(values: MutableList<Int>): Int {
        values.sort()
        return values[values.size / 2]
    }

    override fun close() = Unit

    private companion object {
        const val RING = 4
    }
}
