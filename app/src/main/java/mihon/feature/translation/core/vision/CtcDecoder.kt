package mihon.feature.translation.core.vision

import java.nio.FloatBuffer

/**
 * Greedy CTC decoding for PaddleOCR recognisers.
 *
 * Class layout (PaddleOCR `CTCLabelDecode`): 0 = blank, 1..N = dictionary entries, N+1 = space.
 */
class CtcDecoder(private val dictionary: List<String>) {

    /**
     * @param probabilities `[steps * classes]` row-major softmax output.
     * @return decoded text and the mean probability of the emitted characters.
     */
    fun decode(probabilities: FloatBuffer, steps: Int, classes: Int): Pair<String, Float> {
        val indices = IntArray(steps)
        val maxProbs = FloatArray(steps)
        for (t in 0 until steps) {
            val offset = t * classes
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val v = probabilities.get(offset + c)
                if (v > bestValue) {
                    bestValue = v
                    best = c
                }
            }
            indices[t] = best
            maxProbs[t] = bestValue
        }
        return decode(indices, maxProbs)
    }

    fun decode(indices: IntArray, maxProbs: FloatArray? = null): Pair<String, Float> {
        val spaceIndex = dictionary.size + 1
        val sb = StringBuilder()
        var previous = -1
        var sum = 0f
        var count = 0
        for (t in indices.indices) {
            val index = indices[t]
            if (index != BLANK && index != previous) {
                when (index) {
                    in 1..dictionary.size -> sb.append(dictionary[index - 1])
                    spaceIndex -> sb.append(' ')
                }
                if (maxProbs != null) {
                    sum += maxProbs[t]
                    count++
                }
            }
            previous = index
        }
        val confidence = if (count == 0) 0f else sum / count
        return sb.toString().trim() to confidence
    }

    companion object {
        const val BLANK = 0
    }
}
