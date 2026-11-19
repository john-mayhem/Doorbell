package com.mayhem.doorbell

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One screen per missing permission, explaining it before Android asks. Shown on first launch,
 * and again from Settings when something is still missing. "Not now" just moves on.
 */
class SetupActivity : ComponentActivity() {

    private lateinit var steps: List<Perm>
    private var index by mutableIntStateOf(0)

    private val askRuntime = registerForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { next() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.setSetupDone(this)
        steps = Perm.missing(this)
        if (steps.isEmpty()) { finish(); return }
        setContent { DoorbellTheme { Step(steps[index]) } }
    }

    private fun next() {
        // Skip anything that got allowed along the way (e.g. Samsung grants two at once).
        var i = index + 1
        while (i < steps.size && steps[i].granted(this)) i++
        if (i >= steps.size) finish() else index = i
    }

    private fun allow(p: Perm) {
        val pkg = Uri.parse("package:$packageName")
        when (p) {
            Perm.NOTIFY, Perm.MIC -> {
                val perm = if (p == Perm.NOTIFY) Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.RECORD_AUDIO
                val prefs = getSharedPreferences("doorbell", MODE_PRIVATE)
                // Android stops showing its dialog after two refusals; then the app's settings page is the only way.
                if (prefs.getBoolean("asked_$perm", false) && !shouldShowRequestPermissionRationale(perm)) {
                    openSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                } else {
                    prefs.edit().putBoolean("asked_$perm", true).apply()
                    askRuntime.launch(perm)
                }
            }
            Perm.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                openSettings.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            } else next()
            Perm.UPDATES -> openSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
            Perm.BATTERY -> runCatching {
                @Suppress("BatteryLife")
                openSettings.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }.onFailure { next() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Step(p: Perm) {
        Column(
            Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding().padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Step ${index + 1} of ${steps.size}", style = text(14, 600, C.Muted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { i ->
                        Box(Modifier.width(22.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= index) C.Amber else C.Line))
                    }
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(44.dp)).background(C.Card), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, Modifier.size(64.dp), tint = C.Amber)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p.title, style = text(28, 800), textAlign = TextAlign.Center)
                    Text(p.text, style = text(16, 400, C.Muted).copy(lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        textAlign = TextAlign.Center)
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(50)).background(C.Amber)
                        .clickable(role = Role.Button) { allow(p) },
                    contentAlignment = Alignment.Center,
                ) { Text("Allow", style = text(17, 700, C.AmberInk)) }
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { next() },
                    contentAlignment = Alignment.Center,
                ) { Text("Not now", style = text(15, 600, C.Muted)) }
            }
        }
    }
}
