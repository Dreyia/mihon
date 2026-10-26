package mihon.feature.translation.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.translation.core.cache.PageTranslationCache
import mihon.feature.translation.core.pipeline.OnDevicePageTranslator
import mihon.feature.translation.core.server.MangaImageTranslatorEngine
import mihon.feature.translation.core.text.MarianOnnxTranslator
import mihon.feature.translation.core.text.OpenAiCompatibleTranslator
import mihon.feature.translation.core.vision.AotInpainter
import mihon.feature.translation.core.vision.FlatFillInpainter
import mihon.feature.translation.core.vision.Inpainter
import mihon.feature.translation.core.vision.MangaOcr
import mihon.feature.translation.core.vision.PaddleDictionary
import mihon.feature.translation.core.vision.PaddleOcr
import mihon.feature.translation.core.vision.RtDetrTextDetector
import mihon.feature.translation.core.vision.TextRecognizer
import okhttp3.OkHttpClient

/**
 * Entry point used by the reader. Owns the active [PageTranslator], rebuilds it when the settings
 * change, serialises page processing (the models are memory hungry) and consults the disk cache.
 */
class TranslationManager(
    val modelStore: ModelStore,
    val cache: PageTranslationCache,
    private val client: OkHttpClient,
    private val configProvider: () -> TranslationConfig,
) {
    private val mutex = Mutex()
    private var engine: PageTranslator? = null
    private var engineConfig: TranslationConfig? = null

    /** Model packs that still need downloading for the current settings. */
    fun missingPacks(config: TranslationConfig = configProvider()): Set<ModelPack> =
        modelStore.missing(config.requiredPacks())

    /**
     * @return the translated page, or `null` when the page has no text and the original should be shown.
     * @throws ModelsNotInstalledException when the on-device engine is selected but packs are missing.
     * @throws TranslationException for server/LLM failures.
     */
    suspend fun translate(imageBytes: ByteArray): ByteArray? {
        val config = configProvider()
        val key = cache.key(imageBytes, config.cacheKey())
        cache.get(key)?.let { return it.bytes() }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it.bytes() }
            val result = engineFor(config).translate(imageBytes)
            cache.put(key, result)
            result
        }
    }

    /** Frees the loaded models. Call when the reader closes or translation is switched off. */
    suspend fun release() = mutex.withLock {
        engine?.close()
        engine = null
        engineConfig = null
    }

    private fun engineFor(config: TranslationConfig): PageTranslator {
        engine?.takeIf { engineConfig == config }?.let { return it }
        engine?.close()
        engine = null
        val created = when (config.pageEngine) {
            PageEngine.SERVER -> MangaImageTranslatorEngine(
                client = client,
                baseUrl = config.serverUrl,
                translator = config.serverTranslator,
                sourceLanguage = config.sourceLanguage,
                targetLanguage = config.targetLanguage,
            )
            PageEngine.ON_DEVICE -> buildOnDevice(config)
        }
        engine = created
        engineConfig = config
        return created
    }

    private fun buildOnDevice(config: TranslationConfig): PageTranslator {
        val missing = missingPacks(config)
        if (missing.isNotEmpty()) throw ModelsNotInstalledException(missing)

        val detector = RtDetrTextDetector(
            modelFile = modelStore.file(ModelPack.DETECTOR, "detector.onnx"),
            threshold = config.detectionThreshold,
        )
        val recognizer: TextRecognizer = when (config.sourceLanguage) {
            SourceLanguage.JAPANESE -> MangaOcr(
                encoderFile = modelStore.file(ModelPack.OCR_JAPANESE, "encoder.onnx"),
                decoderInitFile = modelStore.file(ModelPack.OCR_JAPANESE, "decoder_init.onnx"),
                decoderStepFile = modelStore.file(ModelPack.OCR_JAPANESE, "decoder_step.onnx"),
                vocabFile = modelStore.file(ModelPack.OCR_JAPANESE, "vocab.txt"),
            )
            SourceLanguage.CHINESE -> PaddleOcr(
                modelFile = modelStore.file(ModelPack.OCR_CHINESE, "rec.onnx"),
                dictionary = PaddleDictionary.parse(modelStore.file(ModelPack.OCR_CHINESE, "rec.yml").readText()),
            )
        }
        val translator: TextTranslator = when (config.textEngine) {
            TextEngine.ON_DEVICE -> {
                val pack = when (config.sourceLanguage) {
                    SourceLanguage.JAPANESE -> ModelPack.TRANSLATE_JA_EN
                    SourceLanguage.CHINESE -> ModelPack.TRANSLATE_ZH_EN
                }
                MarianOnnxTranslator(
                    encoderFile = modelStore.file(pack, "encoder.onnx"),
                    decoderFile = modelStore.file(pack, "decoder.onnx"),
                    tokenizerFile = modelStore.file(pack, "tokenizer.json"),
                )
            }
            TextEngine.OPENAI_COMPATIBLE -> OpenAiCompatibleTranslator(
                client = client,
                baseUrl = config.openAiBaseUrl,
                apiKey = config.openAiApiKey,
                model = config.openAiModel,
            )
        }
        val inpainter: Inpainter = when (config.inpaintMode) {
            InpaintMode.MODEL -> AotInpainter(modelStore.file(ModelPack.INPAINTING, "aot.onnx"))
            InpaintMode.FLAT_FILL -> FlatFillInpainter()
        }
        return OnDevicePageTranslator(detector, recognizer, translator, inpainter, config)
    }

    private fun PageTranslationCache.Entry.bytes(): ByteArray? = when (this) {
        is PageTranslationCache.Entry.Translated -> bytes
        PageTranslationCache.Entry.NoText -> null
    }
}
