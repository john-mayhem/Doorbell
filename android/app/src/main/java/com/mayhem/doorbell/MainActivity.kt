package com.mayhem.doorbell

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private sealed interface Link {
        data object Looking : Link
        data object Away : Link
        data class Online(val host: String, val status: JSONObject) : Link
    }

    private var link by mutableStateOf<Link>(Link.Looking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // First launch: one screen per permission. Later, Settings shows what is still missing.
        if (savedInstanceState == null && !Prefs.setupDone(this)) startActivity(Intent(this, SetupActivity::class.java))

        // Every time the app comes to the front: (re)register for pushes, then watch the doorbell.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Door.register(applicationContext) }
                while (true) {
                    val host = Door.find(applicationContext)
                    val st = host?.let { Door.status(it) }
                    link = if (host != null && st != null) Link.Online(host, st) else Link.Away
                    delay(if (link is Link.Online) 2000 else 6000)
                }
            }
        }

        setContent { DoorbellTheme { Home() } }
    }

    override fun onStart() { super.onStart(); Updater.screens++ }
    override fun onStop() { Updater.screens--; super.onStop() }

    @Composable
    private fun Home() {
        val events by History.events.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

        Column(
            Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(greeting, style = text(14, 500, C.Muted))
                    Text("Home", style = text(30, 800))
                }
                val ctx = LocalContext.current
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(C.Card)
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Gear, "Settings", Modifier.size(22.dp), tint = C.Ink) }
            }

            DoorCard(onTestRing = { host -> scope.launch { Door.post(host, "/api/ring") } })

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recent", style = text(17, 700), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                if (events.isEmpty()) {
                    Text("No calls yet", style = text(14, 400, C.Muted), modifier = Modifier.padding(start = 4.dp))
                }
                events.forEach { EventRow(it) }
            }
        }
    }

    @Composable
    private fun DoorCard(onTestRing: (String) -> Unit) {
        val l = link
        val online = l as? Link.Online
        val line = online?.status?.optString("state")
        val ctx = LocalContext.current
        val subtitle = when {
            online == null -> if (l is Link.Away) "Can't reach the doorbell" else "Looking for the doorbell…"
            line == "ringing" -> "Someone is calling"
            line == "talking" -> "On a call with ${online.status.optString("owner")}"
            else -> "Nobody at the door"
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.Card)
                .clickable(enabled = line == "ringing", role = Role.Button) {
                    ctx.startActivity(Intent(ctx, CallActivity::class.java))
                }
                .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(136.dp).clip(CircleShape).background(C.Halo), contentAlignment = Alignment.Center) {
                Box(Modifier.size(116.dp).clip(CircleShape).background(C.Ring), contentAlignment = Alignment.Center) {
                    Icon(if (line == "ringing") Icons.Bell else Icons.Door, null, Modifier.size(52.dp), tint = C.Amber)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Front door", style = text(22, 700))
                Text(subtitle, style = text(14, 400, C.Muted))
            }
            when {
                online != null -> StatusPill("Connected", C.GreenBg, C.GreenText, C.GreenDot)
                l is Link.Away -> StatusPill("Not on home Wi-Fi", C.Row, C.Muted, C.Muted)
                else -> StatusPill("Searching", C.AmberBg, C.Amber, C.Amber)
            }
            if (online != null && online.status.optBoolean("emu") && line == "idle") {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, C.Line, RoundedCornerShape(50))
                        .clickable(role = Role.Button) { onTestRing(online.host) }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Test ring", style = text(13, 600, C.Muted)) }
            }
        }
    }

    @Composable
    private fun StatusPill(label: String, bg: Color, fg: Color, dot: Color) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, style = text(13, 600, fg))
        }
    }

    @Composable
    private fun EventRow(e: Event) {
        val ctx = LocalContext.current
        val who = if (e.by.isBlank() || e.by == Prefs.name(ctx)) "You" else e.by
        val talked = "%d:%02d".format(e.secs / 60, e.secs % 60)
        val (icon, tileBg, tileFg, title) = when (e.kind) {
            Event.Kind.MISSED -> Quad(Icons.PhoneMissed, C.RedBg, C.RedText, "Missed call")
            Event.Kind.DECLINED -> Quad(Icons.PhoneOff, C.Card, C.Muted, "Declined")
            Event.Kind.OPENED -> Quad(Icons.DoorOpen, C.AmberBg, C.Amber, "Door opened")
            Event.Kind.ANSWERED -> Quad(Icons.Phone, C.GreenTile, C.GreenText, "Answered")
        }
        val detail = when (e.kind) {
            Event.Kind.MISSED -> "Nobody answered"
            Event.Kind.DECLINED -> "$who declined"
            Event.Kind.OPENED -> "$who, without talking"
            Event.Kind.ANSWERED -> "$who talked $talked" + if (e.opened) ", door opened" else ""
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Row).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tileBg), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tileFg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = text(15, 600))
                Text(detail, style = text(13, 400, C.Muted))
            }
            Text(whenText(e.time), style = text(13, 400, C.Muted))
        }
    }

    private data class Quad(val a: ImageVector, val b: Color, val c: Color, val d: String)

    private fun whenText(t: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        return when {
            DateUtils.isToday(t) -> hm
            DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        }
    }
}
