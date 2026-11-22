package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.translation.VoiceCatalog
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsReadAloudScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_read_aloud

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.translationPreferences }
        val readAloud = remember { context.appGraph.readAloud }
        val defaultLabel = stringResource(MR.strings.pref_read_aloud_default_engine)
        val voices = readAloud.voices
        val voiceStates by voices.states.collectAsState()
        val selectedEngine by prefs.ttsEngine.collectAsState()
        val downloadedVoices = VoiceCatalog.all.filter { voices.isDownloaded(it) }
        val offlineSuffix = stringResource(MR.strings.pref_read_aloud_offline_suffix)
        val engines = remember(defaultLabel, downloadedVoices.map { it.id }) {
            readAloud.engines().mapValues { (key, label) -> if (key.isEmpty()) defaultLabel else label } +
                downloadedVoices.associate { (VoiceCatalog.PREFIX + it.id) to (it.title + offlineSuffix) }
        }
        val selectedVoice = VoiceCatalog.selected(selectedEngine)
        val languages = remember(defaultLabel) { mapOf("" to defaultLabel) + READ_ALOUD_LANGUAGES }
        val sample = stringResource(MR.strings.pref_read_aloud_test_sentence)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_read_aloud_voice),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_read_aloud_info)),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.ttsEngine,
                        entries = engines,
                        title = stringResource(MR.strings.pref_read_aloud_engine),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.ttsLanguage,
                        entries = languages,
                        title = stringResource(MR.strings.pref_read_aloud_language),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.ttsSpeaker,
                        title = stringResource(MR.strings.pref_read_aloud_speaker),
                        subtitle = stringResource(MR.strings.pref_read_aloud_speaker_summary),
                        valueText = { if (it < 0) defaultLabel else (it + 1).toString() },
                        valueRange = -1..((selectedVoice?.speakers ?: 1) - 1),
                        visible = selectedVoice != null && selectedVoice.speakers > 1,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.ttsSpeechRate,
                        title = stringResource(MR.strings.pref_read_aloud_speed),
                        valueText = { stringResource(MR.strings.pref_read_aloud_percent, it) },
                        valueRange = 50..200 step 10,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_read_aloud_test),
                        onClick = { readAloud.test(sample) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_read_aloud_offline_voices),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.pref_read_aloud_offline_voices_summary),
                    ),
                ) + VoiceCatalog.all.map { voice ->
                    val state = voiceStates[voice.id]
                    val busy = state?.progress != null
                    val ready = voices.isDownloaded(voice)
                    Preference.PreferenceItem.TextPreference(
                        title = voice.title,
                        subtitle = when {
                            state?.error != null -> state.error
                            busy -> state.progress
                            ready -> stringResource(MR.strings.pref_read_aloud_voice_ready)
                            else -> voice.description + "\n" +
                                stringResource(MR.strings.pref_translation_not_downloaded, voice.approxSize)
                        },
                        onClick = {
                            when {
                                busy -> voices.cancel(voice)
                                ready -> {
                                    voices.delete(voice)
                                    if (selectedEngine == VoiceCatalog.PREFIX + voice.id) prefs.ttsEngine.set("")
                                }
                                else -> voices.download(voice)
                            }
                        },
                    )
                },
            ),
        )
    }
}

private val READ_ALOUD_LANGUAGES = mapOf(
    "en" to "English",
    "ko" to "Korean",
    "ja" to "Japanese",
    "zh" to "Chinese",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ru" to "Russian",
    "tr" to "Turkish",
    "ar" to "Arabic",
    "hi" to "Hindi",
    "bn" to "Bengali",
    "id" to "Indonesian",
    "th" to "Thai",
    "vi" to "Vietnamese",
)
