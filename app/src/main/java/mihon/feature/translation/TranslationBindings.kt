package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.feature.translation.core.ModelStore
import mihon.feature.translation.core.TranslationManager
import mihon.feature.translation.core.cache.PageTranslationCache
import java.io.File

/**
 * Provides the framework-independent translation core to the app graph.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object TranslationBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesTranslationManager(
        context: Context,
        networkHelper: NetworkHelper,
        preferences: TranslationPreferences,
    ): TranslationManager = TranslationManager(
        modelStore = ModelStore(File(context.filesDir, "translation_models"), networkHelper.client),
        cache = PageTranslationCache(File(context.cacheDir, "translated_pages")),
        client = networkHelper.client,
        configProvider = preferences::toConfig,
    )
}
