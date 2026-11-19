package com.mayhem.doorbell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen call: ringing → talking → door opened. Opens over the lock screen. */
class CallActivity : ComponentActivity() {
    companion object {
        const val ANSWER = "answer"
        const val OPEN = "open"
    }

    private enum class Screen { CONNECTING, RINGING, TALKING, OPENING, OPENED, ENDED }

    private var screen by mutableStateOf(Screen.CONNECTING)
    private var endedText by mutableStateOf("")
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(true)  // set from Settings in onCreate
    private var talkStart by mutableLongStateOf(0L)

    private var link: DoorLink? = null
    private var audio: AudioEngine? = null
    private var pending: String? = null   // ANSWER / OPEN from the notification, run once connected
    private var iOpened = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { link?.send("answer") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pending = intent?.action
        speaker = Prefs.speaker(this)
        Ringer.inCall = true

        val host = Prefs.target(this)
        if (host.isBlank()) end("Doorbell not found on this Wi-Fi") else connect(host)

        setContent {
            DoorbellTheme {
                Box(Modifier.fillMaxSize().background(C.CallBg).safeDrawingPadding()) {
                    when (screen) {
                        Screen.CONNECTING, Screen.RINGING -> Incoming(screen == Screen.CONNECTING)
                        Screen.TALKING -> Talking()
                        Screen.OPENING, Screen.OPENED -> Opened(screen == Screen.OPENED)
                        Screen.ENDED -> Ended()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            ANSWER -> answer()
            OPEN -> open()
        }
    }

    private fun connect(host: String) {
        val l = DoorLink(host, Prefs.name(this))
        link = l
        lifecycleScope.launch {
            l.state.collect { s -> if (s != null) onState(s) }
        }
        lifecycleScope.launch {
            l.events.collect { ev ->
                when {
                    ev == "door_opened" -> onDoorOpened()
                    ev == "lost" && screen != Screen.OPENED && screen != Screen.ENDED -> end("Connection lost")
                }
            }
        }
    }

    private fun onState(s: DoorState) {
        when (s.line) {
            Line.RINGING -> {
                screen = Screen.RINGING
                when (pending) {
                    ANSWER -> answer()
                    OPEN -> open()
                }
                pending = null
            }
            Line.TALKING -> when {
                s.mine -> startTalking()
                s.openOnly && iOpened -> if (screen != Screen.OPENED) screen = Screen.OPENING
                else -> end("Answered on ${s.owner}")
            }
            Line.IDLE -> {
                Ringer.stop(this)
                if (screen == Screen.TALKING) {
                    end("Call ended")
                } else if (screen == Screen.OPENED) {
                    // stay on the check mark
                } else if (screen != Screen.ENDED) {
                    end(if (s.reason == "missed") "Nobody answered in time" else "Call ended")
                }
            }
        }
    }

    private fun answer() {
        Ringer.stop(this)
        if (screen != Screen.RINGING) { pending = ANSWER; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            link?.send("answer")
        }
    }

    private fun open() {
        Ringer.stop(this)
        if (screen == Screen.CONNECTING) { pending = OPEN; return }
        iOpened = true
        if (screen == Screen.RINGING) screen = Screen.OPENING
        link?.send("open")
    }

    private fun decline() {
        Ringer.stop(this)
        link?.send("reject")
        end("Declined")
    }

    private fun hangUp() {
        link?.send("hangup")
        end("Call ended")
    }

    private fun startTalking() {
        if (screen == Screen.TALKING) return
        screen = Screen.TALKING
        talkStart = System.currentTimeMillis()
        val l = link ?: return
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        audio = AudioEngine(this, mic) { l.sendAudio(it) }.also {
            it.muted = muted
            it.volume = Prefs.voiceVolume(this)
            it.start(speaker)
            l.onAudio = it::onDownlink
        }
    }

    private fun onDoorOpened() {
        if (screen == Screen.TALKING) {
            // opened during a conversation: show the check, then back to talking
            screen = Screen.OPENED
            lifecycleScope.launch {
                delay(2200)
                if (screen == Screen.OPENED && audio != null) screen = Screen.TALKING
            }
        } else {
            screen = Screen.OPENED
        }
    }

    private fun end(text: String) {
        stopAudio()
        endedText = text
        screen = Screen.ENDED
        lifecycleScope.launch {
            delay(1600)
            finish()
        }
    }

    private fun stopAudio() {
        link?.onAudio = null
        audio?.stop()
        audio = null
    }

    override fun onDestroy() {
        if (audio != null) link?.send("hangup")
        stopAudio()
        link?.close()
        Ringer.inCall = false
        super.onDestroy()
    }

    // ---------- screens ----------

    @Composable
    private fun Incoming(connecting: Boolean) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DOORBELL", style = text(14, 600, C.Amber))
                Text("Front door", style = text(34, 800))
                Text(if (connecting) "Connecting…" else "Someone is calling from the entrance", style = text(16, 400, C.Muted))
            }
            Ripple(Icons.Bell)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(34.dp)) {
                Pill("Open door", Icons.Key, C.Amber, C.AmberInk) { open() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    RoundButton("Decline", Icons.PhoneOff, C.Red, Color.White) { decline() }
                    RoundButton("Answer", Icons.Phone, C.Green, C.GreenInk) { answer() }
                }
            }
        }
    }

    @Composable
    private fun Talking() {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
        val secs = ((now - talkStart) / 1000).coerceAtLeast(0)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.GreenDot))
                    Text("Talking", style = text(14, 600, C.GreenText))
                }
                Text("Front door", style = text(34, 800))
                Text("%02d:%02d".format(secs / 60, secs % 60), style = text(20, 600, C.Muted))
            }
            Bars()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Toggle(if (muted) "Unmute" else "Mute", if (muted) Icons.MicOff else Icons.Mic, muted, Modifier.weight(1f)) {
                        muted = !muted; audio?.muted = muted
                    }
                    Toggle(if (speaker) "Speaker" else "Earpiece", if (speaker) Icons.Speaker else Icons.Earpiece, speaker, Modifier.weight(1f)) {
                        speaker = !speaker; audio?.setSpeaker(speaker)
                    }
                }
                Pill("Open door", Icons.Key, C.Amber, C.AmberInk) { open() }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RoundButton(null, Icons.PhoneOff, C.Red, Color.White, "Hang up") { hangUp() }
                }
            }
        }
    }

    @Composable
    private fun Opened(done: Boolean) {
        val pop = remember { androidx.compose.animation.core.Animatable(0.6f) }
        LaunchedEffect(done) { if (done) pop.animateTo(1f, tween(450)) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("FRONT DOOR", style = text(14, 600, C.Muted))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
                if (done) {
                    Box(
                        Modifier.scale(pop.value).size(200.dp).clip(CircleShape).background(C.AmberDeep),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(168.dp).clip(CircleShape).background(C.Amber), contentAlignment = Alignment.Center) {
                            Icon(Icons.Check, null, Modifier.size(76.dp), tint = C.AmberInk)
                        }
                    }
                } else {
                    Ripple(Icons.Key)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (done) "Door opened" else "Opening…", style = text(34, 800))
                    Text(if (done) "The lock is released for a few seconds" else "Pressing the door button for you",
                        style = text(16, 400, C.Muted), textAlign = TextAlign.Center)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50)).background(C.Card)
                    .clickable(role = Role.Button) { if (audio != null) screen = Screen.TALKING else finish() },
                contentAlignment = Alignment.Center,
            ) { Text(if (audio != null) "Back to call" else "Done", style = text(16, 600)) }
        }
    }

    @Composable
    private fun Ended() {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Front door", style = text(28, 800))
            Spacer(Modifier.height(8.dp))
            Text(endedText, style = text(16, 400, C.Muted))
        }
    }
}

// ---------- shared pieces ----------

@Composable
fun Ripple(icon: ImageVector) {
    val t = rememberInfiniteTransition(label = "ripple")
    val a = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "a")
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        listOf(0f, 0.5f).forEach { off ->
            val p = (a.value + off) % 1f
            Box(
                Modifier.size(140.dp).graphicsLayer { scaleX = 1f + p * 0.9f; scaleY = 1f + p * 0.9f; alpha = 0.55f * (1f - p) }
                    .border(2.dp, C.Amber, CircleShape)
            )
        }
        Box(Modifier.size(140.dp).clip(CircleShape).background(C.AmberDeep), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(60.dp), tint = C.Amber)
        }
    }
}

@Composable
private fun Bars() {
    val t = rememberInfiniteTransition(label = "bars")
    val heights = listOf(40, 76, 104, 64, 92, 52, 84, 36)
    Row(Modifier.height(120.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        heights.forEachIndexed { i, h ->
            val s = t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(550, delayMillis = i * 70), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(7.dp).height(h.dp).graphicsLayer { scaleY = s.value }.clip(RoundedCornerShape(4.dp)).background(C.Green))
        }
    }
}

@Composable
fun Pill(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(50)).background(bg).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        Text(label, style = text(18, 700, fg))
    }
}

@Composable
private fun RoundButton(label: String?, icon: ImageVector, bg: Color, fg: Color, desc: String? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(74.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label ?: desc, Modifier.size(30.dp), tint = fg) }
        if (label != null) Text(label, style = text(14, 600))
    }
}

@Composable
private fun Toggle(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (on) Color(0xFFE9EDF5) else C.Card
    val fg = if (on) C.Bg else C.Ink
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        Text(label, style = text(15, if (on) 700 else 600, fg))
    }
}
