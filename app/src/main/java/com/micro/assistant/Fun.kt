package com.micro.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.Calendar
import java.util.Locale

/** Gestures: face-down silence, pocket detection and a double shake for the torch. */
object Gestures {
    @Volatile var faceDown = false
    @Volatile var inPocket = false

    var onFlipUp: (() -> Unit)? = null
    var onFaceDown: (() -> Unit)? = null

    private var sm: SensorManager? = null
    private var accL: SensorEventListener? = null
    private var proxL: SensorEventListener? = null
    private var lightL: SensorEventListener? = null
    private var started = false

    private var downSince = 0L
    private var lastSpike = 0L
    private var torchOn = false
    private var near = false
    private var lux = 100f

    fun pocketActive(): Boolean = Prefs.pocketMode && inPocket
    fun quiet(): Boolean = Prefs.faceDownQuiet && faceDown

    fun start(ctx: Context, hands: Hands) {
        if (started) return
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc != null) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val x = e.values[0]
                    val y = e.values[1]
                    val z = e.values[2]
                    val now = System.currentTimeMillis()

                    // face down: the screen looks at the table
                    if (z < -8f && Math.abs(x) < 3f && Math.abs(y) < 3f) {
                        if (downSince == 0L) downSince = now
                        if (!faceDown && now - downSince > 1500) {
                            faceDown = true
                            onFaceDown?.invoke()
                        }
                    } else {
                        downSince = 0L
                        if (faceDown && z > -6f) {
                            faceDown = false
                            onFlipUp?.invoke()
                        }
                    }

                    // double shake = torch on / off
                    if (Prefs.shakeTorch && !faceDown) {
                        val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                        if (Math.abs(mag - 9.81f) > 15f) {
                            val gap = now - lastSpike
                            if (gap in 250..900) {
                                lastSpike = 0L
                                torchOn = !torchOn
                                hands.torch(torchOn)
                            } else if (gap > 900 || lastSpike == 0L) {
                                lastSpike = now
                            }
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            accL = l
            m.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
        }
        val prox = m.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (prox != null) {
            val limit = Math.min(prox.maximumRange, 5f)
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    near = e.values[0] < limit
                    inPocket = near && lux < 8f
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            proxL = l
            m.registerListener(l, prox, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val light = m.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light != null) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    lux = e.values[0]
                    inPocket = near && lux < 8f
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            lightL = l
            m.registerListener(l, light, SensorManager.SENSOR_DELAY_NORMAL)
        }
        started = true
    }

    fun stop() {
        try {
            accL?.let { sm?.unregisterListener(it) }
            proxL?.let { sm?.unregisterListener(it) }
            lightL?.let { sm?.unregisterListener(it) }
        } catch (e: Exception) {
            // ignore
        }
        accL = null
        proxL = null
        lightL = null
        started = false
        faceDown = false
        inPocket = false
    }
}

/** Finds two claps in a row from the microphone loudness that the speech recogniser reports. */
object Clap {
    private var baseline = 0f
    private var init = false
    private var firstAt = 0L
    private var lastSpike = 0L

    fun feed(rms: Float, now: Long): Boolean {
        if (!init) {
            baseline = rms
            init = true
        }
        val spike = rms > baseline + 4.5f && rms > 4f
        if (!spike) {
            baseline = baseline * 0.97f + rms * 0.03f
            return false
        }
        if (now - lastSpike < 120) {
            lastSpike = now
            return false
        }
        lastSpike = now
        if (firstAt != 0L && now - firstAt in 200..900) {
            firstAt = 0L
            return true
        }
        firstAt = now
        return false
    }
}

/** A very rough guess of your mood from how fast and how loud you speak. Only used to give a kind hint. */
object Mood {
    private val recent = ArrayList<Pair<Long, Int>>()
    private var lastDay = -1

    fun feed(wordCount: Int, durationMs: Long, avgRms: Float): String? {
        if (!Prefs.moodAware || durationMs < 1500 || wordCount < 3) return null
        val rate = wordCount * 1000f / durationMs
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val night = h >= 22 || h <= 4
        val kind = when {
            rate < 1.3f && avgRms < 3f && night -> 1
            rate > 3.8f && avgRms > 7f -> 2
            else -> 0
        }
        if (kind == 0) return null
        val now = System.currentTimeMillis()
        recent.add(now to kind)
        recent.removeAll { now - it.first > 15 * 60_000 }
        val same = recent.count { it.second == kind }
        val today = cal.get(Calendar.DAY_OF_YEAR)
        if (same < 3 || lastDay == today) return null
        lastDay = today
        recent.clear()
        val sir = if (Prefs.jarvisMode) "सर, " else ""
        return if (kind == 1) "${sir}आज आप थके हुए लग रहे हैं। थोड़ा आराम कर लीजिए।"
        else "${sir}थोड़ा गहरी साँस लीजिए। मैं यहीं हूँ।"
    }
}

/** Funny SMS texts. Uses the AI if there is a Gemini key, otherwise a built-in list. */
object Prank {
    private val LINES = listOf(
        "मैं चाँद पर पहुँच गया हूँ, मंगल की टिकट भी ले ली है। चिंता मत करना।",
        "ज़रूरी सूचना: मेरी बैटरी 1 प्रतिशत है और मेरी हँसी 100 प्रतिशत।",
        "आज खाने में क्या बना है? अगर पनीर है तो मैं पहले ही घर के बाहर खड़ा हूँ।",
        "मैंने आज दुनिया बचाई है। अब चाय मिलेगी क्या?",
        "मैं रास्ते में हूँ, बस ज़रा सा रास्ता भूल गया हूँ। गूगल मैप्स कह रहा है कि 'किस्मत से मिलते हैं'।",
        "सुनो! मेरा फ़ोन मुझसे ज़्यादा स्मार्ट है, इसलिए यह मैसेज उसी ने भेजा है।"
    )

    fun line(): String {
        val ai = try {
            Brain.quickText("एक छोटा, साफ़-सुथरा और मज़ेदार हिंदी SMS लिखो (एक या दो वाक्य) जो कोई दोस्त अपने परिवार को हँसाने के लिए भेजे। सिर्फ़ SMS का टेक्स्ट दो, कोई और बात नहीं।")
        } catch (e: Exception) {
            null
        }
        return ai?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it.length < 200 } ?: LINES.random()
    }

    fun isPrankWord(token: String): Boolean {
        val t = Local.strip(token)
        return listOf("mazak", "मजाक", "मज़ाक", "mazaak", "funny", "मजेदार", "मज़ेदार", "prank", "शरारती", "joke", "jokes", "hasi", "हंसी", "मजाकिया", "मज़ाकिया")
            .map { Local.strip(it) }.any { t.startsWith(it) }
    }
}

/** Schedules a fake incoming call screen. */
object FakeCall {
    fun schedule(ctx: Context, millis: Long, name: String): String {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, name)
        val at = System.currentTimeMillis() + millis
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
        val mins = millis / 60_000
        val when_ = if (millis < 60_000) "${millis / 1000} सेकंड" else "$mins मिनट"
        return "ठीक है, $when_ में $name की नकली कॉल आएगी।"
    }

    fun cancel(ctx: Context): String {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, ""))
        return "नकली कॉल रद्द कर दी।"
    }

    private fun pending(ctx: Context, name: String): PendingIntent {
        val i = Intent(ctx, FakeCallReceiver::class.java).putExtra("name", name)
        return PendingIntent.getBroadcast(ctx, 7701, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Voice commands for the fun features. */
object Fun {
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun handle(t: String, words: List<String>, raw: String): String? {
        val ctx = appCtx ?: return null
        fun any(vararg w: String) = w.any { t.contains(Local.strip(it)) }
        fun word(vararg w: String) = w.any { x -> words.contains(Local.strip(x)) }
        val off = word("off", "band", "stop", "disable") || any("बंद", "बन्द", "हटाओ")

        // ----- fake call -----
        if (any("fake call", "फेक कॉल", "फ़ेक कॉल", "फेक काल", "नकली कॉल", "नकली काल", "nakli call", "fek call", "fake kol")) {
            if (any("cancel", "कैंसिल", "रद्द", "radd", "band", "बंद", "hatao", "हटाओ")) return FakeCall.cancel(ctx)
            var millis = 60_000L
            for (i in 1 until words.size) {
                val n = Local.numberOf(words[i - 1]) ?: continue
                val u = words[i]
                when {
                    u.startsWith(Local.strip("minute")) || u.startsWith(Local.strip("मिनट")) || u == "min" -> { millis = n * 60_000L; break }
                    u.startsWith(Local.strip("second")) || u.startsWith(Local.strip("सेकंड")) || u.startsWith(Local.strip("सेकण्ड")) -> { millis = n * 1000L; break }
                    u.startsWith(Local.strip("ghante")) || u.startsWith(Local.strip("घंटे")) || u.startsWith(Local.strip("घंटा")) || u.startsWith(Local.strip("hour")) -> { millis = n * 3_600_000L; break }
                }
            }
            val m = Regex("(\\S+)\\s+(?:ki|की|ka|का)\\s+(?:fake|फेक|फ़ेक|नकली|nakli|fek)").find(t)
            val name = m?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it !in setOf("mujhe", "मुझे", "ek", "एक") } ?: Prefs.fakeCaller
            return FakeCall.schedule(ctx, millis.coerceIn(5_000L, 6 * 3_600_000L), name)
        }
        if (any("caller ka naam", "कॉलर का नाम", "fake caller naam", "नकली कॉलर का नाम")) {
            val newName = words.dropWhile { it != "naam" && it != Local.strip("नाम") }.drop(1).filter { it !in setOf("hai", "है", "rakho", "रखो", "karo", "करो") }.joinToString(" ")
            if (newName.isNotBlank()) {
                Prefs.fakeCaller = newName
                return "ठीक है, नकली कॉल अब $newName के नाम से आएगी।"
            }
        }

        // ----- toggles -----
        if (any("pocket mode", "पॉकेट मोड", "जेब मोड")) {
            Prefs.pocketMode = !off
            return if (off) "पॉकेट मोड बंद।" else "पॉकेट मोड चालू। जेब में फ़ोन होने पर मैं नए मैसेज बोलकर सुनाऊँगा और स्क्रीन नहीं जलाऊँगा।"
        }
        if (any("clap mode", "ताली मोड", "ताली से ढूंढ", "ताली से ढूँढ")) {
            Prefs.clapFind = !off
            return if (off) "ताली मोड बंद।" else "ताली मोड चालू। फ़ोन खो जाए तो दो बार ताली बजाइए।"
        }
        if (any("mood detect", "मूड", "mood mode")) {
            if (any("on", "ऑन", "चालू", "off", "बंद", "band")) {
                Prefs.moodAware = !off
                return if (off) "मूड पहचान बंद।" else "मूड पहचान चालू।"
            }
        }
        return null
    }
}
