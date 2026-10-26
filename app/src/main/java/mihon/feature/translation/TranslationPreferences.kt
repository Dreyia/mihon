package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.feature.translation.core.InpaintMode
import mihon.feature.translation.core.PageEngine
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TextEngine
import mihon.feature.translation.core.TranslationConfig
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {
    /** Reader toggle: translate pages while reading. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("translation_enabled", false)

    /** Pre-translate every page of a chapter right after it is downloaded. */
    val translateOnDownload: Preference<Boolean> = preferenceStore.getBoolean("translation_on_download", false)

    val pageEngine: Preference<PageEngine> = preferenceStore.getEnum("translation_page_engine", PageEngine.ON_DEVICE)

    val sourceLanguage: Preference<SourceLanguage> =
        preferenceStore.getEnum("translation_source_language", SourceLanguage.JAPANESE)

    /** ISO 639-1 code. The on-device translator only ships English models. */
    val targetLanguage: Preference<String> = preferenceStore.getString("translation_target_language", "en")

    val textEngine: Preference<TextEngine> = preferenceStore.getEnum("translation_text_engine", TextEngine.ON_DEVICE)

    val inpaintMode: Preference<InpaintMode> = preferenceStore.getEnum("translation_inpaint_mode", InpaintMode.MODEL)

    val translateFreeText: Preference<Boolean> = preferenceStore.getBoolean("translation_free_text", false)

    /** Percent, 100 = layout default. */
    val fontScale: Preference<Int> = preferenceStore.getInt("translation_font_scale", 100)

    val serverUrl: Preference<String> = preferenceStore.getString("translation_server_url", "")

    val serverTranslator: Preference<String> = preferenceStore.getString("translation_server_translator", "sugoi")

    val openAiBaseUrl: Preference<String> = preferenceStore.getString("translation_openai_url", "")

    val openAiModel: Preference<String> = preferenceStore.getString("translation_openai_model", "")

    /** Stored under a private key so it is never written to backups. */
    val openAiApiKey: Preference<String> =
        preferenceStore.getString(Preference.privateKey("translation_openai_key"), "")

    fun toConfig(): TranslationConfig = TranslationConfig(
        pageEngine = pageEngine.get(),
        sourceLanguage = sourceLanguage.get(),
        targetLanguage = targetLanguage.get().trim().ifBlank { "en" },
        textEngine = textEngine.get(),
        inpaintMode = inpaintMode.get(),
        translateFreeText = translateFreeText.get(),
        fontScale = fontScale.get() / 100f,
        serverUrl = serverUrl.get().trim(),
        serverTranslator = serverTranslator.get().trim().ifBlank { "sugoi" },
        openAiBaseUrl = openAiBaseUrl.get().trim(),
        openAiApiKey = openAiApiKey.get().trim(),
        openAiModel = openAiModel.get().trim(),
    )
}
