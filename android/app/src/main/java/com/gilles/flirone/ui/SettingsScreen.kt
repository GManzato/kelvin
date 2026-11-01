package com.gilles.flirone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gilles.flirone.CameraController
import com.gilles.flirone.Conn
import com.gilles.flirone.Emissivity
import com.gilles.flirone.PH
import com.gilles.flirone.PW
import com.gilles.flirone.Thermal
import com.gilles.flirone.UiState
import com.gilles.flirone.Units
import com.gilles.flirone.ViewSettings
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

private const val DIST_MIN = 0.3f
private const val DIST_MAX = 3f

/** 6 · Settings: measurement, thermal ↔ visible alignment, power, camera info. */
@Composable
fun SettingsScreen(ui: UiState, cam: CameraController) {
    val s = ui.settings
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(C.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
            BasicText("Settings", style = text(24.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            // ===== MEASUREMENT =====
            SectionLabel("MEASUREMENT", top = 8.dp)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Emissivity", style = text(17.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    BasicText("ε ${"%.2f".format(Locale.US, s.emissivity)}", style = text(22.sp, FontWeight.SemiBold, C.Accent))
                }
                BasicText("How well the surface radiates heat. Shiny surfaces reflect their surroundings and read too low.", style = text(13.sp, color = C.Secondary, lineHeight = 18.sp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                for (e in Emissivity.entries) {
                    val sel = e == s.emissivityPreset
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) Color(0x144FD1E8) else Color.Transparent)
                            .tap { ui.update { it.copy(emissivityPreset = e) } }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (sel) C.Accent else C.RadioOff, CircleShape), contentAlignment = Alignment.Center) {
                            if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Accent))
                        }
                        Column(Modifier.weight(1f)) {
                            BasicText(e.label, style = text(14.sp, FontWeight.Medium))
                            BasicText(e.sub, style = text(12.sp, color = C.Secondary))
                        }
                        BasicText("%.2f".format(Locale.US, e.value ?: s.customEmissivity), style = text(14.sp, FontWeight.SemiBold, if (sel) C.Text else C.Secondary))
                    }
                }
                if (s.emissivityPreset == Emissivity.Custom) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText("0.10", style = text(12.sp, color = C.Secondary))
                    ValueSlider(s.customEmissivity, 0.1f, 1f, Modifier.weight(1f)) { v -> ui.update { it.copy(customEmissivity = (v * 100).roundToInt() / 100f) } }
                    BasicText("1.00", style = text(12.sp, color = C.Secondary))
                }
            }
            Card(top = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Reflected temperature", style = text(16.sp, FontWeight.SemiBold))
                        BasicText("Temperature of what the surface mirrors", style = text(12.sp, color = C.Secondary))
                    }
                    // 0.5 °C steps (≈ 1 °F)
                    StepButton("−") { ui.update { it.copy(reflectedC = (it.reflectedC - 0.5f).coerceAtLeast(-20f)) } }
                    BasicText(Units.tu(s.reflectedC), style = text(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 10.dp))
                    StepButton("+") { ui.update { it.copy(reflectedC = (it.reflectedC + 0.5f).coerceAtMost(120f)) } }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Units", style = text(16.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Box(Modifier.size(112.dp, 44.dp)) {
                        Segmented(listOf(false to "°C", true to "°F"), s.fahrenheit, { f -> ui.update { it.copy(fahrenheit = f) } }, height = 36.dp)
                    }
                }
            }

            // ===== ALIGNMENT =====
            SectionLabel("THERMAL ↔ VISIBLE ALIGNMENT", top = 22.dp)
            Card {
                AlignmentPreview(cam, s.thermalFlip)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Subject distance", style = text(16.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    BasicText("%.2f m".format(Locale.US, s.distanceM), style = text(18.sp, FontWeight.SemiBold))
                }
                BasicText("The two lenses sit ~14 mm apart; match the distance so edges line up.", style = text(12.sp, color = C.Secondary, lineHeight = 17.sp), modifier = Modifier.padding(top = 2.dp))
                // logarithmic: 0.3 m … 3 m
                val pos = (ln(s.distanceM / DIST_MIN) / ln(DIST_MAX / DIST_MIN)).toFloat()
                ValueSlider(pos, 0f, 1f, Modifier.fillMaxWidth().padding(top = 6.dp)) { v ->
                    val d = DIST_MIN * Math.pow((DIST_MAX / DIST_MIN).toDouble(), v.toDouble()).toFloat()
                    ui.update { it.copy(distanceM = (d * 20).roundToInt() / 20f) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (l in listOf("0.3 m", "1 m", "3 m")) BasicText(l, style = text(11.sp, color = C.Muted))
                }
                BasicText("Thermal camera rotation", style = text(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Segmented(listOf(false to "0°", true to "180°"), s.thermalFlip, { f -> ui.update { it.copy(thermalFlip = f) } })
                BasicText("Visible camera rotation", style = text(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                Segmented(listOf(false to "0°", true to "180°"), s.visibleFlip, { f -> ui.update { it.copy(visibleFlip = f) } })
                TextLink("Reset alignment", {
                    val d = ViewSettings()
                    ui.update { it.copy(distanceM = d.distanceM, thermalFlip = d.thermalFlip, visibleFlip = d.visibleFlip) }
                }, modifier = Modifier.padding(top = 6.dp))
            }

            // ===== POWER =====
            SectionLabel("POWER & CONNECTION", top = 22.dp)
            Card {
                ToggleRow("Power saving", "Pause the stream when the app is in the background. The camera battery lasts about 1 h of streaming.", s.powerSaving) {
                    ui.update { it.copy(powerSaving = !it.powerSaving) }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                ToggleRow("Calibration sound", "Click when the shutter recalibrates (about every 30 s)", s.calibrationSound) {
                    ui.update { it.copy(calibrationSound = !it.calibrationSound) }
                }
            }

            // ===== CAMERA =====
            SectionLabel("CAMERA", top = 22.dp)
            Card {
                val info = cam.info
                val connected = cam.conn == Conn.Live
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CameraGlyph(connected)
                    Column {
                        BasicText("FLIR ONE", style = text(16.sp, FontWeight.SemiBold))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) C.Connected else C.Muted))
                            BasicText(if (connected) "Connected · Lightning adapter · iAP2" else "Not connected", style = text(13.sp, color = C.Secondary))
                        }
                    }
                }
                val rows = listOf(
                    "MODEL" to info.model, "FIRMWARE" to info.firmware,
                    "SERIAL" to info.serial, "SENSOR" to info.lepton.let { if (it.isEmpty()) "" else "Lepton $it" },
                    "BATTERY" to (info.battery?.let { b -> "$b %" + (info.voltage?.let { " · %.2f V".format(Locale.US, it) } ?: "") } ?: ""),
                    "STREAM" to if (connected) "80×60 · %.1f fps".format(Locale.US, cam.fps) else "",
                )
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pair in rows.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, v) in pair) Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            BasicText(k, style = text(10.sp, FontWeight.SemiBold, C.Muted, 0.12.em))
                            BasicText(v.ifEmpty { "—" }, style = mono(13.sp, C.Text, androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            SectionLabel("COMPATIBILITY", top = 22.dp)
            Card {
                BasicText("Using another FLIR ONE?", style = text(16.sp, FontWeight.SemiBold))
                BasicText("Export phone, USB and connection details to help add support. No images are included; camera serial is optional.", style = text(12.sp, color = C.Secondary, lineHeight = 17.sp), modifier = Modifier.padding(top = 4.dp))
                OutlineButton("Compatibility report", { ui.open(com.gilles.flirone.Screen.Compatibility) }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                KelvinBrand(height = 30.dp)
                CenteredText("Kelvin $version", text(12.sp, color = C.Muted), Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Card(top: androidx.compose.ui.unit.Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = top).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(14.dp), content = content)
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Color(0x29FFFFFF), CircleShape).tap(onClick), contentAlignment = Alignment.Center) {
        BasicText(label, style = text(20.sp, FontWeight.Medium))
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = text(16.sp, FontWeight.SemiBold))
            BasicText(sub, style = text(12.sp, color = C.Secondary, lineHeight = 17.sp), modifier = Modifier.padding(top = 2.dp))
        }
        Toggle(on, onToggle)
    }
}

/** Live thermal image with the visible camera's outlines on top, to check the alignment by eye. */
@Composable
private fun AlignmentPreview(cam: CameraController, thermalFlip: Boolean) {
    val f = cam.frame
    cam.wantEdges = true
    val thermal = remember(f, thermalFlip) {
        f?.let { fr ->
            val a = Thermal.analyze(fr.raw, ViewSettings(thermalFlip = thermalFlip), isoPreview = false)
            Bitmap.createBitmap(a.pixels, PW, PH, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    val edges = remember(f) { f?.edges?.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        if (thermal == null) {
            CenteredText("Connect the camera to preview the alignment", text(13.sp, color = C.Secondary), Modifier.align(Alignment.Center).padding(24.dp))
        } else Canvas(Modifier.fillMaxSize()) {
            // centre crop of the portrait image into the 4:3 card
            for ((img, alpha) in listOfNotNull(thermal to 1f, edges?.let { it to 0.9f })) {
                val scale = maxOf(size.width / img.width, size.height / img.height)
                val w = (img.width * scale).roundToInt(); val h = (img.height * scale).roundToInt()
                drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), IntSize(w, h), alpha = alpha, filterQuality = FilterQuality.Low)
            }
        }
        if (thermal != null) BasicText(
            if (edges == null) "WAITING FOR EDGES" else "LIVE PREVIEW", style = mono(10.sp, C.Isotherm, FontWeight.SemiBold),
            modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB30B0B0F)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CameraGlyph(connected: Boolean) {
    Canvas(Modifier.size(56.dp)) {
        val u = size.width / 56f
        drawRoundRect(Color(0xFF26262E), Offset(6 * u, 14 * u), androidx.compose.ui.geometry.Size(44 * u, 30 * u), androidx.compose.ui.geometry.CornerRadius(8 * u))
        drawCircle(C.Background, 7 * u, Offset(20 * u, 29 * u)); drawCircle(Color(0xFF1B2B33), 3 * u, Offset(20 * u, 29 * u))
        drawCircle(C.Background, 4 * u, Offset(35 * u, 29 * u))
        drawCircle(if (connected) C.Connected else C.Muted, 1.6f * u, Offset(44 * u, 21 * u))
        drawRoundRect(Color(0xFF3A3A44), Offset(22 * u, 44 * u), androidx.compose.ui.geometry.Size(12 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
    }
}
