package mihon.feature.translation.core.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.feature.translation.core.PageTranslator
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TranslationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for a self-hosted https://github.com/zyddnys/manga-image-translator server.
 *
 * Uses `POST /translate/with-form/image` (multipart: `image` file + `config` JSON string), which
 * returns the finished page as PNG. Endpoint and config schema: `server/main.py` and the
 * "Config" section of the project README.
 */
class MangaImageTranslatorEngine(
    client: OkHttpClient,
    baseUrl: String,
    private val translator: String,
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: String,
) : PageTranslator {

    private val client = client.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val endpoint = baseUrl.trim().trimEnd('/') + "/translate/with-form/image"

    override suspend fun translate(imageBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (endpoint.startsWith("/")) throw TranslationException("Server URL is not set")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "page" + extensionOf(imageBytes),
                imageBytes.toRequestBody(mediaTypeOf(imageBytes)),
            )
            .addFormDataPart("config", config())
            .build()
        val request = Request.Builder().url(endpoint).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TranslationException(
                        "manga-image-translator returned HTTP ${response.code}: ${response.body.string().take(300)}",
                    )
                }
                response.body.bytes()
            }
        } catch (e: IOException) {
            throw TranslationException("Could not reach manga-image-translator at $endpoint", e)
        }
    }

    /** Config JSON understood by the server; only the fields we set, the rest keep server defaults. */
    fun config(): String = buildJsonObject {
        put(
            "translator",
            buildJsonObject {
                put("translator", translator)
                put("target_lang", serverLanguage(targetLanguage))
            },
        )
        put("detector", buildJsonObject { put("detector", "default") })
        put(
            "ocr",
            buildJsonObject {
                put(
                    "ocr",
                    if (sourceLanguage ==
                        SourceLanguage.JAPANESE
                    ) {
                        "48px"
                    } else {
                        "48px_ctc"
                    },
                )
            },
        )
    }.toString()

    override fun close() = Unit

    companion object {
        private val PNG = "image/png".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private val WEBP = "image/webp".toMediaType()

        /** ISO 639-1 -> the language ids manga-image-translator expects. */
        fun serverLanguage(code: String): String = when (code.lowercase()) {
            "en" -> "ENG"
            "ja" -> "JPN"
            "zh", "zh-cn" -> "CHS"
            "zh-tw" -> "CHT"
            "ko" -> "KOR"
            "es" -> "ESP"
            "fr" -> "FRA"
            "de" -> "DEU"
            "it" -> "ITA"
            "pt", "pt-br" -> "PTB"
            "ru" -> "RUS"
            "vi" -> "VIN"
            "id" -> "IND"
            "th" -> "THA"
            "ar" -> "ARA"
            "tr" -> "TRK"
            "pl" -> "PLK"
            "nl" -> "NLD"
            "uk" -> "UKR"
            else -> code.uppercase().take(3)
        }

        private fun mediaTypeOf(bytes: ByteArray) = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> PNG
            bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> WEBP
            else -> JPEG
        }

        private fun extensionOf(bytes: ByteArray) = when (mediaTypeOf(bytes)) {
            PNG -> ".png"
            WEBP -> ".webp"
            else -> ".jpg"
        }
    }
}
