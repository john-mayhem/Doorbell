package com.mayhem.doorbell

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.graphics.vector.ImageVector

/** The five things Doorbell needs to ring reliably, in the order the setup screens ask for them. */
enum class Perm(val icon: ImageVector, val title: String, val text: String) {
    NOTIFY(
        Icons.BellBig, "Ring when someone calls",
        "Doorbell shows the call as a notification, so Android has to let it post notifications.",
    ),
    MIC(
        Icons.MicBig, "Talk to the door",
        "So the person at the entrance can hear you. The microphone is used only during a call you answer.",
    ),
    LOCK_SCREEN(
        Icons.LockRing, "Ring on the lock screen",
        "When someone calls from the entrance, Doorbell wakes the screen like a phone call, even when the phone is locked.",
    ),
    BATTERY(
        Icons.Battery, "Ring without delay",
        "Let Doorbell skip battery saving, so a call isn't held back while the phone sleeps. It uses no battery between calls.",
    ),
    UPDATES(
        Icons.Download, "Keep itself up to date",
        "Doorbell installs its new versions from GitHub by itself. Android calls this \"Install unknown apps\".",
    );

    fun granted(ctx: Context): Boolean = when (this) {
        NOTIFY -> ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        LOCK_SCREEN -> Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        UPDATES -> ctx.packageManager.canRequestPackageInstalls()
    }

    companion object {
        fun missing(ctx: Context) = entries.filter { !it.granted(ctx) }
    }
}
