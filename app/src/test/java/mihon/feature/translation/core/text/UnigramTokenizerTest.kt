package mihon.feature.translation.core.text

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnigramTokenizerTest {

    // Mirrors the layout of onnx-community/opus-mt-ja-en/tokenizer.json, including its odd unk_id=2.
    private val json = """
        {
          "added_tokens": [
            {"id": 0, "content": "</s>", "special": true},
            {"id": 1, "content": "<unk>", "special": true},
            {"id": 7, "content": "<pad>", "special": true}
          ],
          "normalizer": {"type": "Precompiled", "precompiled_charsmap": null},
          "pre_tokenizer": {"type": "Sequence", "pretokenizers": [{"type": "WhitespaceSplit"}, {"type": "Metaspace", "replacement": "▁", "add_prefix_space": true}]},
          "model": {
            "type": "Unigram",
            "unk_id": 2,
            "vocab": [
              ["</s>", 0.0],
              ["<unk>", 0.0],
              ["▁こん", -3.0],
              ["にち", -3.5],
              ["は", -2.0],
              ["▁hello", -4.0],
              ["▁こんにち", -8.0],
              ["<pad>", 0.0]
            ]
          }
        }
    """.trimIndent()

    private val tokenizer = UnigramTokenizer.parse(json)

    @Test
    fun `special token ids come from added_tokens`() {
        assertEquals(1, tokenizer.unkId)
        assertEquals(0, tokenizer.eosId)
        assertEquals(7, tokenizer.padId)
    }

    @Test
    fun `viterbi picks the highest scoring segmentation and appends eos`() {
        // ▁こん(-3) + にち(-3.5) + は(-2) = -8.5 beats ▁こんにち(-8) + は(-2) = -10
        assertArrayEquals(intArrayOf(2, 3, 4, 0), tokenizer.encode("こんにちは"))
    }

    @Test
    fun `each whitespace separated word gets a metaspace prefix`() {
        assertArrayEquals(intArrayOf(5, 5, 0), tokenizer.encode("hello  hello"))
    }

    @Test
    fun `unknown characters become unk without dropping the rest`() {
        assertArrayEquals(intArrayOf(2, 1, 4, 0), tokenizer.encode("こん𝄞は"))
    }

    @Test
    fun `decode joins pieces and restores spaces`() {
        assertEquals("hello hello", tokenizer.decode(intArrayOf(5, 5, 0)))
        assertEquals("こんにちは", tokenizer.decode(intArrayOf(2, 3, 4, 0, 7)))
    }

    @Test
    fun `encode respects the maximum length`() {
        val ids = tokenizer.encode("hello hello hello hello", maxLength = 3)
        assertArrayEquals(intArrayOf(5, 5, 0), ids)
    }
}
