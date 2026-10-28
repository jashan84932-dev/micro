package com.micro.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
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

    companion object { var instance: MicroService? = null }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "micro") }

    // ---------- Voice input ----------
    fun listen() {
        Handler(Looper.getMainLooper()).post {
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }
        )
    }
    }
