package be.apexlab.kelvin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import be.apexlab.kelvin.Analysis
import be.apexlab.kelvin.Emissivity
import be.apexlab.kelvin.IsoMode
import be.apexlab.kelvin.Palette
import be.apexlab.kelvin.SheetKind
import be.apexlab.kelvin.Tool
import be.apexlab.kelvin.UiState
import be.apexlab.kelvin.ViewMode
import be.apexlab.kelvin.Screen
import be.apexlab.kelvin.Units
import androidx.compose.foundation.layout.width
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.offset(x = 10.dp).size(44.dp).clip(CircleShape).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(Ic.Close, C.Secondary)
    }
}

// ---------------------------------------------------------------------------------------------
// 2 · Palette & view
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletteSheet(ui: UiState) {
    val s = ui.settings
    val close = { ui.sheet = SheetKind.None }
    Sheet(onDismiss = close, title = "Palette & view", trailing = { CloseButton(close) }) {
        SectionLabel("PALETTE", top = 6.dp)
        val rows = Palette.entries.chunked(4)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in row) {
                    val sel = p == s.palette
                    Column(Modifier.weight(1f).tap { ui.update { it.copy(palette = p, view = if (it.view == ViewMode.Visible) ViewMode.Thermal else it.view) } }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(44.dp)
                                .then(if (sel) Modifier.border(2.dp, C.Accent, RoundedCornerShape(14.dp)).padding(4.dp) else Modifier)
                                .clip(RoundedCornerShape(if (sel) 10.dp else 12.dp))
                                .background(Brush.horizontalGradient(p.colors.map { Color(it) }))
                                .then(if (!sel) Modifier.border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xC70B0B0F)), contentAlignment = Alignment.Center) {
                                Icon(Ic.Check, Color.White, 14.dp)
                            }
                        }
                        CenteredText(p.label, text(12.sp, FontWeight.Medium, if (sel) C.Text else C.Secondary), Modifier.fillMaxWidth())
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        // While an alarm runs the image is greyscale + isotherm, so palettes have no visible effect.
        if (s.alarmOn) Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1FD4FF3A))
                .border(1.dp, Color(0x59D4FF3A), RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ic.Bell, C.Isotherm, 18.dp)
            BasicText(
                "Alarm is on: the image stays greyscale with matches highlighted. The palette shows once it's off.",
                style = text(12.sp, color = C.Text2, lineHeight = 17.sp), modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(C.Isotherm)
                    .tap { ui.update { it.copy(alarmOn = false) } }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("Turn off", style = text(12.sp, FontWeight.SemiBold, C.OnIsotherm)) }
        }

        SectionLabel("VIEW")
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Well).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((mode, icon) in listOf(ViewMode.Thermal to Ic.Thermal, ViewMode.Visible to Ic.Visible, ViewMode.Blend to Ic.Blend, ViewMode.Edges to Ic.Edges)) {
                val sel = s.view == mode
                Column(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) C.Surface3 else Color.Transparent)
                        .tap { ui.update { it.copy(view = mode) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    val c = if (sel) C.Text else C.Secondary
                    Icon(icon, c)
                    BasicText(mode.name, style = text(12.sp, FontWeight.Medium, c))
                }
            }
        }
        if (s.view == ViewMode.Blend) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Visible", style = text(12.sp, color = C.Secondary))
            ValueSlider(s.blend, 0f, 1f, Modifier.weight(1f)) { v -> ui.update { it.copy(blend = (v * 100).roundToInt() / 100f) } }
            BasicText("Thermal", style = text(12.sp, color = C.Secondary))
            BasicText("${(s.blend * 100).roundToInt()}%", style = text(12.sp, FontWeight.SemiBold), modifier = Modifier.width(40.dp))
        }
        if (s.view == ViewMode.Edges) BasicText(
            buildAnnotatedString {
                withStyle(text(12.sp, color = C.Secondary).toSpanStyle()) { append("Outlines from the visible camera drawn over the thermal image. Accuracy depends on ") }
                withStyle(text(12.sp, color = C.Accent).toSpanStyle()) { append("alignment distance") }
                withStyle(text(12.sp, color = C.Secondary).toSpanStyle()) { append(" (now ${"%.2f".format(java.util.Locale.US, s.distanceM)} m).") }
            },
            style = text(12.sp, lineHeight = 17.sp),
            modifier = Modifier.padding(top = 12.dp).tap { ui.sheet = SheetKind.None; ui.open(Screen.Settings) },
        )

        SectionLabel("MEASURE")
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((tool, info) in listOf(
                Tool.Spot to Triple("Spot", "Single point", Ic.Spot),
                Tool.Box to Triple("Box", "Min · max · avg", Ic.Box),
                Tool.Delta to Triple("Delta", "A minus B", Ic.Delta),
            )) ToolCard(info.first, info.second, info.third, s.tool == tool, Modifier.weight(1f)) { ui.update { it.copy(tool = tool) } }
        }
    }
}

@Composable
private fun ToolCard(label: String, sub: String, icon: ImageVector, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(16.dp))
            .background(if (sel) Color(0x1A4FD1E8) else C.Surface2)
            .border(1.dp, if (sel) C.Accent else Color(0x0FFFFFFF), RoundedCornerShape(16.dp))
            .tap(onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, if (sel) C.Accent else C.Soft)
        Column {
            BasicText(label, style = text(14.sp, FontWeight.SemiBold))
            BasicText(sub, style = text(11.sp, color = C.Secondary), modifier = Modifier.padding(top = 1.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 3 · Alarms & isotherms
// ---------------------------------------------------------------------------------------------

private const val ISO_MIN = 20f
private const val ISO_MAX = 60f

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmSheet(ui: UiState, a: Analysis?) {
    val s = ui.settings
    val close = { ui.sheet = SheetKind.None }
    Sheet(
        onDismiss = close,
        title = "Alarms & isotherms",
        subtitle = "Paints matching pixels over a greyscale image",
        trailing = { Toggle(s.alarmOn) { ui.update { it.copy(alarmOn = !it.alarmOn) } } },
    ) {
        Segmented(
            listOf(IsoMode.Above to "Above", IsoMode.Below to "Below", IsoMode.Between to "Between"),
            s.isoMode, { m -> ui.update { it.copy(isoMode = m) } }, modifier = Modifier.padding(top = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                BasicText(
                    when (s.isoMode) { IsoMode.Above -> "HIGHLIGHT ABOVE"; IsoMode.Below -> "HIGHLIGHT BELOW"; IsoMode.Between -> "HIGHLIGHT BETWEEN" },
                    style = Label,
                )
                val value = if (s.isoMode == IsoMode.Between) "${Units.whole(s.isoLo)} – ${Units.whole(s.isoHi)}" else Units.whole(s.isoLo)
                BasicText(
                    buildAnnotatedString {
                        withStyle(text(38.sp, FontWeight.SemiBold, spacing = (-0.02).em).toSpanStyle()) { append(value) }
                        withStyle(text(18.sp, FontWeight.Medium, C.Soft).toSpanStyle()) { append(" " + Units.label) }
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(C.Isotherm))
                    BasicText("${a?.isoMatchPct?.takeIf { it.isFinite() }?.let { "%.1f".format(java.util.Locale.US, it) } ?: "—"} %", style = text(15.sp, FontWeight.SemiBold))
                }
                BasicText("of frame matches", style = text(12.sp, color = C.Secondary))
            }
        }
        ThresholdSlider(s.isoMode, s.isoLo, s.isoHi) { lo, hi -> ui.update { it.copy(isoLo = lo, isoHi = hi) } }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((i, c) in listOf(20f, 30f, 40f, 50f, 60f).withIndex()) {
                BasicText(Units.whole(c) + if (i == 0 || i == 4) " " + Units.label else "", style = text(11.sp, color = C.Muted))
            }
        }
        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("Hot water ≥ ${Units.whole(48f)} ${Units.label}") { ui.update { it.copy(isoMode = IsoMode.Above, isoLo = 48f, alarmOn = true) } }
            PresetChip("Body heat ${Units.whole(32f)}–${Units.whole(36f)} ${Units.label}") { ui.update { it.copy(isoMode = IsoMode.Between, isoLo = 32f, isoHi = 36f, alarmOn = true) } }
            PresetChip("Cold draft ≤ ${Units.whole(20f)} ${Units.label}") { ui.update { it.copy(isoMode = IsoMode.Below, isoLo = 20f, alarmOn = true) } }
        }
        SectionLabel("WHEN THE THRESHOLD IS CROSSED")
        Column(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2)) {
            SwitchRow(Ic.Sound, "Sound", "Short beep, at most every 10 s", s.sound, divider = true) { ui.update { it.copy(sound = !it.sound) } }
            SwitchRow(Ic.Vibrate, "Vibrate", "Haptic pulse when it first matches", s.vibrate, divider = false) { ui.update { it.copy(vibrate = !it.vibrate) } }
        }
        PrimaryButton("Done", close, Modifier.padding(top = 16.dp), height = 52.dp)
    }
}

@Composable
private fun ThresholdSlider(mode: IsoMode, lo: Float, hi: Float, onChange: (Float, Float) -> Unit) {
    val density = LocalDensity.current
    val curLo by rememberUpdatedState(lo)
    val curHi by rememberUpdatedState(hi)
    val curMode by rememberUpdatedState(mode)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)) {
        val track = with(density) { maxWidth.toPx() }
        val thumb = with(density) { 24.dp.toPx() }
        fun px(v: Float) = (v - ISO_MIN) / (ISO_MAX - ISO_MIN) * track
        fun value(x: Float) = (ISO_MIN + (x / track) * (ISO_MAX - ISO_MIN)).coerceIn(ISO_MIN, ISO_MAX).roundToInt().toFloat()
        Canvas(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val moveHi = curMode == IsoMode.Between && abs(down.position.x - px(curHi)) < abs(down.position.x - px(curLo))
                    fun set(x: Float) {
                        val v = value(x)
                        if (moveHi) onChange(curLo, maxOf(v, curLo + 1)) else onChange(if (curMode == IsoMode.Between) minOf(v, curHi - 1) else v, curHi)
                    }
                    set(down.position.x)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) { set(it.position.x); it.consume() } }
                    } while (ev.changes.any { it.pressed })
                }
            },
        ) {
            val cy = size.height / 2
            val h = 6.dp.toPx()
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(C.Track, androidx.compose.ui.geometry.Offset(0f, cy - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
            val (l, w) = when (mode) {
                IsoMode.Above -> px(lo) to size.width - px(lo)
                IsoMode.Below -> 0f to px(lo)
                IsoMode.Between -> px(lo) to px(hi) - px(lo)
            }
            drawRoundRect(C.Isotherm, androidx.compose.ui.geometry.Offset(l, cy - h / 2), androidx.compose.ui.geometry.Size(w, h), r)
            val thumbs = if (mode == IsoMode.Between) listOf(lo, hi) else listOf(lo)
            for (t in thumbs) {
                val x = px(t).coerceIn(thumb / 2, size.width - thumb / 2)
                drawCircle(Color(0x80000000), thumb / 2 + 1.5f, androidx.compose.ui.geometry.Offset(x, cy + 2f))
                drawCircle(Color.White, thumb / 2, androidx.compose.ui.geometry.Offset(x, cy))
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(17.dp)).tap(onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = text(12.sp, FontWeight.Medium, C.Text2)) }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, sub: String, on: Boolean, divider: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, C.Soft)
        Column(Modifier.weight(1f)) {
            BasicText(title, style = text(14.sp, FontWeight.Medium))
            BasicText(sub, style = text(12.sp, color = C.Secondary))
        }
        Toggle(on, onToggle)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
}

// ---------------------------------------------------------------------------------------------
// 7m · Quick emissivity
// ---------------------------------------------------------------------------------------------

@Composable
fun EmissivitySheet(ui: UiState, a: Analysis?) {
    val s = ui.settings
    val close = { ui.sheet = SheetKind.None }
    Sheet(
        onDismiss = close,
        title = "Emissivity",
        subtitle = "Match the surface you are measuring",
        trailing = { BasicText("ε ${"%.2f".format(java.util.Locale.US, s.emissivity)}", style = text(26.sp, FontWeight.SemiBold, C.Accent)) },
    ) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (e in Emissivity.entries) {
                val sel = e == s.emissivityPreset
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Color(0x144FD1E8) else Color.Transparent)
                        .tap { ui.update { it.copy(emissivityPreset = e) } }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (sel) C.Accent else C.RadioOff, CircleShape), contentAlignment = Alignment.Center) {
                        if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Accent))
                    }
                    Column(Modifier.weight(1f)) {
                        BasicText(e.label, style = text(14.sp, FontWeight.Medium))
                        BasicText(e.sub, style = text(12.sp, color = C.Secondary), modifier = Modifier.padding(top = 1.dp))
                    }
                    BasicText("%.2f".format(java.util.Locale.US, e.value ?: s.customEmissivity), style = text(14.sp, FontWeight.SemiBold, if (sel) C.Text else C.Secondary))
                }
            }
        }
        if (s.emissivityPreset == Emissivity.Custom) Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("0.10", style = text(12.sp, color = C.Secondary))
            ValueSlider(s.customEmissivity, 0.10f, 1.00f, Modifier.weight(1f)) { v -> ui.update { it.copy(customEmissivity = (v * 100).roundToInt() / 100f) } }
            BasicText("1.00", style = text(12.sp, color = C.Secondary))
        }
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                buildAnnotatedString {
                    withStyle(text(13.sp, color = C.Body).toSpanStyle()) { append("Spot now reads ") }
                    withStyle(text(13.sp, FontWeight.SemiBold).toSpanStyle()) { append(a?.at(s.spotA)?.takeIf { it.valid }?.let { "${it.text} ${Units.label}" } ?: "—") }
                    withStyle(text(13.sp, color = C.Body).toSpanStyle()) { append(" · reflected ${Units.tu(s.reflectedC)}") }
                },
                modifier = Modifier.weight(1f),
            )
            TextLink("More", { ui.sheet = SheetKind.None; ui.open(Screen.Settings) })
        }
        PrimaryButton("Done", close, Modifier.padding(top = 14.dp), height = 52.dp)
    }
}

@Composable
internal fun ValueSlider(v: Float, min: Float, max: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val cur by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.height(36.dp)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        Canvas(
            Modifier.fillMaxWidth().height(36.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun set(x: Float) = cur(min + (x / w).coerceIn(0f, 1f) * (max - min))
                    set(down.position.x)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) { set(it.position.x); it.consume() } }
                    } while (ev.changes.any { it.pressed })
                }
            },
        ) {
            val cy = size.height / 2
            val x = (v - min) / (max - min) * size.width
            drawLine(C.Track, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(size.width, cy), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(C.Accent, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(x, cy), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(C.Accent, 10.dp.toPx(), androidx.compose.ui.geometry.Offset(x, cy))
        }
    }
}
