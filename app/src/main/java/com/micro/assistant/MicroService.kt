package com.micro.assistant

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class MicroService : AccessibilityService() {

    // MainActivity reads MicroService.instance and calls listen() on it.
    companion object {
        var instance: MicroService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                ttsReady = true
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Voice out ----------

    fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "micro")
    }

    // ---------- Voice in ----------
   fun listen() {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                say("Speech recognition is not available on this phone")
                return@post
            }
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }
            r.startListening(intent)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) say("I didn't catch that") else handle(text)
        } 
        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                say("Please allow the microphone first")
            } else {
                say("I didn't catch that")
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- Commands ---------- 
    private fun handle(raw: String) {
        val original = raw.trim()
        val t = original.lowercase(Locale.getDefault()).replace(".", "")
        try {
            when {
                t.startsWith("open ") -> openApp(t.removePrefix("open ").trim())
                t == "go back" || t == "back" -> global(GLOBAL_ACTION_BACK, "Going back")
                t == "go home" || t == "home" -> global(GLOBAL_ACTION_HOME, "Going home")
                t == "recent apps" || t == "recents" -> global(GLOBAL_ACTION_RECENTS, "Recent apps")
                t == "scroll down" -> scroll(forward = true)
                t == "scroll up" -> scroll(forward = false)
                t.startsWith("click ") -> clickText(t.removePrefix("click ").trim())
                t.startsWith("tap ") -> clickText(t.removePrefix("tap ").trim())
                t.startsWith("type ") -> typeText(original.drop(5))
                t.contains("alarm") -> setAlarm(t)
                else -> say("I heard $original. I don't know that command yet")
            }
        } catch (e: Exception) {
            say("Sorry, that didn't work")
        }
    } 
    private fun global(action: Int, message: String) {
        if (performGlobalAction(action)) say(message) else say("I couldn't do that")
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.getDefault()).contains(name)
        }
        val intent = match?.activityInfo?.packageName?.let { pm.getLaunchIntentForPackage(it) }
        if (intent == null) {
            say("I couldn't find $name")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        say("Opening $name")
    } 
    private fun scroll(forward: Boolean) {
        val root = rootInActiveWindow ?: return say("I can't read the screen")
        val node = findScrollable(root)
        if (node == null) {
            say("Nothing to scroll")
            return
        }
        node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    } 
   private fun clickText(label: String) {
        val root = rootInActiveWindow ?: return say("I can't read the screen")
        for (node in root.findAccessibilityNodeInfosByText(label)) {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                say("Clicked $label")
                return
            }
        }
        say("I couldn't find $label on the screen")
    }

    // Replaces the text in the box that currently has focus.
    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return say("I can't read the screen")
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null) {
            say("Tap a text box first")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } 
    // Understands: "set alarm for 7 am", "alarm 7:30 pm", "set an alarm 6 45 am".
    private fun setAlarm(t: String) {
        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?").find(t)
        if (m == null) {
            say("Tell me the time, for example set alarm for 7 30 am")
            return
        }
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) {
            say("That time doesn't look right")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            say("Alarm set for %d:%02d".format(hour, minute))
        } catch (e: ActivityNotFoundException) {
            say("I couldn't find a clock app")
        }
    }
}
