package mihon.feature.translation.core.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class CtcDecoderTest {

    private val decoder = CtcDecoder(listOf("a", "b", "c"))

    @Test
    fun `collapses repeats and drops blanks`() {
        val (text, _) = decoder.decode(intArrayOf(0, 1, 1, 0, 2, 2, 2, 0, 1))
        assertEquals("aba", text)
    }

    @Test
    fun `last class is a space`() {
        val (text, _) = decoder.decode(intArrayOf(1, 4, 2))
        assertEquals("a b", text)
    }

    @Test
    fun `argmax over probability rows`() {
        // 3 steps x 5 classes (blank, a, b, c, space)
        val probs = floatArrayOf(
            0.1f, 0.8f, 0.05f, 0.05f, 0f,
            0.9f, 0.1f, 0f, 0f, 0f,
            0f, 0f, 0f, 0.7f, 0.3f,
        )
        val (text, confidence) = decoder.decode(FloatBuffer.wrap(probs), steps = 3, classes = 5)
        assertEquals("ac", text)
        assertEquals(0.75f, confidence, 1e-6f)
    }
}
