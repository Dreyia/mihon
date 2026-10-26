package mihon.feature.translation.core.text

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TextTranslator
import mihon.feature.translation.core.TranslationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Translates with any server that speaks the OpenAI chat-completions API: Ollama, LM Studio,
 * llama.cpp server, OpenRouter, OpenAI, DeepSeek... Works with whatever GPU the PC has.
 *
 * All lines of a page are sent in one request as a numbered list so the model sees the whole
 * conversation for context; the answer is parsed back by line number.
 */
class OpenAiCompatibleTranslator(
    client: OkHttpClient,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TextTranslator {

    private val client = client.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val endpoint = baseUrl.trimEnd('/').removeSuffix("/chat/completions") + "/chat/completions"

    override suspend fun translate(texts: List<String>, source: SourceLanguage, target: String): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.all { it.isBlank() }) return@withContext texts.map { "" }
            val body = buildRequest(texts, source, target)
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toString().toRequestBody(JSON))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val content = try {
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        throw TranslationException("LLM endpoint returned HTTP ${response.code}: ${text.take(300)}")
                    }
                    extractContent(text)
                }
            } catch (e: IOException) {
                throw TranslationException("Could not reach LLM endpoint $endpoint", e)
            }
            parseNumbered(content, texts)
        }

    private fun buildRequest(texts: List<String>, source: SourceLanguage, target: String): JsonObject {
        val sourceName = languageName(source.code)
        val targetName = languageName(target)
        val system = "You translate manga dialogue from $sourceName to $targetName. " +
            "Keep the tone casual and natural, keep names as they are, keep each line short enough for a speech bubble. " +
            "Reply with the translated lines only, one per line, keeping the same numbering as the input. " +
            "Never add explanations."
        val user = texts.mapIndexed { i, t -> "${i + 1}. ${t.replace('\n', ' ')}" }.joinToString("\n")
        return buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                },
            )
        }
    }

    private fun extractContent(json: String): String {
        val root = Json.parseToJsonElement(json).jsonObject
        root["error"]?.let { throw TranslationException("LLM error: $it") }
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw TranslationException("LLM reply has no choices: ${json.take(300)}")
        return message["content"]?.jsonPrimitive?.content ?: ""
    }

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    override fun close() = Unit

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val NUMBERED = Regex("^\\s*(\\d+)\\s*[.):\\-]\\s*(.*)$")

        /**
         * Maps "N. text" lines back to their inputs. Lines the model skipped fall back to the original text
         * so the page never loses a bubble. Un-numbered replies are matched by position.
         */
        fun parseNumbered(content: String, originals: List<String>): List<String> {
            val byIndex = HashMap<Int, String>()
            val plain = ArrayList<String>()
            for (line in content.lines()) {
                if (line.isBlank()) continue
                val match = NUMBERED.find(line)
                if (match != null) {
                    val index = match.groupValues[1].toInt() - 1
                    val text = match.groupValues[2].trim()
                    if (index in originals.indices) {
                        byIndex[index] = (byIndex[index]?.plus(" ") ?: "") + text
                        continue
                    }
                }
                plain += line.trim()
            }
            return originals.indices.map { i ->
                byIndex[i]
                    ?: plain.getOrNull(i).takeIf { byIndex.isEmpty() && plain.size == originals.size }
                    ?: originals[i]
            }
        }
    }
}
