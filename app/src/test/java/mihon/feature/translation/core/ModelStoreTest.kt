package mihon.feature.translation.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ModelStoreTest {

    @TempDir
    lateinit var dir: File

    private val payload = ByteArray(1000) { (it % 251).toByte() }

    @Test
    fun `downloads a file and verifies its size`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        server.start()
        try {
            val store = ModelStore(dir, OkHttpClient())
            val model = ModelFile("m.onnx", server.url("/m.onnx").toString(), payload.size.toLong())
            val target = File(dir, "m.onnx")
            var last = 0L
            store.downloadFile(model, target) { last = it }
            assertArrayEquals(payload, target.readBytes())
            assertEquals(payload.size.toLong(), last)
            assertFalse(File(dir, "m.onnx.part").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resumes a partial download with a range request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(Buffer().write(payload, 400, 600)),
        )
        server.start()
        try {
            File(dir, "m.onnx.part").writeBytes(payload.copyOfRange(0, 400))
            val store = ModelStore(dir, OkHttpClient())
            val model = ModelFile("m.onnx", server.url("/m.onnx").toString(), payload.size.toLong())
            store.downloadFile(model, File(dir, "m.onnx")) {}
            assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
            assertArrayEquals(payload, File(dir, "m.onnx").readBytes())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `size mismatch is rejected`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(payload, 0, 10)))
        server.start()
        try {
            val store = ModelStore(dir, OkHttpClient())
            val model = ModelFile("m.onnx", server.url("/m.onnx").toString(), payload.size.toLong())
            assertThrows(IOException::class.java) {
                runBlocking { store.downloadFile(model, File(dir, "m.onnx")) {} }
            }
            assertFalse(File(dir, "m.onnx").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `installed detection requires every file at its expected size`() {
        val store = ModelStore(dir, OkHttpClient())
        assertFalse(store.isInstalled(ModelPack.DETECTOR))
        val file = store.file(ModelPack.DETECTOR, "detector.onnx")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(10))
        assertFalse(store.isInstalled(ModelPack.DETECTOR))
        assertTrue(store.missing(setOf(ModelPack.DETECTOR)).contains(ModelPack.DETECTOR))
    }
}
