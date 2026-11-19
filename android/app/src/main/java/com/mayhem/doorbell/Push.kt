package com.mayhem.doorbell

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DoorbellApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("ring")  // v0.2: the system played the sound, so it couldn't be changed
        nm.createNotificationChannel(NotificationChannel(Ringer.CH_CALL, "Door calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows the call when someone rings from the entrance. Sound and vibration are set in the app."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(Ringer.CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(Updater.CH_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        History.load(this)
        Updater.schedule(this)
    }
}

/** Receives the doorbell's pushes. Runs even when the app is closed. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as DoorbellApp).scope.launch { Door.register(applicationContext, token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        d["host"]?.takeIf { it.isNotBlank() }?.let { Prefs.setHost(this, it) }
        when (d["type"]) {
            "ring" -> Ringer.ring(this)
            "ring_end" -> Ringer.stop(this)  // someone picked up
            "call" -> {                      // the call is over: one line for Recent
                Ringer.stop(this)
                val kind = when (d["outcome"]) {
                    "declined" -> Event.Kind.DECLINED
                    "answered" -> Event.Kind.ANSWERED
                    "opened" -> Event.Kind.OPENED
                    else -> Event.Kind.MISSED
                }
                val at = d["at"]?.toLongOrNull()?.takeIf { it > 1_600_000_000 }?.times(1000) ?: System.currentTimeMillis()
                History.add(this, Event(at, kind, d["by"].orEmpty(), d["secs"]?.toIntOrNull() ?: 0, d["opened"] == "1"))
                if (kind == Event.Kind.MISSED) Ringer.missed(this)
            }
        }
    }
}

/** "Decline" on the notification: tell the doorbell without opening any screen. */
class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Ringer.stop(ctx)
        val pending = goAsync()
        (ctx.applicationContext as DoorbellApp).scope.launch {
            Prefs.target(ctx).takeIf { it.isNotBlank() }?.let { DoorLink.oneShot(it, Prefs.name(ctx), "reject") }
            pending.finish()
        }
    }
}
