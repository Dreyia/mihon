package mihon.feature.translation.core.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaOcrPostProcessTest {

    @Test
    fun `removes whitespace and normalises ellipses like manga-ocr`() {
        // manga-ocr turns "…" into "..." and then jaconv.h2z(ascii=True) makes the dots full width.
        assertEquals("こんにちは．．．", MangaOcr.postProcess("こん にちは…"))
        assertEquals("え．．．．", MangaOcr.postProcess("え・・・・"))
    }

    @Test
    fun `converts ascii and half-width katakana to full width`() {
        assertEquals("ＡＢＣ１２３！", MangaOcr.postProcess("ABC123!"))
        assertEquals("ガンバレ", MangaOcr.postProcess("ｶﾞﾝﾊﾟﾞﾚ".replace("ﾊﾟﾞ", "ﾊﾞ")))
        assertEquals("パン", MangaOcr.postProcess("ﾊﾟﾝ"))
    }
}
