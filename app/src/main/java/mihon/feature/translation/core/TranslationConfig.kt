package mihon.feature.translation.core

/**
 * Language the manga page is written in. Each value maps to the on-device OCR and
 * translation models that handle it (see [ModelPack]).
 */
enum class SourceLanguage(val code: String) {
    JAPANESE("ja"),
    CHINESE("zh"),
    ;

    companion object {
        fun fromCode(code: String): SourceLanguage = entries.firstOrNull { it.code == code } ?: JAPANESE
    }
}

/** Which component turns the whole page into a translated page. */
enum class PageEngine {
    /** Everything runs on the phone with ONNX models. */
    ON_DEVICE,

    /** Page is uploaded to a self-hosted manga-image-translator server which returns the finished image. */
    SERVER,
}

/** Which component translates the recognised text when [PageEngine.ON_DEVICE] is used. */
enum class TextEngine {
    /** opus-mt Marian model running on the phone. */
    ON_DEVICE,

    /** Any OpenAI-compatible chat endpoint (Ollama, LM Studio, OpenRouter, ...). */
    OPENAI_COMPATIBLE,
}

/** How the original text is removed before the translation is drawn. */
enum class InpaintMode {
    /** AOT inpainting model. Best result, needs the inpainting model pack. */
    MODEL,

    /** Fill the text box with the colour sampled around it. No model needed. */
    FLAT_FILL,
}

/**
 * Snapshot of every setting that changes the output image. [cacheKey] must change whenever the
 * rendered result would change so the disk cache never serves a stale page.
 */
data class TranslationConfig(
    val pageEngine: PageEngine = PageEngine.ON_DEVICE,
    val sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE,
    /** ISO 639-1 code. On-device translation currently only supports "en". */
    val targetLanguage: String = "en",
    val textEngine: TextEngine = TextEngine.ON_DEVICE,
    val inpaintMode: InpaintMode = InpaintMode.MODEL,
    /** Also translate text that is not inside a speech bubble (sound effects, signs). */
    val translateFreeText: Boolean = false,
    /** Multiplier applied to the font size chosen by the layout. */
    val fontScale: Float = 1f,
    /** Detector confidence threshold, 0..1. */
    val detectionThreshold: Float = 0.5f,

    // Server engine
    val serverUrl: String = "",
    val serverTranslator: String = "sugoi",

    // OpenAI-compatible text engine
    val openAiBaseUrl: String = "",
    val openAiApiKey: String = "",
    val openAiModel: String = "",
) {
    /** Part of the cache key. Secrets (API key) are deliberately excluded. */
    fun cacheKey(): String = listOf(
        "v1",
        pageEngine.name,
        sourceLanguage.code,
        targetLanguage,
        textEngine.name,
        inpaintMode.name,
        translateFreeText,
        fontScale,
        detectionThreshold,
        serverUrl,
        serverTranslator,
        openAiBaseUrl,
        openAiModel,
    ).joinToString("|")

    /** Model packs that must be installed before this configuration can run on the phone. */
    fun requiredPacks(): Set<ModelPack> {
        if (pageEngine != PageEngine.ON_DEVICE) return emptySet()
        val packs = mutableSetOf(ModelPack.DETECTOR)
        packs += when (sourceLanguage) {
            SourceLanguage.JAPANESE -> ModelPack.OCR_JAPANESE
            SourceLanguage.CHINESE -> ModelPack.OCR_CHINESE
        }
        if (inpaintMode == InpaintMode.MODEL) packs += ModelPack.INPAINTING
        if (textEngine == TextEngine.ON_DEVICE) {
            packs += when (sourceLanguage) {
                SourceLanguage.JAPANESE -> ModelPack.TRANSLATE_JA_EN
                SourceLanguage.CHINESE -> ModelPack.TRANSLATE_ZH_EN
            }
        }
        return packs
    }
}
