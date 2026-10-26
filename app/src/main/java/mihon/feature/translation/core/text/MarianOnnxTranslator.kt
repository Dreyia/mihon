package mihon.feature.translation.core.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TextTranslator
import mihon.feature.translation.core.onnx.Ort
import mihon.feature.translation.core.onnx.Ort.shape
import mihon.feature.translation.core.onnx.Ort.tensor
import java.io.File

/**
 * On-device opus-mt (Marian) translation with ONNX Runtime.
 *
 * Files come from an Optimum export (https://huggingface.co/onnx-community/opus-mt-ja-en):
 *  - `encoder.onnx`: `input_ids`, `attention_mask` -> `last_hidden_state`
 *  - `decoder.onnx` (merged): `input_ids`, `encoder_attention_mask`, `encoder_hidden_states`,
 *    `past_key_values.{layer}.{decoder|encoder}.{key|value}`, `use_cache_branch` ->
 *    `logits`, `present.{layer}.{decoder|encoder}.{key|value}`
 *
 * Decoding is greedy (the reference generation config uses 6 beams, so expect slightly weaker
 * output in exchange for speed). The input/output names are discovered from the session so a
 * different export layout fails loudly instead of silently.
 */
class MarianOnnxTranslator(
    encoderFile: File,
    decoderFile: File,
    tokenizerFile: File,
) : TextTranslator {

    private val tokenizer = UnigramTokenizer.load(tokenizerFile)
    private val encoder: OrtSession = Ort.createSession(encoderFile)
    private val decoder: OrtSession = Ort.createSession(decoderFile)

    private val pastInputs: List<String> = decoder.inputNames.filter { it.startsWith("past_key_values.") }
    private val useCacheInput: String? = decoder.inputNames.firstOrNull { it == "use_cache_branch" }
    private val decoderStartId: Int = tokenizer.padId

    init {
        require("input_ids" in decoder.inputNames && "encoder_hidden_states" in decoder.inputNames) {
            "Unexpected Marian decoder inputs: ${decoder.inputNames}"
        }
    }

    override suspend fun translate(texts: List<String>, source: SourceLanguage, target: String): List<String> =
        withContext(Dispatchers.Default) {
            texts.map { text -> if (text.isBlank()) "" else translateOne(text) }
        }

    private fun translateOne(text: String): String {
        val ids = tokenizer.encode(text, MAX_SOURCE_TOKENS)
        val length = ids.size.toLong()
        val inputIds = Ort.longTensor(LongArray(ids.size) { ids[it].toLong() }, 1, length)
        val attention = Ort.longTensor(LongArray(ids.size) { 1L }, 1, length)
        try {
            encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to attention)).use { encoded ->
                val hidden = encoded.tensor(0)
                return decodeGreedy(hidden, attention, maxNewTokens = (ids.size * 2 + 10).coerceAtMost(MAX_TARGET_TOKENS))
            }
        } finally {
            inputIds.close()
            attention.close()
        }
    }

    private fun decodeGreedy(encoderHidden: OnnxTensor, encoderMask: OnnxTensor, maxNewTokens: Int): String {
        val output = ArrayList<Int>()
        var token = decoderStartId
        var previous: OrtSession.Result? = null
        var first: OrtSession.Result? = null
        val emptyPast = ArrayList<OnnxTensor>()
        try {
            for (step in 0 until maxNewTokens) {
                val feeds = HashMap<String, OnnxTensor>()
                val stepIds = Ort.longTensor(longArrayOf(token.toLong()), 1, 1)
                feeds["input_ids"] = stepIds
                feeds["encoder_hidden_states"] = encoderHidden
                if ("encoder_attention_mask" in decoder.inputNames) feeds["encoder_attention_mask"] = encoderMask

                if (previous == null) {
                    for (name in pastInputs) {
                        val empty = emptyPast(name)
                        emptyPast += empty
                        feeds[name] = empty
                    }
                    useCacheInput?.let { feeds[it] = Ort.boolTensor(false) }
                } else {
                    for (name in pastInputs) {
                        val presentName = "present." + name.removePrefix("past_key_values.")
                        // Encoder K/V never change: reuse the first step's tensors.
                        val from = if (".encoder." in name) first!! else previous
                        feeds[name] = from.tensor(presentName)
                            ?: error("Decoder output $presentName missing; outputs=${decoder.outputNames}")
                    }
                    useCacheInput?.let { feeds[it] = Ort.boolTensor(true) }
                }

                val result = decoder.run(feeds)
                stepIds.close()
                useCacheInput?.let { feeds[it]?.close() }

                val logits = result.tensor("logits") ?: result.tensor(0)
                val vocab = logits.shape.last().toInt()
                val offset = (logits.shape[1].toInt() - 1) * vocab
                token = Ort.argmax(logits.floatBuffer, offset, vocab, skip = tokenizer.padId)

                if (first == null) {
                    first = result
                } else {
                    previous?.takeIf { it !== first }?.close()
                }
                previous = result

                if (token == tokenizer.eosId) break
                output += token
                if (isLooping(output)) break
            }
        } finally {
            previous?.takeIf { it !== first }?.close()
            first?.close()
            emptyPast.forEach { it.close() }
        }
        return tokenizer.decode(output.toIntArray())
    }

    /** A `[1, heads, 0, headDim]` tensor for the first step of the merged decoder. */
    private fun emptyPast(name: String): OnnxTensor {
        val shape = (decoder.inputInfo[name]?.info as? TensorInfo)?.shape ?: longArrayOf(1, 8, 0, 64)
        val heads = shape.getOrNull(1)?.takeIf { it > 0 } ?: 8L
        val headDim = shape.getOrNull(3)?.takeIf { it > 0 } ?: 64L
        return Ort.floatTensor(Ort.floatBuffer(0), 1, heads, 0, headDim)
    }

    private fun isLooping(tokens: List<Int>): Boolean {
        if (tokens.size < LOOP_WINDOW * 2) return false
        val tail = tokens.takeLast(LOOP_WINDOW)
        val before = tokens.subList(tokens.size - LOOP_WINDOW * 2, tokens.size - LOOP_WINDOW)
        return tail == before
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        const val MAX_SOURCE_TOKENS = 256
        const val MAX_TARGET_TOKENS = 256
        const val LOOP_WINDOW = 6
    }
}
