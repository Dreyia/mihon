package mihon.feature.translation.core.vision

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * One box found by the detector, in original page pixel coordinates.
 */
data class Detection(
    val label: Int,
    val score: Float,
    val box: Rect,
) {
    val isText: Boolean get() = label == LABEL_TEXT_BUBBLE || label == LABEL_TEXT_FREE

    companion object {
        // Class ids from https://huggingface.co/ogkalu/comic-text-and-bubble-detector
        const val LABEL_BUBBLE = 0
        const val LABEL_TEXT_BUBBLE = 1
        const val LABEL_TEXT_FREE = 2
    }
}

/** Recognises the text inside one cropped text block. */
interface TextRecognizer {
    /** @return recognised text, or an empty string when nothing readable was found. */
    fun recognize(crop: Bitmap): String

    fun close()
}

/** Removes the original text from the given boxes and returns a new bitmap. */
interface Inpainter {
    fun inpaint(image: Bitmap, boxes: List<Rect>): Bitmap

    fun close()
}
