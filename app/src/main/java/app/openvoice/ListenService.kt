package app.openvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

/**
 * Foreground service that keeps the microphone open and feeds speech to OpenVoiceService.
 * The notification has a button that turns listening off and on without removing the notification.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_STOP = "app.openvoice.STOP"
        const val ACTION_TOGGLE = "app.openvoice.TOGGLE"
        private const val CHANNEL_ID = "open_voice_listening"
        private const val NOTIF_ID = 1

        /** The service is alive (notification visible). */
        @Volatile
        var running = false

        /** Listening switched off from the notification or the app, service still alive. */
        @Volatile
        var paused = false

        val listening: Boolean get() = running && !paused

        /** UI hooks (called on the main thread). */
        var onHeard: ((String) -> Unit)? = null
        var onState: (() -> Unit)? = null
        var onLevel: ((Float) -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ListenService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }

        /** Turns listening off / on while keeping the notification. */
        fun toggle(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_TOGGLE))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var errorStreak = 0

    // ---- beep muting -------------------------------------------------
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val beepStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM
    )
    private val mutedByUs = mutableSetOf<Int>()

    private fun muteBeep() {
        for (s in beepStreams) {
            if (s in mutedByUs) continue
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs.add(s)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun unmuteBeep() {
        for (s in mutedByUs.toList()) {
            try {
                audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        mutedByUs.clear()
    }

    private val startRunnable = Runnable { startListening() }
    private val unmuteRunnable = Runnable { unmuteBeep() }

    // ------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (running) setPaused(!paused) else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            Toast.makeText(this, "Microphone or speech recognition unavailable", Toast.LENGTH_LONG).show()
            try {
                startForeground(NOTIF_ID, buildNotification("Unavailable"))
            } catch (_: SecurityException) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification("Say “show numbers” or “scroll down”")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        paused = false
        OpenVoiceService.instance?.settle()
        startListening()
        onState?.invoke()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        unmuteBeep()
        OpenVoiceService.instance?.hideStatus()
        OpenVoiceService.instance?.clearOverlays()
        recognizer?.destroy()
        recognizer = null
        onState?.invoke()
        super.onDestroy()
    }

    private fun setPaused(p: Boolean) {
        paused = p
        handler.removeCallbacks(startRunnable)
        handler.removeCallbacks(unmuteRunnable)
        if (p) {
            recognizer?.destroy()
            recognizer = null
            unmuteBeep()
            OpenVoiceService.instance?.hideStatus()
            OpenVoiceService.instance?.clearOverlays()
        } else {
            errorStreak = 0
            OpenVoiceService.instance?.settle()
            startListening()
        }
        notifyNow(if (p) "Listening is off" else "Say “show numbers” or “scroll down”")
        onState?.invoke()
    }

    private fun notifyNow(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------

    private fun startListening() {
        if (!running || paused) return
        muteBeep() // silence the "ding" that plays when the recognizer starts
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            resetRecognizer()
            restart(1000)
        }
    }

    private fun restart(delayMs: Long) {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, delayMs)
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // start-beep has played by now; restore sound shortly after
            handler.removeCallbacks(unmuteRunnable)
            handler.postDelayed(unmuteRunnable, 300)
        }

        override fun onBeginningOfSpeech() {
            errorStreak = 0
            OpenVoiceService.instance?.onSpeechStart()
        }

        override fun onEndOfSpeech() {
            // mute again before the "stop" beep
            handler.removeCallbacks(unmuteRunnable)
            muteBeep()
        }

        override fun onRmsChanged(rmsdB: Float) {
            onLevel?.invoke(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!p.isNullOrBlank()) OpenVoiceService.instance?.onPartial(p)
        }

        override fun onError(error: Int) {
            if (paused) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopSelf()
                    return
                }
                // Normal "nobody spoke" cases: restart quietly and immediately.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    OpenVoiceService.instance?.settle()
                    restart(100)
                }
                // Real errors: back off so we never spin in a tight loop.
                else -> {
                    errorStreak++
                    resetRecognizer()
                    val delay = minOf(1000L * errorStreak, 5000L)
                    if (delay > 1500) {
                        handler.removeCallbacks(unmuteRunnable)
                        unmuteBeep()
                    }
                    restart(delay)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (paused) return
            errorStreak = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isNotEmpty()) {
                val svc = OpenVoiceService.instance
                val feedback = svc?.handleSpeech(list)
                    ?: "“${list[0]}” → enable the accessibility service first"
                if (feedback.isNotEmpty()) {
                    onHeard?.invoke(feedback)
                    notifyNow(feedback)
                    svc?.setStatus(feedback, 2500)
                } else {
                    svc?.settle()
                }
            }
            restart(100)
        }
    }

    // ------------------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ListenService::class.java).setAction(ACTION_STOP), flags
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, ListenService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Open Voice · off" else "Open Voice · listening")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, if (paused) "Turn on" else "Turn off", toggle)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
