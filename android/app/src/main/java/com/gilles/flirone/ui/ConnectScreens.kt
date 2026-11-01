package com.gilles.flirone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gilles.flirone.CameraController
import com.gilles.flirone.Conn

private enum class Illo { Plug, Connecting, Restarting, Unsupported }

/** Opens Settings from the connection screens (provided by the app root). */
val LocalOpenSettings = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }
val LocalOpenCompatibility = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** Shared frame: status-bar spacer, Kelvin header, optional illustration, body. */
@Composable
private fun ConnectFrame(illo: Illo?, onBack: (() -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            KelvinBrand(Modifier.weight(1f), height = 28.dp)
            if (onBack != null) TextLink("Back to live view", onBack)
            TextLink("Report", LocalOpenCompatibility.current)
            val openSettings = LocalOpenSettings.current
            Box(Modifier.size(44.dp).tap(openSettings), contentAlignment = Alignment.Center) { Icon(Ic.Sliders, C.Text2, 22.dp) }
        }
        if (illo != null) Illustration(illo)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), content = body)
    }
}

@Composable
private fun Title(s: String) = BasicText(s, style = text(28.sp, FontWeight.SemiBold, spacing = (-0.01).em, lineHeight = 34.sp))

@Composable
private fun Body(s: String, top: androidx.compose.ui.unit.Dp = 8.dp) =
    BasicText(s, style = text(15.sp, color = C.Body, lineHeight = 22.sp), modifier = Modifier.padding(top = top))

@Composable
private fun NumberedStep(n: Int, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
            BasicText("$n", style = text(13.sp, FontWeight.SemiBold))
        }
        Box(Modifier.padding(top = 3.dp)) { content() }
    }
}

// ---------------------------------------------------------------------------------------------
// 7a · Plug in your FLIR ONE
// ---------------------------------------------------------------------------------------------

@Composable
fun PlugInScreen(cam: CameraController, startWithTips: Boolean = false, onBack: (() -> Unit)? = null, onGallery: () -> Unit) {
    var tips by remember { mutableStateOf(startWithTips) }
    ConnectFrame(Illo.Plug, onBack) {
        Title("Plug in your FLIR ONE")
        Body("Kelvin finds the camera on its own once it is powered on.")
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberedStep(1) { BasicText("Connect it to the phone through a Lightning → USB‑C adapter", style = text(15.sp, lineHeight = 22.sp)) }
            NumberedStep(2) { BasicText("Press the power button on the bottom of the camera", style = text(15.sp, lineHeight = 22.sp)) }
            NumberedStep(3) {
                BasicText(buildAnnotatedString {
                    append("Wait about 20 s for the LED to ")
                    withStyle(text(15.sp, FontWeight.SemiBold, C.Connected).toSpanStyle()) { append("blink green") }
                    append(": it is ready")
                }, style = text(15.sp, lineHeight = 22.sp))
            }
        }
        if (tips) BasicText(
            "Remove a thick phone case so the adapter seats fully. If the LED stays off, charge the camera through its own USB‑C port for 10 minutes. When Android asks, allow USB access and tick “Always open Kelvin” so it reconnects by itself.",
            style = text(13.sp, color = C.Soft, lineHeight = 20.sp),
            modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Box(Modifier.weight(1f))
        PrimaryButton("It's plugged in", { cam.scan() })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextLink(if (tips) "Hide tips" else "Not connecting?", { tips = !tips }, C.Secondary)
            TextLink("Open gallery", onGallery)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 7b · Connecting
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectingScreen(step: Int) {
    ConnectFrame(Illo.Connecting) {
        Title("Connecting…")
        Body("Usually takes 2–3 seconds.")
        Column(Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
            listOf("USB permission granted", "Reading camera info", "Starting stream · first calibration").forEachIndexed { i, label ->
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < step -> Icon(Ic.Check, C.Connected, 18.dp)
                            i == step -> Spinner(C.Accent, 18.dp, 2.dp)
                            else -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3A3A44)))
                        }
                    }
                    BasicText(if (i == 0 && step == 0) "Waiting for USB permission" else label, style = text(15.sp, color = if (i <= step) C.Text else C.Muted))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 7e · Camera not responding (restarting)
// ---------------------------------------------------------------------------------------------

@Composable
fun RestartingScreen(conn: Conn.Restarting, now: Long, onGallery: () -> Unit) {
    var replug by remember { mutableStateOf(false) }
    val left = maxOf(0, CameraController.RESTART_SECONDS - ((now - conn.since) / 1000).toInt())
    ConnectFrame(Illo.Restarting) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val w = 4.dp.toPx()
                    drawCircle(Color(0x404FD1E8), size.minDimension / 2 - w / 2, style = Stroke(w))
                    drawArc(C.Accent, -90f, 360f * left / CameraController.RESTART_SECONDS, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                }
                BasicText("$left", style = text(18.sp, FontWeight.SemiBold))
            }
            BasicText(if (left > 0) "Camera not responding · $left s" else "Camera not responding", style = text(22.sp, FontWeight.SemiBold, lineHeight = 28.sp), modifier = Modifier.weight(1f))
        }
        Body("The camera has not answered yet. It may still hold an old session, need more power, or use a different protocol. Kelvin will reconnect when it reappears; use Report to share the connection details.", top = 14.dp)
        Row(
            Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(C.Accent, 14.dp, 2.dp)
            BasicText("Waiting for the camera to come back on USB", style = text(13.sp, color = C.Soft))
        }
        // the camera doesn't always recover by itself: once the countdown is over, show the steps
        if (replug || left == 0) Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf("Unplug the camera from the adapter", "Wait 2 seconds", "Plug it back in. Kelvin connects as soon as it is detected").forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText("${i + 1}", style = text(14.sp, FontWeight.SemiBold, C.Accent))
                    BasicText(s, style = text(14.sp, lineHeight = 20.sp))
                }
            }
        }
        Box(Modifier.weight(1f))
        if (left > 0) OutlineButton(if (replug) "Hide steps" else "Unplug and replug", { replug = !replug }, Modifier.fillMaxWidth())
        TextLink("Open gallery meanwhile", onGallery, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 14.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// 7f · USB access denied
// ---------------------------------------------------------------------------------------------

@Composable
fun DeniedScreen(cam: CameraController, onGallery: () -> Unit) {
    ConnectFrame(null) {
        // mock of Android's USB dialog, with the "always open" checkbox highlighted
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(300.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF2A2A33)).padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp)) {
                    BasicText("Allow Kelvin to access FLIR ONE?", style = text(17.sp, lineHeight = 23.sp))
                    Row(
                        Modifier.padding(top = 16.dp).fillMaxWidth().border(2.dp, C.Accent, RoundedCornerShape(12.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(C.Accent), contentAlignment = Alignment.Center) { Icon(Ic.Check, C.OnAccent, 12.dp) }
                        BasicText("Always open Kelvin when FLIR ONE is connected", style = text(14.sp, color = C.Text2, lineHeight = 20.sp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.End)) {
                        BasicText("Cancel", style = text(14.sp, FontWeight.SemiBold, Color(0xFFA8C7FA)))
                        BasicText("OK", style = text(14.sp, FontWeight.SemiBold, Color(0xFFA8C7FA)))
                    }
                }
                BasicText(
                    "TICK THIS", style = text(11.sp, FontWeight.Bold, C.OnAccent, 0.06.em),
                    modifier = Modifier.padding(start = 14.dp, top = 109.dp).clip(RoundedCornerShape(8.dp)).background(C.Accent).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            BasicText("Android's USB dialog", style = text(11.sp, color = C.Muted, spacing = 0.04.em), modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.padding(top = 18.dp)) { Title("USB access needed") }
        Body("Kelvin reads the temperature stream straight from the camera over USB. Android blocks that until you allow it; nothing leaves your phone.")
        BasicText(buildAnnotatedString {
            append("Tick ")
            withStyle(text(13.sp, FontWeight.SemiBold).toSpanStyle()) { append("“Always open Kelvin”") }
            append(" in the dialog so the app reconnects on its own next time you plug the camera in.")
        }, style = text(13.sp, color = C.Soft, lineHeight = 20.sp), modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 12.dp))
        Box(Modifier.weight(1f))
        PrimaryButton("Grant access", { cam.requestPermission() })
        TextLink("Not now", onGallery, C.Secondary, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 14.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// 7g · Unsupported camera
// ---------------------------------------------------------------------------------------------

@Composable
fun UnsupportedScreen(conn: Conn.Unsupported, onGallery: () -> Unit) {
    val openReport = LocalOpenCompatibility.current
    ConnectFrame(Illo.Unsupported) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Title("This camera isn't supported yet")
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, Color(0x59F5B83D), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicText("DETECTED", style = text(10.sp, FontWeight.SemiBold, C.Warning, 0.14.em))
                BasicText(conn.name, style = text(18.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
                BasicText(conn.detail, style = mono(11.sp, C.Secondary), modifier = Modifier.padding(top = 2.dp))
            }
            SectionLabel("CAMERA SUPPORT")
            Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface)) {
                val rows = listOf(
                    Triple("FLIR ONE · Lightning (435-0004-01)", "Through a Lightning → USB‑C adapter", true),
                    Triple("FLIR ONE · USB‑C / Micro-USB (Android)", "USB discovery and compatibility reports", false),
                    Triple("FLIR ONE Pro / other Lightning models", "Identification and compatibility reports", false),
                    Triple("FLIR ONE Edge · wireless", "Not discoverable over USB", false),
                )
                rows.forEachIndexed { i, (name, sub, ok) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(name, style = text(13.sp, FontWeight.SemiBold))
                            BasicText(sub, style = text(11.sp, color = C.Secondary))
                        }
                        val (label, fg, bg) = if (ok) Triple("SUPPORTED", C.Connected, Color(0x243DDC84)) else Triple("NOT YET", C.Warning, Color(0x24F5B83D))
                        BasicText(label, style = text(10.sp, FontWeight.Bold, fg, 0.08.em), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    if (i < rows.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
                }
            }
            Body("This model's transport, thermal layout or calibration is not verified. Share a compatibility report to help add support; Kelvin will not guess temperatures.", top = 12.dp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("Compatibility report", openReport, Modifier.weight(1f))
            PrimaryButton("Open gallery", onGallery, Modifier.weight(1f), height = 52.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Illustration (ported from the Connect board's SVG; viewBox x −50…290)
// ---------------------------------------------------------------------------------------------

@Composable
private fun Illustration(kind: Illo) {
    val tm = rememberTextMeasurer()
    val t = rememberInfiniteTransition(label = "illo")
    val blink by t.animateFloat(1f, 0.12f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "blink")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
    val h = if (kind == Illo.Plug) 310f else 270f
    Box(Modifier.fillMaxWidth().height(h.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(340.dp, h.dp)) {
            val shift = 50.dp.toPx() // the SVG viewBox starts at x = −50
            withTransform({ translate(left = shift) }) {
                val u = 1.dp.toPx()
                fun o(x: Float, y: Float) = Offset(x * u, y * u)
                fun sz(w: Float, hh: Float) = Size(w * u, hh * u)
                fun rr(x: Float, y: Float, w: Float, hh: Float, r: Float, fill: Color, stroke: Color? = null, sw: Float = 1.5f) {
                    drawRoundRect(fill, o(x, y), sz(w, hh), CornerRadius(r * u))
                    if (stroke != null) drawRoundRect(stroke, o(x, y), sz(w, hh), CornerRadius(r * u), style = Stroke(sw * u))
                }
                fun label(s: String, x: Float, y: Float, end: Boolean) {
                    val lay = tm.measure(s, text(10.5.sp, color = C.Secondary))
                    drawText(lay, topLeft = Offset(if (end) x * u - lay.size.width else x * u, y * u - lay.size.height * 0.8f))
                }
                drawCircle(C.Accent.copy(alpha = 0.05f), 118 * u, o(130f, 170f))
                // phone
                drawRoundRect(Brush.verticalGradient(0f to C.Background, 0.35f to C.Surface2, startY = -20 * u, endY = 150 * u), o(60f, -20f), sz(140f, 170f), CornerRadius(26 * u))
                drawRoundRect(Color(0xFF3A3A44), o(60f, -20f), sz(140f, 170f), CornerRadius(26 * u), style = Stroke(1.5f * u))
                rr(70f, -10f, 120f, 150f, 18f, Color(0xFF101016))
                rr(120f, 146f, 20f, 5f, 2.5f, Color(0xFF3A3A44))
                val dy: Float
                if (kind == Illo.Plug) {
                    drawLine(C.Accent, o(130f, 168f), o(130f, 157f), 1.75f * u, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(3 * u, 4 * u)))
                    drawLine(C.Accent, o(125f, 161f), o(130f, 156f), 1.75f * u, StrokeCap.Round)
                    drawLine(C.Accent, o(130f, 156f), o(135f, 161f), 1.75f * u, StrokeCap.Round)
                    rr(124f, 171f, 12f, 7f, 2.5f, C.Muted)
                    rr(116f, 177f, 28f, 20f, 5f, Color(0xFF3A3A44), Color(0xFF55555F), 1f)
                    drawLine(C.Muted, o(114f, 187f), o(62f, 187f), 1.25f * u, StrokeCap.Round)
                    label("Lightning → USB‑C", 58f, 184f, end = true)
                    label("adapter", 58f, 197f, end = true)
                    rr(126.5f, 197f, 7f, 14f, 2f, C.Body)
                    dy = 32f
                } else {
                    rr(116f, 151f, 28f, 20f, 5f, Color(0xFF3A3A44), Color(0xFF55555F), 1f)
                    rr(126.5f, 171f, 7f, 8f, 2f, C.Body)
                    dy = 0f
                }
                // camera body (attached position y = 179; plug-in position 32 lower)
                rr(70f, 179f + dy, 120f, 58f, 15f, Color(0xFF26262E), Color(0xFF4A4A55))
                drawCircle(C.Background, 14 * u, o(104f, 208f + dy)); drawCircle(Color(0xFF55555F), 14 * u, o(104f, 208f + dy), style = Stroke(1.5f * u))
                drawCircle(Color(0xFF16323B), 6 * u, o(104f, 208f + dy))
                drawCircle(C.Background, 7 * u, o(140f, 208f + dy)); drawCircle(Color(0xFF55555F), 7 * u, o(140f, 208f + dy), style = Stroke(1.5f * u))
                val led = when (kind) { Illo.Restarting -> Color(0xFF55555F); else -> C.Connected }
                drawCircle(led.copy(alpha = if (kind == Illo.Plug) blink else 1f), 3.2f * u, o(170f, 192f + dy))
                rr(152f, 236f + dy, 20f, 4f, 2f, Color(0xFF6A6A76))
                if (kind == Illo.Plug) {
                    drawLine(C.Muted, o(174f, 274f), o(184f, 284f), 1.25f * u, StrokeCap.Round)
                    label("power button", 188f, 292f, end = false)
                }
                if (kind == Illo.Connecting) for (phase in listOf(pulse, (pulse + 0.5f) % 1f)) {
                    drawCircle(C.Accent.copy(alpha = 0.8f * (1 - phase)), (16 + 30 * phase) * u, o(104f, 208f), style = Stroke(1.5f * u))
                }
                if (kind == Illo.Unsupported) {
                    drawCircle(C.Background, 14.5f * u, o(190f, 181f))
                    drawCircle(C.Warning, 13 * u, o(190f, 181f))
                    val q = tm.measure("?", text(15.sp, FontWeight.Bold, Color(0xFF2A1C00)).copy(textAlign = TextAlign.Center))
                    drawText(q, topLeft = Offset(190 * u - q.size.width / 2f, 181 * u - q.size.height / 2f))
                }
            }
        }
    }
}
