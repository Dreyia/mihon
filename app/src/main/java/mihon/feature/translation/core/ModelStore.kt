package mihon.feature.translation.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Download state of one [ModelPack].
 */
sealed interface PackStatus {
    data object NotInstalled : PackStatus
    data class Downloading(val downloaded: Long, val total: Long) : PackStatus {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data object Installed : PackStatus
    data class Failed(val error: Throwable) : PackStatus
}

/**
 * Keeps model packs on disk under [rootDir]/<pack id>/ and downloads them with [client].
 *
 * Downloads are resumable: a partial file is kept as `<name>.part` and continued with a `Range`
 * request. A file is considered installed when it exists with exactly its expected size.
 */
class ModelStore(
    private val rootDir: File,
    private val client: OkHttpClient,
) {
    private val _status = MutableStateFlow(ModelPack.entries.associateWith { statusOnDisk(it) })
    val status: StateFlow<Map<ModelPack, PackStatus>> = _status

    fun packDir(pack: ModelPack): File = File(rootDir, pack.id)

    fun file(pack: ModelPack, fileName: String): File = File(packDir(pack), fileName)

    fun isInstalled(pack: ModelPack): Boolean = pack.files.all { file(pack, it.fileName).isComplete(it) }

    fun missing(packs: Set<ModelPack>): Set<ModelPack> = packs.filterNot(::isInstalled).toSet()

    fun installedBytes(): Long = ModelPack.entries.filter(::isInstalled).sumOf { it.totalSize }

    fun refresh() {
        _status.value = ModelPack.entries.associateWith { statusOnDisk(it) }
    }

    fun delete(pack: ModelPack) {
        packDir(pack).deleteRecursively()
        _status.update { it + (pack to PackStatus.NotInstalled) }
    }

    /**
     * Downloads every missing file of [pack]. Safe to call for an installed pack (no-op).
     * Progress is published through [status].
     */
    suspend fun download(pack: ModelPack) = withContext(Dispatchers.IO) {
        if (isInstalled(pack)) {
            _status.update { it + (pack to PackStatus.Installed) }
            return@withContext
        }
        val total = pack.totalSize
        var done = pack.files.filter { file(pack, it.fileName).isComplete(it) }.sumOf { it.expectedSize }
        _status.update { it + (pack to PackStatus.Downloading(done, total)) }
        try {
            packDir(pack).mkdirs()
            for (model in pack.files) {
                val target = file(pack, model.fileName)
                if (target.isComplete(model)) continue
                downloadFile(model, target) { fileBytes ->
                    _status.update { it + (pack to PackStatus.Downloading(done + fileBytes, total)) }
                }
                done += model.expectedSize
            }
            _status.update { it + (pack to PackStatus.Installed) }
        } catch (e: Throwable) {
            _status.update { it + (pack to PackStatus.Failed(e)) }
            throw e
        }
    }

    internal suspend fun downloadFile(model: ModelFile, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > model.expectedSize) {
            part.delete()
            offset = 0
        }
        val request = Request.Builder()
            .url(model.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while downloading ${model.url}")
            }
            if (response.code != 206 && offset > 0) {
                // Server ignored the range: start over.
                offset = 0
                part.delete()
            }
            val body = response.body
            RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                val buffer = ByteArray(256 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        offset += read
                        onProgress(offset)
                    }
                }
            }
        }
        if (part.length() != model.expectedSize) {
            throw IOException(
                "Size mismatch for ${model.fileName}: got ${part.length()}, expected ${model.expectedSize}",
            )
        }
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
    }

    private fun statusOnDisk(pack: ModelPack): PackStatus =
        if (isInstalled(pack)) PackStatus.Installed else PackStatus.NotInstalled

    private fun File.isComplete(model: ModelFile): Boolean = exists() && length() == model.expectedSize
}
