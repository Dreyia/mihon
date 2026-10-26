package mihon.feature.translation.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TranslationConfigTest {

    @Test
    fun `on-device japanese needs detector, ocr, inpainting and translator packs`() {
        val packs = TranslationConfig().requiredPacks()
        assertEquals(
            setOf(ModelPack.DETECTOR, ModelPack.OCR_JAPANESE, ModelPack.INPAINTING, ModelPack.TRANSLATE_JA_EN),
            packs,
        )
    }

    @Test
    fun `flat fill and llm translation drop the optional packs`() {
        val packs = TranslationConfig(
            sourceLanguage = SourceLanguage.CHINESE,
            textEngine = TextEngine.OPENAI_COMPATIBLE,
            inpaintMode = InpaintMode.FLAT_FILL,
        ).requiredPacks()
        assertEquals(setOf(ModelPack.DETECTOR, ModelPack.OCR_CHINESE), packs)
    }

    @Test
    fun `server engine needs no packs`() {
        assertEquals(emptySet<ModelPack>(), TranslationConfig(pageEngine = PageEngine.SERVER).requiredPacks())
    }

    @Test
    fun `cache key changes with settings but never contains the api key`() {
        val base = TranslationConfig(openAiApiKey = "secret")
        assertFalse(base.cacheKey().contains("secret"))
        assertEquals(base.cacheKey(), base.copy(openAiApiKey = "other").cacheKey())
        assert(base.cacheKey() != base.copy(fontScale = 1.3f).cacheKey())
    }
}
