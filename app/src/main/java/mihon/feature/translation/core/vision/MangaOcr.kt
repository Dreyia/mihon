package mihon.feature.translation.core.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import mihon.feature.translation.core.onnx.Ort
import mihon.feature.translation.core.onnx.Ort.shape
import mihon.feature.translation.core.onnx.Ort.tensor
import java.io.File
import java.nio.FloatBuffer

/**
 * Japanese manga OCR (vertical and horizontal text, furigana aware).
 *
 * Model: https://huggingface.co/ogkalu/manga-ocr-mobile, a mobile export of
 * https://github.com/kha-white/manga-ocr split into three graphs:
 *  - `encoder.onnx`: `[1,3,224,224]` pixels normalised to -1..1 -> encoder hidden states
 *  - `decoder_init.onnx`: (`encoder_hidden_states`, `input_ids`=[CLS]) -> logits, self K/V, cross K/V
 *  - `decoder_step.onnx`: (`encoder_hidden_states`, `input_ids`, `position_ids`, `self_k_cache`,
 *    `self_v_cache`, `cross_k_cache`, `cross_v_cache`) -> logits, new self K/V slice
 *
 * Decoding is greedy. The tensor names, cache layout and the position bookkeeping follow the
 * contract used by TachiyomiATVibe's `MangaOcrEngine` (Apache-2.0), which runs these exact files.
 */
class MangaOcr(
    encoderFile: File,
    decoderInitFile: File,
    decoderStepFile: File,
    vocabFile: File,
) : TextRecognizer {

    private val encoder: OrtSession = Ort.createSession(encoderFile)
    private val decoderInit: OrtSession = Ort.createSession(decoderInitFile)
    private val decoderStep: OrtSession = Ort.createSession(decoderStepFile)
    private val vocab: List<String> = vocabFile.readLines(Charsets.UTF_8)

    private val encoderInput = encoder.inputNames.first()
    private val cacheLength: Int = run {
        val shape = (decoderStep.inputInfo["self_k_cache"]?.info as? TensorInfo)?.shape
        shape?.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: DEFAULT_CACHE_LENGTH
    }

    override fun recognize(crop: Bitmap): String {
        val pixels = preprocess(crop)
        val input = Ort.floatBuffer(3 * INPUT_SIZE * INPUT_SIZE)
        // manga-ocr converts to grayscale then back to RGB, so all three planes are the luma.
        BitmapOps.writeNchw(pixels, input) { (it / 255f - 0.5f) / 0.5f }

        Ort.floatTensor(input, 1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()).use { pixelTensor ->
            encoder.run(mapOf(encoderInput to pixelTensor)).use { encoded ->
                val hidden = encoded.tensor(0)
                return decode(hidden)
            }
        }
    }

    private fun preprocess(crop: Bitmap): IntArray {
        val gray = BitmapOps.grayscale(crop)
        // The original pipeline (ViTImageProcessor, size 224) squashes the crop without keeping aspect.
        val resized = BitmapOps.scaled(gray, INPUT_SIZE, INPUT_SIZE)
        gray.recycle()
        val pixels = BitmapOps.pixels(resized)
        resized.recycle()
        return pixels
    }

    private fun decode(encoderHidden: OnnxTensor): String {
        val startIds = Ort.longTensor(longArrayOf(START_TOKEN.toLong()), 1, 1)
        val initResult = decoderInit.run(
            mapOf("encoder_hidden_states" to encoderHidden, "input_ids" to startIds),
        )
        val tokens = ArrayList<Int>()
        var selfK: OnnxTensor? = null
        var selfV: OnnxTensor? = null
        var stepIds: OnnxTensor? = null
        var stepPositions: OnnxTensor? = null
        try {
            val parts = InitOutputs.from(initResult, vocab.size)
            var next = Ort.argmax(parts.logits.floatBuffer, 0, vocab.size)
            if (next == END_TOKEN) return ""
            tokens += next

            val layers = parts.selfK.shape[0].toInt()
            val heads = parts.selfK.shape[2].toInt()
            val headDim = parts.selfK.shape[4].toInt()
            val kBuffer = Ort.floatBuffer(layers * heads * cacheLength * headDim)
            val vBuffer = Ort.floatBuffer(layers * heads * cacheLength * headDim)
            copyIntoCache(parts.selfK, kBuffer, layers, heads, headDim, slot = 0)
            copyIntoCache(parts.selfV, vBuffer, layers, heads, headDim, slot = 0)
            val cacheShape = longArrayOf(layers.toLong(), 1, heads.toLong(), cacheLength.toLong(), headDim.toLong())
            selfK = Ort.floatTensor(kBuffer, *cacheShape)
            selfV = Ort.floatTensor(vBuffer, *cacheShape)

            val idBuffer = Ort.longBuffer(1)
            val posBuffer = Ort.longBuffer(1)
            stepIds = OnnxTensor.createTensor(Ort.env, idBuffer, longArrayOf(1, 1))
            stepPositions = OnnxTensor.createTensor(Ort.env, posBuffer, longArrayOf(1, 1))
            val feeds = mapOf(
                "encoder_hidden_states" to encoderHidden,
                "input_ids" to stepIds,
                "position_ids" to stepPositions,
                "self_k_cache" to selfK,
                "self_v_cache" to selfV,
                "cross_k_cache" to parts.crossK,
                "cross_v_cache" to parts.crossV,
            )

            // Position bookkeeping matches the reference port: the token produced by decoder_init sits at
            // position 1, the first step is fed position id 2 and its K/V slice is written to cache slot 1.
            var position = 2
            while (tokens.size < MAX_TOKENS && position < MAX_POSITIONS) {
                idBuffer.put(0, next.toLong())
                posBuffer.put(0, position.toLong())
                decoderStep.run(feeds).use { step ->
                    val logits = step.tensor(0)
                    next = Ort.argmax(logits.floatBuffer, 0, vocab.size)
                    copyIntoCache(step.tensor(1), kBuffer, layers, heads, headDim, slot = position - 1)
                    copyIntoCache(step.tensor(2), vBuffer, layers, heads, headDim, slot = position - 1)
                }
                if (next == END_TOKEN) break
                tokens += next
                position++
                if (isRepeating(tokens)) break
            }
            return postProcess(tokens.joinToString("") { vocab.getOrNull(it)?.removePrefix("##") ?: "" })
        } finally {
            initResult.close()
            startIds.close()
            selfK?.close()
            selfV?.close()
            stepIds?.close()
            stepPositions?.close()
        }
    }

    /**
     * Copies a `[layers,1,heads,S,headDim]` slice into the `[layers,1,heads,cacheLength,headDim]`
     * cache starting at [slot].
     */
    private fun copyIntoCache(src: OnnxTensor, dst: FloatBuffer, layers: Int, heads: Int, headDim: Int, slot: Int) {
        val seq = src.shape[3].toInt()
        val srcBuffer = src.floatBuffer
        for (layer in 0 until layers) {
            for (head in 0 until heads) {
                val srcBase = ((layer * heads) + head) * seq * headDim
                val dstBase = ((layer * heads) + head) * cacheLength * headDim + slot * headDim
                for (s in 0 until seq) {
                    if (slot + s >= cacheLength) break
                    for (d in 0 until headDim) {
                        dst.put(dstBase + s * headDim + d, srcBuffer.get(srcBase + s * headDim + d))
                    }
                }
            }
        }
    }

    private fun isRepeating(tokens: List<Int>): Boolean {
        if (tokens.size < REPEAT_LIMIT) return false
        val last = tokens.last()
        return tokens.takeLast(REPEAT_LIMIT).all { it == last }
    }

    override fun close() {
        encoder.close()
        decoderInit.close()
        decoderStep.close()
    }

    private class InitOutputs(
        val logits: OnnxTensor,
        val selfK: OnnxTensor,
        val selfV: OnnxTensor,
        val crossK: OnnxTensor,
        val crossV: OnnxTensor,
    ) {
        companion object {
            /**
             * decoder_init returns five tensors. The reference order is
             * [logits, self_k, self_v, cross_k, cross_v]; shapes are used to double check it.
             */
            fun from(result: OrtSession.Result, vocabSize: Int): InitOutputs {
                val tensors = result.map { it.value as OnnxTensor }
                val logits = tensors.first { it.shape.last().toInt() == vocabSize }
                val caches = tensors.filter { it !== logits && it.shape.size == 5 }
                // Self-attention caches cover the single [CLS] token; cross caches cover the encoder sequence.
                val self = caches.filter { it.shape[3] <= 8 }
                val cross = caches.filter { it.shape[3] > 8 }
                require(self.size == 2 && cross.size == 2) {
                    "Unexpected decoder_init outputs: ${tensors.map { it.shape.contentToString() }}"
                }
                return InitOutputs(logits, self[0], self[1], cross[0], cross[1])
            }
        }
    }

    companion object {
        const val INPUT_SIZE = 224
        private const val START_TOKEN = 2 // [CLS]
        private const val END_TOKEN = 3 // [SEP]
        private const val DEFAULT_CACHE_LENGTH = 256
        private const val MAX_TOKENS = 300

        /** Size of the decoder position-embedding table in the mobile export. */
        private const val MAX_POSITIONS = 128
        private const val REPEAT_LIMIT = 8

        /**
         * Same clean-up as manga-ocr's `post_process`: drop whitespace, normalise ellipses and convert
         * ASCII and half-width katakana to full width.
         */
        fun postProcess(text: String): String {
            var out = text.replace(Regex("\\s+"), "")
            out = out.replace("…", "...")
            out = out.replace(Regex("[・.]{2,}")) { ".".repeat(it.value.length) }
            return halfToFull(out)
        }

        private fun halfToFull(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                when {
                    c in '!'..'~' -> sb.append((c.code + 0xFEE0).toChar())
                    c in HALF_KATAKANA && next == 'ﾞ' && VOICED[c] != null -> {
                        sb.append(VOICED[c])
                        i++
                    }
                    c in HALF_KATAKANA && next == 'ﾟ' && SEMI_VOICED[c] != null -> {
                        sb.append(SEMI_VOICED[c])
                        i++
                    }
                    c in HALF_KATAKANA -> sb.append(FULL_KATAKANA[c - HALF_KATAKANA.first])
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        private val HALF_KATAKANA = '｡'..'ﾟ'
        private const val FULL_KATAKANA =
            "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゛゜"
        private val VOICED: Map<Char, Char> = mapOf(
            'ｶ' to 'ガ', 'ｷ' to 'ギ', 'ｸ' to 'グ', 'ｹ' to 'ゲ', 'ｺ' to 'ゴ',
            'ｻ' to 'ザ', 'ｼ' to 'ジ', 'ｽ' to 'ズ', 'ｾ' to 'ゼ', 'ｿ' to 'ゾ',
            'ﾀ' to 'ダ', 'ﾁ' to 'ヂ', 'ﾂ' to 'ヅ', 'ﾃ' to 'デ', 'ﾄ' to 'ド',
            'ﾊ' to 'バ', 'ﾋ' to 'ビ', 'ﾌ' to 'ブ', 'ﾍ' to 'ベ', 'ﾎ' to 'ボ', 'ｳ' to 'ヴ',
        )
        private val SEMI_VOICED: Map<Char, Char> = mapOf(
            'ﾊ' to 'パ',
            'ﾋ' to 'ピ',
            'ﾌ' to 'プ',
            'ﾍ' to 'ペ',
            'ﾎ' to 'ポ',
        )
    }
}
