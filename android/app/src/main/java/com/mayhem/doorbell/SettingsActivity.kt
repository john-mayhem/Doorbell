package com.mayhem.doorbell

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsActivity : ComponentActivity() {

    private var status by mutableStateOf<JSONObject?>(null)
    private var checked by mutableStateOf(false)      // first status check finished
    private var name by mutableStateOf("")
    private var manualHost by mutableStateOf("")
    private var ringtone by mutableStateOf<Uri?>(null)
    private var missing by mutableStateOf(emptyList<Perm>())
    private var dialog by mutableStateOf<String?>(null) // "name" / "address" / "ringtone"

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) ?: return@registerForActivityResult
        Prefs.setRingtone(this, uri)
        ringtone = uri
        Tone.preview(this, uri, Prefs.ringVolume(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        name = Prefs.name(this)
        manualHost = Prefs.manualHost(this)
        ringtone = Prefs.ringtone(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    status = Door.find(applicationContext)?.let { Door.status(it) }
                    checked = true
                    delay(3000)
                }
            }
        }
        setContent { DoorbellTheme { Page() } }
    }

    override fun onResume() {
        super.onResume()
        missing = Perm.missing(this)  // may have changed in Android's settings
    }

    override fun onStart() { super.onStart(); Updater.screens++ }

    override fun onStop() {
        Tone.stopPreview()
        Updater.screens--
        super.onStop()
    }

    private fun checkOrUpdate() {
        val app = application as DoorbellApp
        when (val s = Updater.state.value) {
            is Updater.State.Available -> when {
                !Perm.UPDATES.granted(this) -> startActivity(Intent(this, SetupActivity::class.java))
                else -> app.scope.launch { Updater.install(applicationContext, s.r) }
            }
            is Updater.State.Checking, is Updater.State.Downloading -> Unit
            else -> app.scope.launch { Updater.check(applicationContext) }
        }
    }

    /** Re-register so the doorbell knows this phone's new name (or finds it at the new address). */
    private fun reRegister() {
        status = null
        checked = false
        (application as DoorbellApp).scope.launch { Door.register(applicationContext) }
    }

    // ---------- page ----------

    @Composable
    private fun Page() {
        Column(
            Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(C.Card).clickable(role = Role.Button) { finish() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Back, "Back", Modifier.size(20.dp), tint = C.Ink) }
                Text("Settings", style = text(26, 800))
            }

            Section("Connection") {
                val st = status
                val addr = Prefs.target(this@SettingsActivity).removeSuffix(":80")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Doorbell", style = text(15, 600))
                        Text(
                            when {
                                st != null -> "doorbell.local · $addr"
                                !checked -> "Looking…"
                                else -> "Not found on this Wi-Fi"
                            },
                            style = text(13, 400, C.Muted),
                        )
                    }
                    when {
                        st != null -> Dot("Online", C.GreenText, C.GreenDot)
                        checked -> Dot("Offline", C.Muted, C.Muted)
                    }
                }
                st?.optJSONObject("push")?.optJSONArray("phones")?.let { arr ->
                    Divider()
                    val phones = (0 until arr.length()).map { arr.getString(it) }
                    ValueRow("Phones that ring", phones.joinToString(", ").ifEmpty { "None yet" }, chevron = false) {}
                }
                Divider()
                ValueRow("Address", manualHost.ifBlank { "Find automatically" }) { dialog = "address" }
                Divider()
                ValueRow("This phone's name", name) { dialog = "name" }
            }

            Section("Sound") {
                ValueRow("Ringtone", ringtoneTitle()) { dialog = "ringtone" }
                Divider()
                VolumeRow("Ring volume", Prefs.ringVolume(this@SettingsActivity)) {
                    Prefs.setRingVolume(this@SettingsActivity, it)
                    Tone.preview(this@SettingsActivity, ringtone, it)
                }
                Divider()
                VolumeRow("Door voice volume", Prefs.voiceVolume(this@SettingsActivity)) {
                    Prefs.setVoiceVolume(this@SettingsActivity, it)
                }
                Divider()
                SwitchRow("Vibrate when ringing", Prefs.vibrate(this@SettingsActivity)) { Prefs.setVibrate(this@SettingsActivity, it) }
                Divider()
                SwitchRow("Start calls on speaker", Prefs.speaker(this@SettingsActivity)) { Prefs.setSpeaker(this@SettingsActivity, it) }
            }

            Section("App") {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = missing.isNotEmpty(), role = Role.Button) {
                            startActivity(Intent(this@SettingsActivity, SetupActivity::class.java))
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Permissions", style = text(15, 600))
                        Text(
                            when (missing.size) {
                                0 -> "Everything needed to ring is allowed"
                                1 -> "1 thing needs your OK"
                                else -> "${missing.size} things need your OK"
                            },
                            style = text(13, 400, if (missing.isEmpty()) C.Muted else C.Amber),
                        )
                    }
                    if (missing.isEmpty()) Icon(Icons.Check, null, Modifier.size(22.dp), tint = C.GreenDot)
                    else Icon(Icons.Chevron, null, Modifier.size(18.dp), tint = C.Muted)
                }
                Divider()
                val up by Updater.state.collectAsStateWithLifecycle()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${Updater.current(this@SettingsActivity)}", style = text(15, 600))
                        Text(
                            when (val s = up) {
                                Updater.State.Idle -> "Checks for updates twice a day"
                                Updater.State.Checking -> "Checking…"
                                Updater.State.UpToDate -> "Up to date"
                                is Updater.State.Available -> "Version ${s.r.version} is ready"
                                is Updater.State.Downloading -> "Getting ${s.r.version}…"
                                is Updater.State.Failed -> s.why
                            },
                            style = text(13, 400, if (up is Updater.State.Available) C.Amber else C.Muted),
                        )
                    }
                    val busy = up is Updater.State.Checking || up is Updater.State.Downloading
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (up is Updater.State.Available) C.Amber else Color.Transparent)
                            .border(1.dp, if (up is Updater.State.Available) C.Amber else C.Line, RoundedCornerShape(50))
                            .clickable(enabled = !busy, role = Role.Button) { checkOrUpdate() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(if (up is Updater.State.Available) "Update" else "Check now",
                            style = text(13, 600, if (up is Updater.State.Available) C.AmberInk else if (busy) C.Muted else C.Ink))
                    }
                }
                Divider()
                ValueRow("Doorbell firmware", status?.optString("fw") ?: "Offline", chevron = false) {}
            }
        }

        when (dialog) {
            "name" -> TextDialog("This phone's name", "Shown to the others, like \"Answered by $name\".", name, KeyboardType.Text) {
                if (it.isNotBlank() && it.trim() != name) {
                    Prefs.setName(this, it)
                    name = Prefs.name(this)
                    reRegister()
                }
            }
            "address" -> TextDialog("Address", "Leave empty to find the doorbell automatically. Or type its IP, like 192.168.18.151.",
                manualHost, KeyboardType.Uri) {
                if (it.trim() != manualHost) {
                    Prefs.setManualHost(this, it)
                    manualHost = Prefs.manualHost(this)
                    reRegister()
                }
            }
            "ringtone" -> RingtoneDialog()
        }
    }

    private fun ringtoneTitle(): String = ringtone?.let { u ->
        runCatching { RingtoneManager.getRingtone(this, u)?.getTitle(this) }.getOrNull()
    } ?: "Doorbell chime"

    // ---------- dialogs ----------

    @Composable
    private fun TextDialog(title: String, hint: String, initial: String, keyboard: KeyboardType, onSave: (String) -> Unit) {
        var value by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = C.Card,
            title = { Text(title, style = text(20, 700)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(hint, style = text(14, 400, C.Muted))
                    OutlinedTextField(
                        value, { value = it }, singleLine = true, textStyle = text(16, 500),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = C.Amber, unfocusedBorderColor = C.Line, cursorColor = C.Amber,
                        ),
                    )
                }
            },
            confirmButton = { DialogButton("Save", C.Amber) { onSave(value); dialog = null } },
            dismissButton = { DialogButton("Cancel", C.Muted) { dialog = null } },
        )
    }

    @Composable
    private fun RingtoneDialog() {
        AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = C.Card,
            title = { Text("Ringtone", style = text(20, 700)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choice("Doorbell chime", ringtone == null) {
                        Prefs.setRingtone(this@SettingsActivity, null)
                        ringtone = null
                        Tone.preview(this@SettingsActivity, null, Prefs.ringVolume(this@SettingsActivity))
                        dialog = null
                    }
                    Choice("Choose from the phone…", ringtone != null) {
                        dialog = null
                        pickRingtone.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtone)
                        )
                    }
                }
            },
            confirmButton = { DialogButton("Close", C.Muted) { dialog = null } },
        )
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = text(16, 600), modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Check, null, Modifier.size(20.dp), tint = C.Amber)
        }
    }

    @Composable
    private fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = text(15, 700, color))
        }
    }
}

// ---------- rows (as in the design) ----------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = text(12, 700, C.Muted).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.96f, androidx.compose.ui.unit.TextUnitType.Sp)),
            modifier = Modifier.padding(horizontal = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Card), content = content)
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222B3D)))

@Composable
private fun Dot(label: String, fg: Color, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(label, style = text(13, 600, fg))
    }
}

@Composable
private fun ValueRow(label: String, value: String, chevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = chevron, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = text(15, 600))
        Text(value, style = text(14, 400, C.Muted), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2)
        if (chevron) Icon(Icons.Chevron, null, Modifier.size(18.dp), tint = C.Muted)
    }
}

@Composable
private fun VolumeRow(label: String, initial: Int, onDone: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(initial.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = text(15, 600), modifier = Modifier.weight(1f))
            Text("${v.toInt()}%", style = text(14, 400, C.Muted))
        }
        Slider(
            value = v, onValueChange = { v = it }, valueRange = 0f..100f,
            onValueChangeFinished = { onDone(v.toInt()) },
            colors = SliderDefaults.colors(thumbColor = C.Amber, activeTrackColor = C.Amber, inactiveTrackColor = C.Line),
        )
    }
}

@Composable
private fun SwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { on = !on; onChange(on) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = text(15, 600), modifier = Modifier.weight(1f))
        Switch(
            checked = on, onCheckedChange = { on = it; onChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.AmberInk, checkedTrackColor = C.Amber, checkedBorderColor = C.Amber,
                uncheckedThumbColor = C.Ink, uncheckedTrackColor = C.Line, uncheckedBorderColor = C.Line,
            ),
        )
    }
}
