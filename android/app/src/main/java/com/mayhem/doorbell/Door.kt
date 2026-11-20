package com.mayhem.doorbell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Line states reported by the ESP. */
enum class Line { IDLE, RINGING, TALKING }

data class DoorState(val line: Line, val owner: String?, val mine: Boolean, val openOnly: Boolean, val reason: String?) {
    companion object {
        fun from(o: JSONObject) = DoorState(
            line = when (o.optString("state")) { "ringing" -> Line.RINGING; "talking" -> Line.TALKING; else -> Line.IDLE },
            owner = o.optString("owner").ifBlank { null },
            mine = o.optBoolean("mine"),
            openOnly = o.optBoolean("open_only"),
            reason = o.optString("reason").ifBlank { null },
        )
    }
}

/** Finding the ESP on the home Wi-Fi and the plain HTTP calls. */
object Door {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val quick = http.newBuilder().readTimeout(3, TimeUnit.SECONDS).build()

    /** GET /status, or null if the ESP doesn't answer. */
    suspend fun status(host: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            quick.newCall(Request.Builder().url("http://$host/status").build()).execute().use {
                if (it.isSuccessful) JSONObject(it.body!!.string()) else null
            }
        }.getOrNull()
    }

    suspend fun post(host: String, path: String, json: JSONObject? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = (json?.toString() ?: "").toRequestBody("application/json".toMediaType())
            quick.newCall(Request.Builder().url("http://$host$path").post(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** The address typed in Settings, else the saved one if it still answers, else an mDNS search. */
    suspend fun find(ctx: Context): String? {
        val manual = Prefs.manualHost(ctx)
        if (manual.isNotBlank()) return manual.takeIf { status(it) != null }
        val saved = Prefs.host(ctx)
        if (saved.isNotBlank() && status(saved) != null) return saved
        return discover(ctx)?.also { Prefs.setHost(ctx, it) }
    }

    /** Tell the ESP where to push this phone's calls. */
    suspend fun register(ctx: Context, token: String? = null): Boolean {
        val t = token ?: fcmToken() ?: return false
        val host = find(ctx) ?: return false
        // ANDROID_ID survives reinstalls (same signing key), so the doorbell keeps one entry per phone.
        val id = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
        return post(host, "/api/register", JSONObject().put("token", t).put("name", Prefs.name(ctx)).put("id", id))
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { c ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { c.resume(if (it.isSuccessful) it.result else null) }
    }

    @Suppress("DEPRECATION")
    private suspend fun discover(ctx: Context): String? {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("doorbell").apply { setReferenceCounted(false); acquire() }
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(6000) {
                suspendCancellableCoroutine { cont ->
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onServiceFound(info: NsdServiceInfo) {
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                                override fun onServiceResolved(si: NsdServiceInfo) {
                                    val addr = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses.firstOrNull { it is Inet4Address } else si.host
                                    if (addr != null && cont.isActive) cont.resume("${addr.hostAddress}:${si.port}")
                                }
                            })
                        }
                        override fun onStartDiscoveryFailed(t: String, code: Int) { if (cont.isActive) cont.resume(null) }
                        override fun onDiscoveryStarted(t: String) {}
                        override fun onDiscoveryStopped(t: String) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onStopDiscoveryFailed(t: String, code: Int) {}
                    }
                    listener = l
                    nsd.discoverServices("_doorbell._tcp", NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            lock.release()
        }
    }
}

/**
 * One live connection to the ESP, open only while a call screen is showing.
 * JSON text frames = control, binary frames = 16 kHz PCM audio.
 */
class DoorLink(host: String, private val name: String) {
    val state = MutableStateFlow<DoorState?>(null)
    val connected = MutableStateFlow(false)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    @Volatile var onAudio: ((ByteArray) -> Unit)? = null

    private val ws: WebSocket = Door.http.newWebSocket(Request.Builder().url("ws://$host/ws").build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("t", "hello").put("name", name).toString())
            connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (o.optString("t")) {
                "state" -> state.value = DoorState.from(o)
                "event" -> events.tryEmit(o.optString("ev"))
                "error" -> events.tryEmit("error:" + o.optString("msg"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onAudio?.invoke(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected.value = false }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.value = false
            events.tryEmit("lost")
        }
    })

    fun send(t: String) = ws.send(JSONObject().put("t", t).toString())
    fun sendAudio(pcm: ByteArray) = ws.send(pcm.toByteString())
    fun close() = ws.close(1000, null)

    companion object {
        /** Connect, send one command, give it a moment, disconnect (notification buttons). */
        suspend fun oneShot(host: String, name: String, cmd: String): Boolean {
            val link = DoorLink(host, name)
            val ok = withTimeoutOrNull(4000) {
                while (link.state.value == null) delay(50)
                link.send(cmd)
                delay(400)
                true
            } ?: false
            link.close()
            return ok
        }
    }
}
