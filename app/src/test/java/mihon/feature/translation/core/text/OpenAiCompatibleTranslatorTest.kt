package mihon.feature.translation.core.text

import kotlinx.coroutines.runBlocking
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TranslationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiCompatibleTranslatorTest {

    @Test
    fun `numbered replies map back by index and missing lines keep the original`() {
        val originals = listOf("一", "二", "三")
        val parsed = OpenAiCompatibleTranslator.parseNumbered("1. one\n3) three\nnoise", originals)
        assertEquals(listOf("one", "二", "three"), parsed)
    }

    @Test
    fun `un-numbered replies are matched by position when counts agree`() {
        val parsed = OpenAiCompatibleTranslator.parseNumbered("one\ntwo", listOf("一", "二"))
        assertEquals(listOf("one", "two"), parsed)
    }

    @Test
    fun `posts a chat completion and reads the content`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"1. Hello\n2. Good morning"}}]}""",
            ),
        )
        server.start()
        try {
            val translator =
                OpenAiCompatibleTranslator(OkHttpClient(), server.url("/v1").toString(), "key", "test-model")
            val result = translator.translate(listOf("こんにちは", "おはよう"), SourceLanguage.JAPANESE, "en")
            assertEquals(listOf("Hello", "Good morning"), result)

            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"test-model\""))
            assertTrue(body.contains("1. こんにちは"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http errors become TranslationException`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val translator = OpenAiCompatibleTranslator(OkHttpClient(), server.url("/").toString(), "", "m")
            assertThrows(TranslationException::class.java) {
                runBlocking { translator.translate(listOf("x"), SourceLanguage.JAPANESE, "en") }
            }
        } finally {
            server.shutdown()
        }
    }
}
