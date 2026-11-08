package com.micro.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when the fake-call alarm goes off and opens the fake incoming-call screen. */
class FakeCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "Mummy"
        val open = Intent(ctx, FakeCallActivity::class.java)
            .putExtra("name", name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            ctx.startActivity(open)
        } catch (e: Exception) {
            // fall through to the notification below
        }
        // Backup: a full-screen notification, in case the phone blocks starting a screen from the background.
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("fakecall", "Fake call", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 7702, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, "fakecall")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(name)
                .setContentText("Incoming call")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7703, n)
        } catch (e: Exception) {
            // ignore
        }
    }
}
