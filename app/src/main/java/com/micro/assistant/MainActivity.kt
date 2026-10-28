package com.micro.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        box.addView(TextView(this).apply { text = "Micro"; textSize = 32f })
        fun button(label: String, action: () -> Unit) {
            box.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("1. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        button("2. Turn on Micro in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("3. Talk to Micro") {
            val s = MicroService.instance
            if (s == null) Toast.makeText(this, "Turn on Micro in Accessibility first", Toast.LENGTH_LONG).show()
            else s.listen()
        }
        setContentView(box)
    }
}
