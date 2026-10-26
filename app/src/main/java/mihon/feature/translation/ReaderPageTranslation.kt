package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.feature.translation.core.ModelPack
import mihon.feature.translation.core.ModelsNotInstalledException
import mihon.feature.translation.core.TranslationManager
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * Bridge between the reader's page pipeline and [TranslationManager].
 */
@Inject
@SingleIn(AppScope::class)
class ReaderPageTranslation(
    private val manager: TranslationManager,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    sealed interface Error {
        data class ModelsMissing(val packs: Set<ModelPack>) : Error
        data class Failed(val message: String?) : Error
    }

    private val _errors =
        MutableSharedFlow<Error>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Problems hit while translating pages; the reader shows them as toasts. */
    val errors: SharedFlow<Error> = _errors

    private val downloads = HashMap<ModelPack, Job>()

    /**
     * Returns the translated page, or [imageSource] untouched when there is nothing to translate,
     * the image is animated, or translation failed (the failure is published on [errors]).
     */
    suspend fun translate(imageSource: BufferedSource): BufferedSource {
        if (ImageUtil.isAnimatedAndSupported(imageSource)) return imageSource
        val bytes = imageSource.peek().readByteArray()
        return try {
            val translated = manager.translate(bytes) ?: return imageSource
            Buffer().write(translated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelsNotInstalledException) {
            _errors.tryEmit(Error.ModelsMissing(e.missing))
            imageSource
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Page translation failed" }
            _errors.tryEmit(Error.Failed(e.message))
            imageSource
        }
    }

    /** Frees the loaded models without blocking the caller. */
    fun releaseModels() {
        scope.launch { manager.release() }
    }

    /** Starts (or resumes) a model pack download that outlives the settings screen. */
    @Synchronized
    fun startDownload(pack: ModelPack) {
        if (downloads[pack]?.isActive == true) return
        downloads[pack] = scope.launch {
            try {
                manager.modelStore.download(pack)
            } catch (e: CancellationException) {
                manager.modelStore.refresh()
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Model download failed: ${pack.id}" }
            }
        }
    }

    @Synchronized
    fun cancelDownload(pack: ModelPack) {
        downloads.remove(pack)?.cancel()
    }
}
