package com.micro.assistant

import android.content.Context
import android.content.SharedPreferences

/** All user settings and small persisted state. Stored privately on this phone only. */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("micro", Context.MODE_PRIVATE)
        }
    }

    private fun read(key: String, def: String): String = sp?.getString(key, def) ?: def
    private fun write(key: String, value: String) {
        sp?.edit()?.putString(key, value)?.apply()
    }

    /** Anthropic API key (the "brain"). */
    var apiKey: String
        get() = read("apiKey", "")
        set(v) = write("apiKey", v.trim())

    /** Free Google Gemini key (aistudio.google.com) – a reliable free brain. */
    var geminiKey: String
        get() = read("geminiKey", "")
        set(v) = write("geminiKey", v.trim())

    var geminiModel: String
        get() = read("geminiModel", DEFAULT_GEMINI).ifBlank { DEFAULT_GEMINI }
        set(v) = write("geminiModel", v.trim())

    /** Show the conversation text on screen (off = voice only). */
    var showText: Boolean
        get() = sp?.getBoolean("showText", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("showText", v)?.apply()
        }

    var model: String
        get() = read("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = write("model", v.trim())

    /** What the friend is called. */
    var friendName: String
        get() = read("friendName", "Micro").ifBlank { "Micro" }
        set(v) = write("friendName", v.trim())

    /** What the friend calls you. */
    var userName: String
        get() = read("userName", "")
        set(v) = write("userName", v.trim())

    /** Use a custom cloud voice instead of the phone's built-in Hindi voice. */
    var customVoice: Boolean
        get() = sp?.getBoolean("customVoice", false) ?: false
        set(v) {
            sp?.edit()?.putBoolean("customVoice", v)?.apply()
        }

    var voiceKey: String
        get() = read("voiceKey", "")
        set(v) = write("voiceKey", v.trim())

    var voiceId: String
        get() = read("voiceId", "")
        set(v) = write("voiceId", v.trim())

    /** Only react after hearing "Hey Micro". */
    var wakeWord: Boolean
        get() = sp?.getBoolean("wakeWord", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("wakeWord", v)?.apply()
        }

    /** Say "ruko" / "stop" while Micro is speaking to cut it off. */
    var bargeIn: Boolean
        get() = sp?.getBoolean("bargeIn", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("bargeIn", v)?.apply()
        }

    /** Jarvis-style personality and voice (calm, formal, "Sir"). Off = friendly buddy. */
    var jarvisMode: Boolean
        get() = sp?.getBoolean("jarvisMode", true) ?: true
        set(v) {
            sp?.edit()?.putBoolean("jarvisMode", v)?.apply()
        }

    /** IP address of the paired smart TV on the hotspot. */
    var tvIp: String
        get() = read("tvIp", "")
        set(v) = write("tvIp", v.trim())

    var facts: String
        get() = read("facts", "[]")
        set(v) = write("facts", v)

    var history: String
        get() = read("history", "[]")
        set(v) = write("history", v)

    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    const val DEFAULT_GEMINI = "gemini-2.5-flash"
}
