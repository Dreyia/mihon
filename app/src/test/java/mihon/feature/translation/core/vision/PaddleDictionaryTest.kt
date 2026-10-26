package mihon.feature.translation.core.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddleDictionaryTest {

    @Test
    fun `parses the character_dict block of a PaddleOCR inference yml`() {
        val yaml = """
            Global:
              model_name: PP-OCRv6_small_rec
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - '"'
              - ${'$'}
              - ''''
              - \
              - "中"
              - 'a b'
              - 😀
            Other:
              - not part of the dict
        """.trimIndent()
        assertEquals(listOf("!", "\"", "$", "'", "\\", "中", "a b", "😀"), PaddleDictionary.parse(yaml))
    }

    @Test
    fun `returns empty list when the key is missing`() {
        assertEquals(emptyList<String>(), PaddleDictionary.parse("Global:\n  x: 1\n"))
    }
}
