package mihon.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class OnlineConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val sourceLanguage: Preference<String> = preferenceStore.getString("pref_translation_language", "ko")

    val engine: Preference<String> = preferenceStore.getString("pref_translation_engine", "mlkit")

    val fallbackToDuckDuckGo: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_fallback", false)

    val markFallback: Preference<Boolean> = preferenceStore.getBoolean("pref_translation_mark_fallback", true)

    val webTimeoutSeconds: Preference<Int> = preferenceStore.getInt("pref_translation_web_timeout", 90)

    val apiBaseUrl: Preference<String> = preferenceStore.getString(
        Preference.privateKey("pref_translation_api_base"),
        "https://api.openai.com/v1",
    )

    val apiKey: Preference<String> = preferenceStore.getString(Preference.privateKey("pref_translation_api_key"), "")

    val apiModel: Preference<String> = preferenceStore.getString(Preference.privateKey("pref_translation_api_model"), "")

    val ttsEngine: Preference<String> = preferenceStore.getString("pref_read_aloud_engine", "")

    /** A language tag, or empty to follow the text: English for translations, the text language otherwise. */
    val ttsLanguage: Preference<String> = preferenceStore.getString("pref_read_aloud_language", "")

    /** Which speaker of a multi-speaker neural voice to use. */
    val ttsSpeaker: Preference<Int> = preferenceStore.getInt("pref_read_aloud_speaker", -1)

    val ttsSpeechRate: Preference<Int> = preferenceStore.getInt("pref_read_aloud_rate", 100)

    fun online() = OnlineConfig(apiBaseUrl.get(), apiKey.get(), apiModel.get())

    fun signedIn(siteId: String) = preferenceStore.getBoolean("pref_translation_signed_in_$siteId", false)

    fun isSignedIn(siteId: String): Boolean = signedIn(siteId).get()

    fun setSignedIn(siteId: String, value: Boolean) = signedIn(siteId).set(value)
}
