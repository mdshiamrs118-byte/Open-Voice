package mihon.translation

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.GeneratedAudio
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Speaks translated text boxes through Android's TextToSpeech and frames the box being read. */
@Inject
@SingleIn(AppScope::class)
class ReadAloud(
    private val context: Context,
    private val preferences: TranslationPreferences,
    val voices: NeuralVoices,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var neuralJob: Job? = null
    private var track: AudioTrack? = null

    /** Called on the main thread when a spoken box ends by itself. */
    var onFinished: (() -> Unit)? = null

    private var framedView: ReaderPageImageView? = null
    private var utteranceCounter = 0L
    private var currentUtterance: String? = null

    fun toggle(): Boolean {
        val value = !mutableActive.value
        mutableActive.value = value
        ReadAloudDisplay.active = value
        ReadAloudDisplay.language = preferences.sourceLanguage.get()
        if (!value) release()
        return value
    }

    fun speak(view: ReaderPageImageView, box: TextBox, autoLocale: Locale) {
        val locale = chosenLocale() ?: autoLocale
        clearFrame()
        framedView = view
        view.readAloudBox = RectF(box.left, box.top, box.right, box.bottom)
        val utterance = "box-${utteranceCounter++}"
        currentUtterance = utterance
        val voice = neuralVoiceFor(locale)
        if (voice != null) {
            speakNeural(voice, box.text, locale, utterance)
            return
        }
        stopNeural()
        withTts { engine ->
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                context.toast("This speech engine has no ${locale.displayLanguage} voice installed.")
                clearFrame()
                return@withTts
            }
            engine.setSpeechRate(preferences.ttsSpeechRate.get() / 100f)
            engine.speak(box.text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        }
    }

    fun test(text: String) {
        clearFrame()
        val locale = chosenLocale() ?: Locale.ENGLISH
        val voice = neuralVoiceFor(locale)
        if (voice != null) {
            speakNeural(voice, text, locale, "test")
            return
        }
        stopNeural()
        withTts { engine ->
            engine.setLanguage(chosenLocale() ?: Locale.ENGLISH)
            engine.setSpeechRate(preferences.ttsSpeechRate.get() / 100f)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    private fun chosenLocale(): Locale? =
        preferences.ttsLanguage.get().takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)

    fun stop() {
        tts?.stop()
        stopNeural()
        clearFrame()
    }

    /** The downloaded neural voice picked in the settings, if it can read [locale]. */
    private fun neuralVoiceFor(locale: Locale): VoiceInfo? =
        VoiceCatalog.selected(preferences.ttsEngine.get())
            ?.takeIf { voices.isDownloaded(it) && locale.language in it.languages }

    private fun speakNeural(voice: VoiceInfo, text: String, locale: Locale, utterance: String) {
        tts?.stop()
        stopNeural()
        val speaker = preferences.ttsSpeaker.get().takeIf { it >= 0 } ?: voice.defaultSpeaker
        val speed = preferences.ttsSpeechRate.get() / 100f
        neuralJob = scope.launch {
            val audio = try {
                withContext(Dispatchers.Default) { voices.synthesize(voice, text, locale.language, speaker, speed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.toast("The voice failed: ${e.message ?: e.javaClass.simpleName}")
                finished(utterance)
                return@launch
            }
            play(audio, utterance)
        }
    }

    private fun play(audio: GeneratedAudio, utterance: String) {
        val samples = audio.samples
        if (samples.isEmpty()) {
            finished(utterance)
            return
        }
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Float.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        created.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        created.setNotificationMarkerPosition(samples.size)
        created.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = finished(utterance)

                override fun onPeriodicNotification(t: AudioTrack) = Unit
            },
            mainHandler,
        )
        track = created
        created.play()
    }

    private fun stopNeural() {
        neuralJob?.cancel()
        neuralJob = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    /** The installed text-to-speech engines by package name, with "" for the system default. */
    @Suppress("DEPRECATION")
    fun engines(): Map<String, String> {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        val installed = services
            .associate { it.serviceInfo.packageName to it.loadLabel(pm).toString() }
            .toList()
            .sortedBy { it.second.lowercase() }
        return linkedMapOf("" to "") + installed
    }

    private fun clearFrame() {
        currentUtterance = null
        framedView?.readAloudBox = null
        framedView = null
    }

    private fun release() {
        stopNeural()
        scope.launch(Dispatchers.Default) { voices.release() }
        clearFrame()
        tts?.shutdown()
        tts = null
        ttsEngine = null
        ready = false
        pending = null
    }

    private fun withTts(action: (TextToSpeech) -> Unit) {
        val wanted = preferences.ttsEngine.get().takeUnless { it.startsWith(VoiceCatalog.PREFIX) }.orEmpty()
        if (tts != null && ttsEngine != wanted) release()
        val existing = tts
        if (existing != null && ready) {
            action(existing)
            return
        }
        pending = { tts?.let(action) }
        if (existing == null) start(wanted)
    }

    private fun start(engine: String) {
        ttsEngine = engine
        lateinit var created: TextToSpeech
        val onInit = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (status != TextToSpeech.SUCCESS) {
                    created.shutdown()
                    if (tts === created) tts = null
                    if (engine.isNotEmpty()) {
                        context.toast("That speech engine failed to start. Using the default engine.")
                        preferences.ttsEngine.set("")
                        start("")
                    } else {
                        context.toast("Text-to-speech failed to start.")
                        pending = null
                    }
                    return@post
                }
                created.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit

                        override fun onDone(utteranceId: String) = finished(utteranceId)

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String) = finished(utteranceId)
                    },
                )
                ready = true
                pending?.invoke()
                pending = null
            }
        }
        created = if (engine.isEmpty()) {
            TextToSpeech(context, onInit)
        } else {
            TextToSpeech(context, onInit, engine)
        }
        tts = created
    }

    private fun finished(utteranceId: String) {
        mainHandler.post {
            if (utteranceId == currentUtterance) {
                clearFrame()
                onFinished?.invoke()
            }
        }
    }
}
