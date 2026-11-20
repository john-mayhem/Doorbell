package com.mayhem.doorbell

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Self-update from the public GitHub repo. Each release has one signed APK attached
 * (built by .github/workflows/release.yml when a version tag is pushed).
 *
 * Twice a day a background job checks the latest release; if it is newer, it downloads the APK and
 * hands it to Android's installer. Android installs it quietly when it allows that, otherwise
 * a notification says "tap to install".
 */
object Updater {
    private const val REPO = "john-mayhem/Doorbell"
    private const val N_UPDATE = 3
    const val CH_UPDATES = "updates"

    data class Release(val version: String, val apk: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release) : State
        data class Failed(val why: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    /** App screens currently visible; while > 0 the installer may open its own dialog right away. */
    @Volatile var screens = 0
    val foreground get() = screens > 0

    fun current(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Latest release if it is newer than this app, else null. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        state.value = State.Checking
        val r = runCatching {
            val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            Door.http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@use null  // no releases yet
                if (!resp.isSuccessful) error("GitHub answered ${resp.code}")
                val o = JSONObject(resp.body!!.string())
                val version = o.getString("tag_name").removePrefix("v")
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                if (apk != null && newer(version, current(ctx))) Release(version, apk) else null
            }
        }
        state.value = r.fold({ if (it != null) State.Available(it) else State.UpToDate }, { State.Failed("Couldn't reach GitHub") })
        r.getOrNull()
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Download the APK and give it to Android's installer. */
    suspend fun install(ctx: Context, r: Release): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Updater) {  // one install at a time (extra taps on Update start nothing)
            if (state.value is State.Downloading) return@withContext false
            state.value = State.Downloading(r)
        }
        val ok = runCatching {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                Door.http.newCall(Request.Builder().url(r.apk).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("download failed: ${resp.code}")
                    s.openWrite("doorbell.apk", 0, resp.body!!.contentLength()).use { out -> resp.body!!.byteStream().copyTo(out) }
                }
                val done = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                s.commit(done.intentSender)
            }
        }.onFailure { Log.w("Doorbell", "update failed", it) }.isSuccess
        if (!ok) state.value = State.Failed("Download failed")
        ok
    }

    fun notifyTapToInstall(ctx: Context, confirm: Intent?, version: String?) {
        val tap = confirm?.let { PendingIntent.getActivity(ctx, 6, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            ?: PendingIntent.getActivity(ctx, 6, Intent(ctx, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(NotificationManager::class.java).notify(
            N_UPDATE,
            Notification.Builder(ctx, CH_UPDATES)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF5A524.toInt())
                .setContentTitle(if (version != null) "Doorbell $version is ready" else "A Doorbell update is ready")
                .setContentText("Tap to install")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }
}

/** Android's answer after an install attempt. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a tap: show its dialog now if the app is open, else leave a notification.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val version = (Updater.state.value as? Updater.State.Downloading)?.r?.version
                if (Updater.foreground && confirm != null) ctx.startActivity(confirm)
                else Updater.notifyTapToInstall(ctx, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the new version is running from now on
            else -> {
                Log.w("Doorbell", "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                Updater.state.value = Updater.State.Failed("Android didn't install it")
            }
        }
    }
}

/** The twice-a-day check. Never installs in the middle of a call. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Ringer.busy) return Result.retry()
        val r = Updater.check(applicationContext) ?: return Result.success()
        if (Perm.UPDATES.granted(applicationContext)) Updater.install(applicationContext, r)
        else Updater.notifyTapToInstall(applicationContext, null, r.version)  // opens Settings, which asks for the permission
        return Result.success()
    }
}
