package mihon.feature.translation.core.cache

import java.io.File
import java.security.MessageDigest

/**
 * Disk cache of translated pages, keyed by the original image bytes and the translation settings.
 *
 * A page that turned out to contain no text is remembered too (as an empty `.none` marker) so it
 * is not re-run through the models every time it is shown.
 */
class PageTranslationCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    sealed interface Entry {
        class Translated(val bytes: ByteArray) : Entry
        data object NoText : Entry
    }

    init {
        dir.mkdirs()
    }

    fun key(imageBytes: ByteArray, configKey: String): String =
        sha256(imageBytes).toHex() + "-" + sha256(configKey.toByteArray(Charsets.UTF_8)).toHex().take(16)

    fun get(key: String): Entry? {
        val image = imageFile(key)
        if (image.isFile) {
            image.setLastModified(System.currentTimeMillis())
            return Entry.Translated(image.readBytes())
        }
        val none = noneFile(key)
        if (none.isFile) {
            none.setLastModified(System.currentTimeMillis())
            return Entry.NoText
        }
        return null
    }

    fun put(key: String, translated: ByteArray?) {
        dir.mkdirs()
        if (translated == null) {
            noneFile(key).writeBytes(ByteArray(0))
        } else {
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(translated)
            if (!tmp.renameTo(imageFile(key))) tmp.delete()
        }
        trim()
    }

    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes * TRIM_TARGET) break
            total -= file.length()
            file.delete()
        }
    }

    private fun imageFile(key: String) = File(dir, "$key.img")
    private fun noneFile(key: String) = File(dir, "$key.none")

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        private const val TRIM_TARGET = 0.9
    }
}
