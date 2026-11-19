package com.mayhem.doorbell

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One line in "Recent". Every phone gets the same line from the doorbell when a call ends,
 * so both of you see who answered, declined or opened.
 */
data class Event(val time: Long, val kind: Kind, val by: String, val secs: Int, val opened: Boolean) {
    enum class Kind { MISSED, DECLINED, ANSWERED, OPENED }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("doorbell", Context.MODE_PRIVATE)

    /** Last address the doorbell was found at (from mDNS or a push). */
    fun host(ctx: Context): String = p(ctx).getString("host", "")!!
    fun setHost(ctx: Context, v: String) = p(ctx).edit().putString("host", v).apply()

    /** Address typed in Settings; empty = find automatically. */
    fun manualHost(ctx: Context): String = p(ctx).getString("manual_host", "")!!
    fun setManualHost(ctx: Context, v: String) = p(ctx).edit().putString("manual_host", v.trim()).apply()

    /** Where to connect: the typed address if there is one, else the last one found. */
    fun target(ctx: Context): String = manualHost(ctx).ifBlank { host(ctx) }

    /** Shown to the other phones ("Answered by …"). Defaults to the phone's own name. */
    fun name(ctx: Context): String = p(ctx).getString("name", null)
        ?: Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
    fun setName(ctx: Context, v: String) = p(ctx).edit().putString("name", v.trim().take(30)).apply()

    /** Ring sound; null = the built-in doorbell chime. */
    fun ringtone(ctx: Context): Uri? = p(ctx).getString("ringtone", null)?.let(Uri::parse)
    fun setRingtone(ctx: Context, v: Uri?) = p(ctx).edit().putString("ringtone", v?.toString()).apply()
    fun chime(ctx: Context): Uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.chime}")

    fun ringVolume(ctx: Context): Int = p(ctx).getInt("ring_vol", 80)
    fun setRingVolume(ctx: Context, v: Int) = p(ctx).edit().putInt("ring_vol", v).apply()

    fun voiceVolume(ctx: Context): Int = p(ctx).getInt("voice_vol", 100)
    fun setVoiceVolume(ctx: Context, v: Int) = p(ctx).edit().putInt("voice_vol", v).apply()

    fun vibrate(ctx: Context): Boolean = p(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("vibrate", v).apply()

    fun speaker(ctx: Context): Boolean = p(ctx).getBoolean("speaker", true)
    fun setSpeaker(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("speaker", v).apply()

    /** The first-launch permission screens were shown once. */
    fun setupDone(ctx: Context): Boolean = p(ctx).getBoolean("setup_done", false)
    fun setSetupDone(ctx: Context) = p(ctx).edit().putBoolean("setup_done", true).apply()
}

/** Call history, newest first, kept on the phone (last 50). */
object History {
    val events = MutableStateFlow<List<Event>>(emptyList())

    private fun store(ctx: Context) = ctx.getSharedPreferences("doorbell", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val arr = JSONArray(store(ctx).getString("calls", "[]"))
        events.value = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Event(o.getLong("t"), Event.Kind.valueOf(o.getString("k")), o.optString("by"), o.optInt("s"), o.optBoolean("o"))
        }
    }

    fun add(ctx: Context, e: Event) {
        load(ctx)
        val list = (listOf(e) + events.value).sortedByDescending { it.time }.take(50)
        events.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.time).put("k", it.kind.name).put("by", it.by).put("s", it.secs).put("o", it.opened)) }
        store(ctx).edit().putString("calls", arr.toString()).remove("history").apply()
    }
}
