package mihon.feature.translation

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.feature.translation.core.ModelsNotInstalledException
import mihon.feature.translation.core.TranslationManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * Translates every page of a downloaded chapter into the page-translation cache so the chapter
 * opens instantly (and offline) with the Translate toggle on. Queued by the downloader when
 * "Translate chapters after download" is enabled; jobs run one at a time.
 */
class ChapterTranslationJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var getManga: GetManga

    @Inject private lateinit var getChapter: GetChapter

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject private lateinit var downloadProvider: DownloadProvider

    @Inject private lateinit var translationManager: TranslationManager

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressNotification(
            title = inputData.getString(KEY_TITLE) ?: "",
            done = 0,
            total = 0,
        )
        return ForegroundInfo(
            Notifications.ID_TRANSLATION_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1L)
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        val manga = getManga.await(mangaId) ?: return Result.failure()
        val chapter = getChapter.await(chapterId) ?: return Result.failure()
        val source = sourceManager.get(manga.source) ?: return Result.failure()
        val title = "${manga.title} · ${chapter.name}"

        setForegroundSafely()

        val loader = DownloadPageLoader(ReaderChapter(chapter), manga, source, downloadManager, downloadProvider)
        try {
            val pages = loader.getPages()
            pages.forEachIndexed { index, page ->
                applicationContext.notify(
                    Notifications.ID_TRANSLATION_PROGRESS,
                    progressNotification(title, index, pages.size),
                )
                loader.loadPage(page)
                val stream = page.stream ?: return@forEachIndexed
                val bytes = stream().use { it.readBytes() }
                translationManager.translate(bytes)
            }
            notifyDone(title, mangaId, chapterId)
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelsNotInstalledException) {
            notifyFailed(title, applicationContext.stringResource(MR.strings.translation_error_models_missing))
            return Result.failure()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Chapter translation failed: $title" }
            notifyFailed(title, e.message ?: e.javaClass.simpleName)
            return Result.failure()
        } finally {
            loader.recycle()
            applicationContext.cancelNotification(Notifications.ID_TRANSLATION_PROGRESS)
        }
    }

    private fun progressNotification(title: String, done: Int, total: Int) =
        applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.translation_notification_translating))
            setContentText(
                if (total > 0) {
                    applicationContext.stringResource(MR.strings.translation_notification_progress, title, done, total)
                } else {
                    title
                },
            )
            setSmallIcon(android.R.drawable.stat_sys_download)
            setProgress(total, done, total == 0)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()

    private fun notifyDone(title: String, mangaId: Long, chapterId: Long) {
        val intent = PendingIntent.getActivity(
            applicationContext,
            chapterId.toInt(),
            ReaderActivity.newIntent(applicationContext, mangaId, chapterId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        applicationContext.notify(Notifications.ID_TRANSLATION_COMPLETE, Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.translation_notification_done))
            setContentText(title)
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setContentIntent(intent)
            setAutoCancel(true)
        }
    }

    private fun notifyFailed(title: String, reason: String) {
        applicationContext.notify(Notifications.ID_TRANSLATION_COMPLETE, Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setContentTitle(applicationContext.stringResource(MR.strings.translation_notification_failed, title))
            setContentText(reason)
            setSmallIcon(android.R.drawable.stat_notify_error)
            setAutoCancel(true)
        }
    }

    companion object {
        private const val TAG = "ChapterTranslation"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_CHAPTER_ID = "chapter_id"
        private const val KEY_TITLE = "title"

        /** Queues the chapter behind any translation already running. */
        fun start(context: Context, mangaId: Long, chapterId: Long, title: String) {
            val request = OneTimeWorkRequestBuilder<ChapterTranslationJob>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_MANGA_ID to mangaId,
                        KEY_CHAPTER_ID to chapterId,
                        KEY_TITLE to title,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
