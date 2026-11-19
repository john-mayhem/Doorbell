package com.mayhem.doorbell

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * The incoming call. The notification itself is silent; this service plays the ringtone and
 * vibrates, and lives only while it rings (at most a minute), so nothing stays in the status bar.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Ringer.build(this)
        try {
            startForeground(Ringer.N_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: Exception) {
            Log.w("Doorbell", "ringing without a foreground service", e)
            getSystemService(NotificationManager::class.java).notify(Ringer.N_RING, n)
        }
        Ringer.ringing = true
        if (player == null) startSound()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 60_000)
        return START_NOT_STICKY
    }

    private fun startSound() {
        player = Tone.play(this, Prefs.ringtone(this), Prefs.ringVolume(this), loop = true)
        val am = getSystemService(AudioManager::class.java)
        if (Prefs.vibrate(this) && am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 600, 800, 1400), 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
            )
        }
    }

    override fun onDestroy() {
        Ringer.ringing = false
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        vibrator.cancel()
        getSystemService(NotificationManager::class.java).cancel(Ringer.N_RING)
        super.onDestroy()
    }
}

/** Plays a ringtone at our own volume (a share of the phone's ring volume). */
object Tone {
    private var preview: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun play(ctx: Context, uri: Uri?, volume: Int, loop: Boolean): MediaPlayer? {
        fun make(u: Uri) = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(ctx, u)
                isLooping = loop
                val v = volume / 100f
                setVolume(v * v, v * v)  // squared: the slider feels even to the ear
                prepare()
                start()
            }
        }.onFailure { Log.w("Doorbell", "can't play $u", it) }.getOrNull()
        // A ringtone that can't be played (deleted, no access) falls back to the chime.
        return uri?.let(::make) ?: make(Prefs.chime(ctx))
    }

    /** A few seconds of the ringtone, for Settings. */
    fun preview(ctx: Context, uri: Uri?, volume: Int) {
        stopPreview()
        preview = play(ctx, uri, volume, loop = false)
        handler.postDelayed(::stopPreview, 3500)
    }

    fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        preview?.release()
        preview = null
    }
}

/** The incoming-call notification: full screen on the lock screen, heads-up otherwise. */
object Ringer {
    /** Ringing or in a call: updates wait. */
    @Volatile var ringing = false
    @Volatile var inCall = false
    val busy get() = ringing || inCall

    const val CH_CALL = "call"
    const val CH_MISSED = "missed"
    const val N_RING = 1
    private const val N_MISSED = 2

    fun ring(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, RingService::class.java))
        } catch (e: Exception) {
            // Android refused the service (should not happen for a high-priority push): at least show it.
            Log.w("Doorbell", "can't start RingService", e)
            ctx.getSystemService(NotificationManager::class.java).notify(N_RING, build(ctx))
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, RingService::class.java))
        ctx.getSystemService(NotificationManager::class.java).cancel(N_RING)
    }

    fun build(ctx: Context): Notification {
        val f = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val call = Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val full = PendingIntent.getActivity(ctx, 1, call, f)
        val answer = PendingIntent.getActivity(ctx, 2, Intent(call).setAction(CallActivity.ANSWER), f)
        val open = PendingIntent.getActivity(ctx, 3, Intent(call).setAction(CallActivity.OPEN), f)
        val decline = PendingIntent.getBroadcast(ctx, 4, Intent(ctx, DeclineReceiver::class.java), f)
        val caller = Person.Builder().setName("Front door").setImportant(true).build()
        return Notification.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFF5A524.toInt())
            .setContentTitle("Front door")
            .setContentText("Someone is calling from the entrance")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setTimeoutAfter(60_000)
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Open door", open).build())
            .build()
    }

    fun missed(ctx: Context) {
        val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(NotificationManager::class.java).notify(
            N_MISSED,
            Notification.Builder(ctx, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF5A524.toInt())
                .setContentTitle("Missed call")
                .setContentText("Someone rang the front door")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setShowWhen(true)
                .build()
        )
    }
}
