package mihon.feature.translation.core.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.FloatBuffer

/**
 * Bitmap helpers shared by the model wrappers.
 */
object BitmapOps {

    fun scaled(src: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), paint)
        return out
    }

    /** Crops [rect] clamped to the bitmap bounds. Always returns a new bitmap. */
    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val r = clamp(rect, src.width, src.height)
        return Bitmap.createBitmap(src, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
    }

    fun clamp(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = rect.top.coerceIn(0, (height - 1).coerceAtLeast(0))
        val right = rect.right.coerceIn(left + 1, width.coerceAtLeast(left + 1))
        val bottom = rect.bottom.coerceIn(top + 1, height.coerceAtLeast(top + 1))
        return Rect(left, top, right, bottom)
    }

    /** Grows [rect] by [fraction] of its size on every side, with at least [minPixels]. */
    fun inflate(rect: Rect, fraction: Float, minPixels: Int = 0): Rect {
        val dx = maxOf((rect.width() * fraction).toInt(), minPixels)
        val dy = maxOf((rect.height() * fraction).toInt(), minPixels)
        return Rect(rect.left - dx, rect.top - dy, rect.right + dx, rect.bottom + dy)
    }

    fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    fun fromPixels(pixels: IntArray, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /** Luma 0..255 of an ARGB pixel. */
    fun luma(pixel: Int): Int {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Writes [pixels] as a planar NCHW RGB tensor (batch 1) into [out] starting at [offset].
     * [normalize] maps a 0..255 channel value to the model's input range.
     */
    fun writeNchw(
        pixels: IntArray,
        out: FloatBuffer,
        offset: Int = 0,
        normalize: (Int) -> Float,
    ) {
        val plane = pixels.size
        for (i in pixels.indices) {
            val p = pixels[i]
            out.put(offset + i, normalize(p shr 16 and 0xFF))
            out.put(offset + plane + i, normalize(p shr 8 and 0xFF))
            out.put(offset + 2 * plane + i, normalize(p and 0xFF))
        }
    }

    fun iou(a: Rect, b: Rect): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    /** Fraction of [inner]'s area that lies inside [outer]. */
    fun containment(inner: Rect, outer: Rect): Float {
        val ix = maxOf(0, minOf(inner.right, outer.right) - maxOf(inner.left, outer.left))
        val iy = maxOf(0, minOf(inner.bottom, outer.bottom) - maxOf(inner.top, outer.top))
        val area = inner.width().toLong() * inner.height()
        if (area == 0L) return 0f
        return (ix.toLong() * iy).toFloat() / area
    }
}
