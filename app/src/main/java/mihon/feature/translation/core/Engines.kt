package mihon.feature.translation.core

/**
 * Turns an encoded page image into an encoded translated page image.
 */
interface PageTranslator {
    /**
     * @param imageBytes the original page (JPEG/PNG/WebP...).
     * @return the translated page encoded as JPEG or PNG, or `null` when the page has no text to translate.
     */
    suspend fun translate(imageBytes: ByteArray): ByteArray?

    fun close()
}

/**
 * Translates recognised text. Implementations must return exactly one entry per input, in order.
 */
interface TextTranslator {
    suspend fun translate(texts: List<String>, source: SourceLanguage, target: String): List<String>

    fun close()
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when an on-device model pack has not been downloaded yet. */
class ModelsNotInstalledException(val missing: Set<ModelPack>) :
    Exception("Missing model packs: ${missing.joinToString { it.id }}")
