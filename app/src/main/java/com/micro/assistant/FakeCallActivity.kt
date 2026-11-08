package com.micro.assistant

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** A screen that looks like an incoming call. It is drawn by Micro itself, it is not a real call. */
class FakeCallActivity : Activity() {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val h = Handler(Looper.getMainLooper())
    private var seconds = 0
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var hang: TextView
    private var accepted = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(7703)
        } catch (e: Exception) {
            // ignore
        }
        val name = intent.getStringExtra("name") ?: "Mummy"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(40, 160, 40, 120)
        }
        fun tv(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        status = tv("Incoming call…", 16f, "#9AA5B1")
        root.addView(status)
        val avatar = tv(name.take(1).uppercase(Locale.getDefault()), 44f, "#FFFFFF", true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3D5AFE")) }
        }
        root.addView(avatar, LinearLayout.LayoutParams(260, 260).apply { topMargin = 90; gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(tv(name, 32f, "#FFFFFF", true).apply { setPadding(0, 50, 0, 0) })
        root.addView(tv("mobile", 15f, "#9AA5B1"))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun circle(label: String, color: String, click: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
            setOnClickListener { click() }
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lp = { LinearLayout.LayoutParams(190, 190).apply { setMargins(70, 0, 70, 0) } }
        buttons.addView(circle("✖", "#E53935") { finishCall() }, lp())
        buttons.addView(circle("✆", "#43A047") { accept(name) }, lp())
        root.addView(buttons)
        hang = circle("✖", "#E53935") { finishCall() }.apply { visibility = View.GONE }
        root.addView(hang, LinearLayout.LayoutParams(190, 190).apply { gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(FrameLayout(this).apply { addView(root) })
        ring()
        h.postDelayed({ if (!accepted) finishCall() }, 45_000)
    }

    private fun ring() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(this@FakeCallActivity, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // silent ring is fine
        }
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun stopRing() {
        try { player?.stop(); player?.release() } catch (e: Exception) { /* ignore */ }
        player = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { /* ignore */ }
    }

    private fun accept(name: String) {
        accepted = true
        stopRing()
        buttons.visibility = View.GONE
        hang.visibility = View.VISIBLE
        status.text = "00:00"
        h.post(object : Runnable {
            override fun run() {
                seconds++
                status.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
                h.postDelayed(this, 1000)
            }
        })
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                h.postDelayed({
                    tts?.speak("हाँ बेटा, कहाँ हो? जल्दी घर आ जाओ, मैं इंतज़ार कर रही हूँ।", TextToSpeech.QUEUE_FLUSH, null, "fc")
                }, 1500)
            }
        }
    }

    private fun finishCall() {
        stopRing()
        h.removeCallbacksAndMessages(null)
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { /* ignore */ }
        finish()
    }

    override fun onDestroy() {
        stopRing()
        h.removeCallbacksAndMessages(null)
        try { tts?.shutdown() } catch (e: Exception) { /* ignore */ }
        super.onDestroy()
    }
}
