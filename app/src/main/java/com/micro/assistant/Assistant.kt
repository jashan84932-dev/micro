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
            Extras.init(a)
            Comms.init(a)
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
    private var awakeUntil = 0L
    private var bargeRec: SpeechRecognizer? = null

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

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!active || mode != OrbView.LISTEN) return
            generation++
            recognizer?.cancel()
            val msg = if (Prefs.jarvisMode) "सर, बैटरी कम हो रही है। चार्जर लगा लीजिए।" else "अरे, बैटरी कम हो रही है, चार्जर लगा लो!"
            log("${Prefs.friendName}: $msg")
            state("Speaking…", OrbView.SPEAK)
            speakThenListen(msg)
        }
    }
    private var batteryRegistered = false

    private var phoneRegistered = false
    private var lastRingNumber = ""
    private var ringHandled = false
    private val phoneReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val st = i?.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE) ?: return
            if (st == android.telephony.TelephonyManager.EXTRA_STATE_IDLE || st == android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK) {
                ringHandled = false
                lastRingNumber = ""
                return
            }
            if (st != android.telephony.TelephonyManager.EXTRA_STATE_RINGING || ringHandled) return
            val n = i?.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
            if (n.isNotBlank()) lastRingNumber = n
            // the number often arrives in a second broadcast, so wait a moment
            main.postDelayed({
                if (ringHandled || !active) return@postDelayed
                ringHandled = true
                val msg = Comms.incomingCallText(lastRingNumber) ?: return@postDelayed
                generation++
                recognizer?.cancel()
                awakeUntil = System.currentTimeMillis() + 25_000
                log("${Prefs.friendName}: $msg")
                state("Speaking…", OrbView.SPEAK)
                speakThenListen(msg)
            }, 1200)
        }
    }

    private val habitTick = object : Runnable {
        override fun run() {
            if (!active) return
            if (mode == OrbView.LISTEN) {
                val q = Comms.habitOffer()
                if (q != null) {
                    generation++
                    recognizer?.cancel()
                    awakeUntil = System.currentTimeMillis() + 25_000
                    log("${Prefs.friendName}: $q")
                    state("Speaking…", OrbView.SPEAK)
                    speakThenListen(q)
                }
            }
            main.postDelayed(this, 60_000)
        }
    }

    fun startConversation() {
        main.post {
            if (active) return@post
            if (!batteryRegistered) {
                try {
                    app.registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_LOW))
                    batteryRegistered = true
                } catch (e: Exception) {
                    // alerts are optional
                }
            }
            if (!phoneRegistered) {
                try {
                    app.registerReceiver(phoneReceiver, android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED))
                    phoneRegistered = true
                } catch (e: Exception) {
                    // call announcements are optional
                }
            }
            main.postDelayed(habitTick, 60_000)
            active = true
            failures = 0
            awakeUntil = System.currentTimeMillis() + 30_000
            acquireWake()
            listen()
        }
    }

    fun stopConversation() {
        main.post {
            if (batteryRegistered) {
                try { app.unregisterReceiver(batteryReceiver) } catch (e: Exception) { /* ignore */ }
                batteryRegistered = false
            }
            if (phoneRegistered) {
                try { app.unregisterReceiver(phoneReceiver) } catch (e: Exception) { /* ignore */ }
                phoneRegistered = false
            }
            main.removeCallbacks(habitTick)
            active = false
            generation++
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            stopBarge()
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
            // stop waiting sooner after you finish speaking = faster replies
            putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 900L)
            putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 700L)
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

    /** Words that count as saying the wake word "Hey Micro" (speech recognisers spell it many ways). */
    private fun wakeWords(): List<String> {
        val base = mutableListOf("micro", "maikro", "mikro", "mykro", "माइक्रो", "मायक्रो", "माईक्रो", "मैक्रो", "माइको", "माइकरो")
        val n = Prefs.friendName.trim().lowercase(Locale.getDefault())
        if (n.length >= 3 && n !in base) base.add(n)
        return base
    }

    private fun heard(heardText: String) {
        var text = heardText
        if (Prefs.wakeWord && System.currentTimeMillis() > awakeUntil) {
            val low = text.lowercase(Locale.getDefault())
            val w = wakeWords().firstOrNull { low.contains(it) }
            if (w == null) {
                relisten(250) // not for Micro - ignore silently
                return
            }
            // drop "hey micro" and keep the rest as the command
            val at = low.indexOf(w)
            var rest = (text.substring(0, at) + " " + text.substring(at + w.length)).trim()
            rest = rest.replace(Regex("(?i)^(hey|hi|hello|ok|okay|अरे|ओए|हे|हेलो|हैलो)\\s+"), "").trim()
            if (rest.isBlank()) {
                awakeUntil = System.currentTimeMillis() + 25_000
                log("You: $text")
                val yes = "हाँ, बोलो।"
                log("${Prefs.friendName}: $yes")
                state("Speaking…", OrbView.SPEAK)
                voice.speak(yes) { if (active) listen() }
                return
            }
            text = rest
        }
        awakeUntil = System.currentTimeMillis() + 25_000
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
        // if the answer takes long, say something so it does not feel dead
        main.postDelayed({
            if (gen == generation && mode == OrbView.THINK) voice.speak("हम्म, एक सेकंड।") { }
        }, 3000)
        Thread {
            val reply = try {
                Extras.chain(hands, text) { brain.reply(it) }
                    ?: Local.handle(hands, text)?.also { Habit.record(text) }
                    ?: brain.reply(text)
            } catch (e: Exception) {
                "सॉरी, कुछ गड़बड़ हो गई।"
            }
            main.post {
                if (gen != generation) return@post
                log("${Prefs.friendName}: $reply")
                state("Speaking…", OrbView.SPEAK)
                speakThenListen(reply)
            }
        }.start()
    }

    private fun speakThenListen(reply: String) {
        val my = generation
        voice.speak(reply) {
            stopBarge()
            awakeUntil = System.currentTimeMillis() + 25_000
            if (active) listen() else state("Ready", OrbView.IDLE)
        }
        startBarge(my)
    }

    /** Called by the camera screen when a photo is ready. */
    fun describePhoto(jpegBase64: String) {
        main.post {
            if (!active) return@post
            generation++
            recognizer?.cancel()
            state("Thinking…", OrbView.THINK)
        }
        val gen = generation
        Thread {
            val reply = try { brain.describeImage(jpegBase64) } catch (e: Exception) { "फोटो नहीं देख पाया।" }
            main.post {
                if (gen != generation) return@post
                log("${Prefs.friendName}: $reply")
                state("Speaking…", OrbView.SPEAK)
                recognizer?.cancel()
                speakThenListen(reply)
            }
        }.start()
    }

    // ---------- Interrupting while Micro speaks ----------

    private val STOP_WORDS = setOf("रुको", "रुक", "ruko", "ruk", "चुप", "chup", "stop", "बस", "bas", "ठहरो", "thehro", "रुकिए")

    private fun startBarge(gen: Int) {
        if (!Prefs.bargeIn || !active) return
        main.post {
            if (!active || gen != generation) return@post
            try {
                bargeRec?.destroy()
                val r = SpeechRecognizer.createSpeechRecognizer(app)
                bargeRec = r
                r.setRecognitionListener(object : RecognitionListener {
                    private fun check(b: Bundle?) {
                        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                        val tokens = list.joinToString(" ").lowercase(Locale.getDefault()).split(Regex("[\\s,।.!?]+"))
                        if (tokens.any { it in STOP_WORDS }) {
                            stopBarge()
                            generation++
                            voice.stop()
                            state("Listening…", OrbView.LISTEN)
                            relisten(200)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        check(results)
                        restartBarge(gen)
                    }
                    override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
                    override fun onError(error: Int) { restartBarge(gen) }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                })
            } catch (e: Exception) {
                // interrupting is optional
            }
        }
    }

    private fun restartBarge(gen: Int) {
        main.postDelayed({
            if (active && gen == generation && mode == OrbView.SPEAK && bargeRec != null) startBarge(gen)
        }, 400)
    }

    private fun stopBarge() {
        try {
            bargeRec?.cancel()
            bargeRec?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        bargeRec = null
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
