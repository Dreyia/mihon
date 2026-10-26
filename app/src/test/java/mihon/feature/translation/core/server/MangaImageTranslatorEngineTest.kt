package mihon.feature.translation.core.server

import kotlinx.coroutines.runBlocking
import mihon.feature.translation.core.SourceLanguage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaImageTranslatorEngineTest {

    @Test
    fun `language codes map to server ids`() {
        assertEquals("ENG", MangaImageTranslatorEngine.serverLanguage("en"))
        assertEquals("CHS", MangaImageTranslatorEngine.serverLanguage("zh"))
        assertEquals("JPN", MangaImageTranslatorEngine.serverLanguage("JA"))
    }

    @Test
    fun `uploads the page as multipart form and returns the server image`() = runBlocking {
        val server = MockWebServer()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4, 5)
        server.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("Content-Type", "image/png"))
        server.start()
        try {
            val engine = MangaImageTranslatorEngine(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString().trimEnd('/'),
                translator = "sugoi",
                sourceLanguage = SourceLanguage.JAPANESE,
                targetLanguage = "en",
            )
            val result = engine.translate(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            assertArrayEquals(png, result)

            val request = server.takeRequest()
            assertEquals("/translate/with-form/image", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"image\"; filename=\"page.jpg\""))
            assertTrue(body.contains("name=\"config\""))
            assertTrue(body.contains("\"translator\":\"sugoi\""))
            assertTrue(body.contains("\"target_lang\":\"ENG\""))
        } finally {
            server.shutdown()
        }
    }
}
