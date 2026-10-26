package mihon.feature.translation.core.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * SentencePiece Unigram tokenizer driven by a Hugging Face `tokenizer.json`
 * (the format shipped with https://huggingface.co/onnx-community/opus-mt-ja-en).
 *
 * Pre-tokenisation follows the file's `WhitespaceSplit` + `Metaspace(add_prefix_space=true)`
 * configuration: every whitespace-separated chunk gets a leading `▁`. The `Precompiled`
 * normaliser in these files has `precompiled_charsmap: null`, i.e. no normalisation, so none is
 * applied here.
 *
 * Segmentation is the standard Viterbi search over piece log-probabilities. Characters with no
 * matching piece become `<unk>`.
 */
class UnigramTokenizer private constructor(
    private val pieces: Map<String, Int>,
    private val scores: FloatArray,
    private val idToPiece: Array<String>,
    private val maxPieceLength: Int,
    val unkId: Int,
    val eosId: Int,
    val padId: Int,
) {
    private val unkScore: Float = (scores.filterIndexed { i, _ -> i != unkId }.minOrNull() ?: -10f) - UNK_PENALTY

    val vocabSize: Int get() = idToPiece.size

    /** Encodes [text] and appends `</s>`. The result is capped at [maxLength] tokens. */
    fun encode(text: String, maxLength: Int = 512): IntArray {
        val ids = ArrayList<Int>()
        for (chunk in text.trim().split(WHITESPACE)) {
            if (chunk.isEmpty()) continue
            ids += segment(METASPACE + chunk)
            if (ids.size >= maxLength - 1) break
        }
        val capped = if (ids.size > maxLength - 1) ids.subList(0, maxLength - 1) else ids
        return (capped + eosId).toIntArray()
    }

    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId || id == padId || id == unkId) continue
            val piece = idToPiece.getOrNull(id) ?: continue
            sb.append(piece)
        }
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    fun pieceOf(id: Int): String? = idToPiece.getOrNull(id)

    /** Viterbi over code-point boundaries so a surrogate pair is never split. */
    private fun segment(chunk: String): List<Int> {
        val n = chunk.length
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestStart = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1) { unkId }
        bestScore[0] = 0f

        var start = 0
        while (start < n) {
            if (bestScore[start] != Float.NEGATIVE_INFINITY) {
                var end = start
                var matched = false
                while (end < n && end - start < maxPieceLength) {
                    end += Character.charCount(chunk.codePointAt(end))
                    val id = pieces[chunk.substring(start, end)] ?: continue
                    matched = true
                    val score = bestScore[start] + scores[id]
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        bestStart[end] = start
                        bestId[end] = id
                    }
                }
                if (!matched) {
                    // Nothing covers this character: emit <unk> for one code point.
                    val end = start + Character.charCount(chunk.codePointAt(start))
                    val score = bestScore[start] + unkScore
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        bestStart[end] = start
                        bestId[end] = unkId
                    }
                }
            }
            start += Character.charCount(chunk.codePointAt(start))
        }

        val out = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            out += bestId[pos]
            pos = bestStart[pos]
        }
        out.reverse()
        return out
    }

    companion object {
        private const val METASPACE = '▁'
        private const val UNK_PENALTY = 10f
        private val WHITESPACE = Regex("\\s+")

        fun load(file: File): UnigramTokenizer = parse(file.readText(Charsets.UTF_8))

        fun parse(json: String): UnigramTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root["model"]!!.jsonObject
            require(model["type"]?.jsonPrimitive?.content == "Unigram") { "Only Unigram tokenizers are supported" }
            val vocab = model["vocab"]!!.jsonArray
            val pieces = HashMap<String, Int>(vocab.size * 2)
            val scores = FloatArray(vocab.size)
            val idToPiece = Array(vocab.size) { "" }
            var maxLen = 1
            vocab.forEachIndexed { id, entry ->
                val pair = entry.jsonArray
                val piece = pair[0].jsonPrimitive.content
                pieces.putIfAbsent(piece, id)
                scores[id] = pair[1].jsonPrimitive.content.toFloat()
                idToPiece[id] = piece
                if (piece.length > maxLen) maxLen = piece.length
            }
            val added = root["added_tokens"]?.jsonArray.orEmpty()
                .associate {
                    it.jsonObject["content"]!!.jsonPrimitive.content to
                        it.jsonObject["id"]!!.jsonPrimitive.content.toInt()
                }
            // tokenizer.json declares unk_id 2 for these Marian exports, but the model config and the
            // slow tokenizer both use the "<unk>" entry (id 1). The explicit special token wins.
            val unkId = added["<unk>"] ?: pieces["<unk>"] ?: model["unk_id"]!!.jsonPrimitive.content.toInt()
            val eosId = added["</s>"] ?: pieces["</s>"] ?: 0
            val padId = added["<pad>"] ?: pieces["<pad>"] ?: (vocab.size - 1)
            return UnigramTokenizer(pieces, scores, idToPiece, maxLen, unkId, eosId, padId)
        }
    }
}
