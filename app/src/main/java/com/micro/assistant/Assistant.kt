package com.micro.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Ties everything together: listens (Hindi + English), runs instant commands or asks the
 * brain, speaks the answer, and keeps doing that again and again - even when the app is
 * minimised - until you press Stop (or say "stop listening").
 */
class Assistant private constructor(private val app: Context) {

    companion object {
        @Volatile private var inst: Assistant? = null
        fun get(ctx: Context): Assistant {
            val a = ctx.applicationContext
            Prefs.init(a)
            Tv.init(a)
            return inst ?: synchronized(this) { inst ?: Assistant(a).also { inst = it } }
        }

        /** Saying one of these switches Micro off completely. */
        private val OFF_PHRASES = listOf(
            "stop listening", "listening band", "sunna band", "सुनना बंद", "सुनना बन्द",
            "बंद हो जाओ", "बंद हो जा", "good night", "गुड नाइट", "turn off micro", "micro band", "माइक्रो बंद"
        )
    }

    val hands = Hands(app)
    private val brain = Brain(hands)
    val voice = Voice(app)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var failures = 0
    private var generation = 0
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var active = false
        private set

    /** How loud you are right now (0..1) - the orb on the home screen moves with it. */
    @Volatile var level = 0f

    /** What Micro is doing now: one of OrbView.IDLE / LISTEN / THINK / SPEAK. */
    @Volatile var mode = OrbView.IDLE
        private set

    val transcript = StringBuilder()
    var onChange: (() -> Unit)? = null // called on the main thread
    var status = "Ready"
        private set

    private fun log(line: String) {
        transcript.append(line).append("\n\n")
        if (transcript.length > 20000) transcript.delete(0, transcript.length - 15000)
        onChange?.invoke()
    }

    private fun state(s: String, m: Int) {
        status = s
        mode = m
        onChange?.invoke()
    }

    // ---------- Public controls ----------

    fun startConversation() {
        main.post {
            if (active) return@post
            active = true
            failures = 0
            acquireWake()
            listen()
        }
    }

    fun stopConversation() {
        main.post {
            active = false
            generation++
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            voice.stop()
            releaseWake()
            state("Ready", OrbView.IDLE)
            ListenService.stop(app)
        }
    }

    // ---------- Listening ----------

    private fun listen() {
        if (!active) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Please allow the microphone first.")
            stopConversation()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            log("This phone has no speech recognition.")
            stopConversation()
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        r.setRecognitionListener(listener)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        state("Listening…", OrbView.LISTEN)
        r.startListening(intent)
    }

    private fun relisten(delayMs: Long) {
        if (!active) return
        state("Listening…", OrbView.LISTEN)
        main.postDelayed({ if (active) listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (!active) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                relisten(300)
                return
            }
            failures = 0
            heard(text.trim())
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log("Please allow the microphone first.")
                    stopConversation()
                }
                // plain silence: just start listening again
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    failures = 0
                    relisten(250)
                }
                // anything else (busy, network, client): wait a little longer each time, never give up
                else -> {
                    failures++
                    relisten((600L * failures).coerceAtMost(4000L))
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun heard(text: String) {
        log("You: $text")
        val lower = text.lowercase(Locale.getDefault())
        if (text.length < 30 && OFF_PHRASES.any { lower.contains(it) }) {
            val bye = "ठीक है, बाय! जब मन करे, फिर बुला लेना।"
            log("${Prefs.friendName}: $bye")
            state("Speaking…", OrbView.SPEAK)
            voice.speak(bye) { stopConversation() }
            return
        }
        think(text)
    }

    // ---------- Thinking and speaking ----------

    private fun think(text: String) {
        val gen = ++generation
        state("Thinking…", OrbView.THINK)
        Thread {
            val reply = try {
                Local.handle(hands, text) ?: brain.reply(text)
            } catch (e: Exception) {
                "सॉरी, कुछ गड़बड़ हो गई।"
            }
            main.post {
                if (gen != generation) return@post
                log("${Prefs.friendName}: $reply")
                state("Speaking…", OrbView.SPEAK)
                voice.speak(reply) {
                    if (active) listen() else state("Ready", OrbView.IDLE)
                }
            }
        }.start()
    }

    // ---------- Keep the CPU awake while listening ----------

    private fun acquireWake() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "micro:listening")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
            // not critical
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
    }
}
