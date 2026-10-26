package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.translation.ReaderPageTranslation
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.core.InpaintMode
import mihon.feature.translation.core.ModelPack
import mihon.feature.translation.core.PackStatus
import mihon.feature.translation.core.PageEngine
import mihon.feature.translation.core.SourceLanguage
import mihon.feature.translation.core.TextEngine
import mihon.feature.translation.core.TranslationManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.translationPreferences }
        val manager = remember { context.appGraph.translationManager }
        val pageTranslation = remember { context.appGraph.readerPageTranslation }
        val pageEngine by prefs.pageEngine.collectAsState()
        val textEngine by prefs.textEngine.collectAsState()
        // Read so the required-packs list below recomposes when these change.
        prefs.sourceLanguage.collectAsState().value
        prefs.inpaintMode.collectAsState().value
        val onDevice = pageEngine == PageEngine.ON_DEVICE

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.enabled,
                title = stringResource(MR.strings.pref_translate_pages),
                subtitle = stringResource(MR.strings.pref_translate_pages_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.translateOnDownload,
                title = stringResource(MR.strings.pref_translate_on_download),
                subtitle = stringResource(MR.strings.pref_translate_on_download_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.pageEngine,
                entries = mapOf(
                    PageEngine.ON_DEVICE to stringResource(MR.strings.translation_engine_on_device),
                    PageEngine.SERVER to stringResource(MR.strings.translation_engine_server),
                ),
                title = stringResource(MR.strings.pref_translation_page_engine),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.sourceLanguage,
                entries = mapOf(
                    SourceLanguage.JAPANESE to stringResource(MR.strings.translation_lang_japanese),
                    SourceLanguage.CHINESE to stringResource(MR.strings.translation_lang_chinese),
                ),
                title = stringResource(MR.strings.pref_translation_source_language),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.targetLanguage,
                title = stringResource(MR.strings.pref_translation_target_language),
                subtitle = stringResource(MR.strings.pref_translation_target_language_summary),
            ),
            onDeviceGroup(prefs, manager, pageTranslation, enabled = onDevice),
            serverGroup(prefs, enabled = !onDevice),
            llmGroup(prefs, enabled = onDevice && textEngine == TextEngine.OPENAI_COMPATIBLE),
            storageGroup(manager),
        )
    }

    @Composable
    private fun onDeviceGroup(
        prefs: TranslationPreferences,
        manager: TranslationManager,
        pageTranslation: ReaderPageTranslation,
        enabled: Boolean,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_translation_on_device),
            enabled = enabled,
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.textEngine,
                    entries = mapOf(
                        TextEngine.ON_DEVICE to stringResource(MR.strings.translation_text_engine_on_device),
                        TextEngine.OPENAI_COMPATIBLE to stringResource(MR.strings.translation_text_engine_openai),
                    ),
                    title = stringResource(MR.strings.pref_translation_text_engine),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.inpaintMode,
                    entries = mapOf(
                        InpaintMode.MODEL to stringResource(MR.strings.translation_inpaint_model),
                        InpaintMode.FLAT_FILL to stringResource(MR.strings.translation_inpaint_flat),
                    ),
                    title = stringResource(MR.strings.pref_translation_inpaint_mode),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.translateFreeText,
                    title = stringResource(MR.strings.pref_translation_free_text),
                    subtitle = stringResource(MR.strings.pref_translation_free_text_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.fontScale,
                    entries = listOf(80, 90, 100, 115, 130, 150).associateWith { "$it%" },
                    title = stringResource(MR.strings.pref_translation_font_scale),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_translation_models),
                ) {
                    ModelPackList(
                        manager = manager,
                        pageTranslation = pageTranslation,
                        required = prefs.toConfig().requiredPacks(),
                    )
                },
            ),
        )
    }

    @Composable
    private fun serverGroup(prefs: TranslationPreferences, enabled: Boolean): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_translation_server),
            enabled = enabled,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.serverUrl,
                    title = stringResource(MR.strings.pref_translation_server_url),
                    subtitle = stringResource(MR.strings.pref_translation_server_url_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.serverTranslator,
                    title = stringResource(MR.strings.pref_translation_server_translator),
                    subtitle = stringResource(MR.strings.pref_translation_server_translator_summary),
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun llmGroup(prefs: TranslationPreferences, enabled: Boolean): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_translation_llm),
            enabled = enabled,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.openAiBaseUrl,
                    title = stringResource(MR.strings.pref_translation_openai_url),
                    subtitle = stringResource(MR.strings.pref_translation_openai_url_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.openAiModel,
                    title = stringResource(MR.strings.pref_translation_openai_model),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.openAiApiKey,
                    title = stringResource(MR.strings.pref_translation_openai_key),
                    subtitle = null,
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun storageGroup(manager: TranslationManager): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var cacheSize by remember { mutableLongStateOf(manager.cache.size()) }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data_storage),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_translation_cache),
                    subtitle = stringResource(MR.strings.used_cache, Formatter.formatFileSize(context, cacheSize)),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            manager.cache.clear()
                            cacheSize = manager.cache.size()
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun ModelPackList(
        manager: TranslationManager,
        pageTranslation: ReaderPageTranslation,
        required: Set<ModelPack>,
    ) {
        val context = LocalContext.current
        val status by manager.modelStore.status.collectAsState()
        val requiredSize = Formatter.formatFileSize(context, required.sumOf { it.totalSize })
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(MR.strings.translation_models_note, requiredSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (pack in ModelPack.entries) {
                val state = status[pack] ?: PackStatus.NotInstalled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pack.displayName + if (pack in required) " *" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                MR.strings.translation_model_size_license,
                                Formatter.formatFileSize(context, pack.totalSize),
                                pack.license,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (state) {
                                PackStatus.Installed -> stringResource(MR.strings.translation_model_installed)
                                PackStatus.NotInstalled -> stringResource(MR.strings.translation_model_not_installed)
                                is PackStatus.Downloading -> stringResource(
                                    MR.strings.translation_model_downloading,
                                    (state.fraction * 100).toInt(),
                                )
                                is PackStatus.Failed -> stringResource(
                                    MR.strings.translation_model_failed,
                                    state.error.message ?: state.error.javaClass.simpleName,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state is PackStatus.Downloading) {
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                    when (state) {
                        PackStatus.Installed -> TextButton(onClick = { manager.modelStore.delete(pack) }) {
                            Text(stringResource(MR.strings.action_delete))
                        }
                        is PackStatus.Downloading -> TextButton(onClick = {
                            pageTranslation.cancelDownload(pack)
                        }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                        else -> TextButton(onClick = { pageTranslation.startDownload(pack) }) {
                            Text(stringResource(MR.strings.action_download))
                        }
                    }
                }
            }
        }
    }
}
