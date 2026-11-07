package com.micro.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps the latest notifications in memory (never saved or sent anywhere) so Micro can read them out. */
class NotifService : NotificationListenerService() {

    companion object {
        val recent = ArrayList<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString()
        } catch (ex: Exception) {
            s.packageName
        }
        synchronized(recent) {
            recent.add("$app: $title $text".trim())
            while (recent.size > 20) recent.removeAt(0)
        }
    }
}
