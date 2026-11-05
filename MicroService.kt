package com.micro.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Gives the AI friend its "hands": permission to see the screen, tap, type and scroll.
 * All the thinking and talking lives in [Assistant]; this service only connects it
 * to the accessibility system.
 */
class MicroService : AccessibilityService() {

    companion object {
        @Volatile var instance: MicroService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Assistant.get(this).hands.service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        instance = null
        Assistant.get(this).hands.service = null
    }
}
