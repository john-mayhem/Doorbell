package com.mayhem.doorbell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Colours from the approved design (https://claude.ai/artifact/Ujodis3qoaQm5saCofPv5N). */
object C {
    val Bg = Color(0xFF0E1320)
    val CallBg = Color(0xFF0B0F19)
    val Card = Color(0xFF18202F)
    val Row = Color(0xFF151C2A)
    val Ring = Color(0xFF222C3F)
    val Halo = Color(0xFF1C2536)
    val Line = Color(0xFF2A3448)
    val Ink = Color(0xFFF2F4F8)
    val Muted = Color(0xFF9AA4B6)
    val Amber = Color(0xFFF5A524)
    val AmberInk = Color(0xFF1B1406)
    val AmberDeep = Color(0xFF2B220F)
    val Green = Color(0xFF2FBF71)
    val GreenInk = Color(0xFF08210F)
    val GreenDot = Color(0xFF3DD68C)
    val GreenText = Color(0xFF6FE0A2)
    val GreenBg = Color(0xFF11271D)
    val Red = Color(0xFFE5484D)
    val RedText = Color(0xFFFF8A80)
    val RedBg = Color(0xFF3A1E1E)
    val AmberBg = Color(0xFF3A2C10)
    val GreenTile = Color(0xFF12291E)
}

@OptIn(ExperimentalTextApi::class)
private fun manrope(w: Int) = Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

@Composable
fun DoorbellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = C.Bg, surface = C.Bg, onBackground = C.Ink, onSurface = C.Ink, primary = C.Amber),
        typography = MaterialTheme.typography.run {
            copy(
                bodyLarge = bodyLarge.copy(fontFamily = Manrope),
                bodyMedium = bodyMedium.copy(fontFamily = Manrope),
                labelLarge = labelLarge.copy(fontFamily = Manrope),
            )
        },
        content = content,
    )
}

fun text(size: Int, weight: Int = 400, color: Color = C.Ink) =
    TextStyle(fontFamily = Manrope, fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = FontWeight(weight), color = color)

/** Line icons (Lucide shapes, as in the design), built from SVG path data. */
object Icons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 2f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.White),
                    strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private const val PHONE = "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"

    val Gear = icon("gear",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        circle(12f, 12f, 3f))
    val Door = icon("door", "M18 20V6a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v14", "M2 20h20", "M14 12v.01")
    val DoorOpen = icon("door-open", "M13 4h3a2 2 0 0 1 2 2v14", "M2 20h3", "M13 20h9", "M10 12v.01",
        "M13 4.562v16.157a1 1 0 0 1-1.242.97L5 20V5.562a2 2 0 0 1 1.515-1.94l4-1A2 2 0 0 1 13 4.561Z")
    val Bell = icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Phone = icon("phone", PHONE)
    val PhoneMissed = icon("phone-missed", "M22 2L16 8", "M16 2L22 8", PHONE)
    val PhoneOff = icon("phone-off",
        "M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91",
        "M22 2L2 22")
    val Mic = icon("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
    val MicOff = icon("mic-off", "M2 2l20 20", "M18.89 13.23A7.12 7.12 0 0 0 19 12v-2", "M5 10v2a7 7 0 0 0 12 5",
        "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M9 9v3a3 3 0 0 0 5.12 2.12", "M12 19v3")
    val Speaker = icon("volume", "M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M19.07 4.93a10 10 0 0 1 0 14.14")
    val Earpiece = icon("ear", "M6 8.5a6.5 6.5 0 1 1 13 0c0 6-6 6-6 10a3.5 3.5 0 1 1-7 0", "M15 8.5a2.5 2.5 0 0 0-5 0v1a2 2 0 1 1 0 4")
    val Key = icon("key",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        circle(16.5f, 7.5f, .5f))
    val Check = icon("check", "M20 6 9 17l-5-5")
    val Back = icon("arrow-left", "m12 19-7-7 7-7", "M19 12H5")
    val Chevron = icon("chevron-right", "m9 18 6-6-6-6")
    val LockRing = icon("phone-bell", rect(5f, 2f, 14f, 20f, 2f), "M12 18h.01", "M9 7.5a3 3 0 0 1 6 0c0 3.5 1.5 4.5 1.5 4.5h-9S9 11 9 7.5", stroke = 1.6f)
    val Battery = icon("battery", rect(2f, 6f, 16f, 12f, 2f), "M22 14v-4", "M6 10v4", "M10 10v4", "M14 10v4", stroke = 1.6f)
    val BellBig = icon("bell-big", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0", stroke = 1.6f)
    val Download = icon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3", stroke = 1.6f)
    val MicBig = icon("mic-big", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3", stroke = 1.6f)

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
}
