package be.apexlab.kelvin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import be.apexlab.kelvin.Capture
import be.apexlab.kelvin.CaptureKind
import be.apexlab.kelvin.Screen
import be.apexlab.kelvin.ThermalApp
import be.apexlab.kelvin.TimelapseLogger
import be.apexlab.kelvin.TimelapseSample
import be.apexlab.kelvin.Units
import be.apexlab.kelvin.fmt1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
private val HM = SimpleDateFormat("HH:mm", Locale.US)

/** Timelapse log: max temperature over time for the running log ([id] null) or a saved one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelapseScreen(id: String?, app: ThermalApp) {
    val ui = app.ui
    val logger = app.timelapse
    var saved by remember(id) { mutableStateOf<Pair<Capture, List<TimelapseSample>>?>(null) }
    if (id != null) LaunchedEffect(id) {
        saved = withContext(Dispatchers.IO) { app.captures.get(id)?.takeIf { it.kind == CaptureKind.Timelapse }?.let { it to app.captures.timelapse(it) } }
    }
    val live = id == null
    val samples: List<TimelapseSample> = if (live) logger.samples else saved?.second ?: emptyList()
    var scrub by remember(id) { mutableStateOf<Int?>(null) }
    val idx = (scrub ?: samples.lastIndex).coerceAtMost(samples.lastIndex)
    val cur = samples.getOrNull(idx)
    var toast by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val palette = ui.settings.palette

    Box(Modifier.fillMaxSize().background(C.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            // ===== latest (or scrubbed) frame =====
            Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(Color.Black)) {
                val img = remember(cur, palette) { cur?.let { app.captures.render(it.temps, palette, it.min, it.max).asImageBitmap() } }
                if (img != null) Canvas(Modifier.fillMaxSize()) {
                    val scale = maxOf(size.width / img.width, size.height / img.height)
                    val w = (img.width * scale).roundToInt(); val h = (img.height * scale).roundToInt()
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), IntSize(w, h), filterQuality = FilterQuality.Low)
                }
                if (cur != null && cur.validCount == 0) Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(15.dp)).background(Color(0xB30B0B0F)).padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText("No valid temperatures in this frame", style = text(13.sp, FontWeight.SemiBold, C.Warning)) }
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color(0xD10B0B0F), Color.Transparent))))
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
                    BasicText("Timelapse log", style = text(18.sp, FontWeight.SemiBold, shadow = ReadingShadow), modifier = Modifier.padding(start = 4.dp))
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(12.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC0B0B0F)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (live && logger.running) Box(Modifier.size(8.dp).clip(CircleShape).background(C.Hot))
                    BasicText(
                        if (live && logger.running) "LOGGING · 1 FRAME / ${TimelapseLogger.INTERVAL_MS / 1000} S" else "SAVED · ${samples.size} FRAMES",
                        style = text(12.sp, FontWeight.Bold, spacing = 0.08.em),
                    )
                }
            }

            if (cur == null) {
                CenteredText(
                    if (live) "Waiting for the first frame from the camera…" else "Loading…",
                    text(14.sp, color = C.Secondary), Modifier.fillMaxWidth().padding(top = 40.dp),
                )
            } else {
                // ===== reading =====
                val first = samples.first()
                BasicText(
                    "MAX TEMPERATURE · " + if (idx == samples.lastIndex && live) "NOW" else "AT " + elapsed(cur.time - first.time),
                    style = Label, modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                )
                Row(Modifier.padding(start = 20.dp, top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    BasicText(Units.t(cur.max), style = text(44.sp, FontWeight.SemiBold, spacing = (-0.02).em))
                    if (cur.max.isFinite()) BasicText(" " + Units.label, style = text(18.sp, FontWeight.Medium, C.Soft), modifier = Modifier.padding(bottom = 7.dp))
                    BasicText(
                        if (cur.max.isFinite() && first.max.isFinite()) {
                            val d = Units.delta(cur.max - first.max)
                            (if (d >= 0) "+" else "") + fmt1(d) + " " + Units.label + " since start"
                        } else "— since start",
                        style = text(14.sp, color = C.Secondary), modifier = Modifier.padding(start = 12.dp, bottom = 9.dp),
                    )
                }
                // ===== chart =====
                Chart(samples, idx, Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp).fillMaxWidth().height(260.dp)) { scrub = it }
                // ===== facts =====
                FlowRow(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fact("Started", HM.format(Date(first.time)))
                    Fact("Frames", "${samples.size}")
                    Fact("Source", "Whole frame")
                    if (live) app.camera.info.battery?.let { Fact("Camera battery", "~${maxOf(1, it * 60 / 100)} min left") }
                }
            }
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton("Export CSV", {
                    val name = app.captures.exportTimelapseCsv(samples, Units.label, Units::conv)
                    toast = if (name != null) "CSV exported" to "Download/Kelvin/$name" else "CSV export failed" to "Nothing logged yet"
                }, Modifier.weight(1f), height = 56.dp)
                if (live) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(if (saving) "Saving…" else if (logger.running) "Stop & save" else "Stopped", {
                            if (!logger.running || saving) return@PrimaryButton
                            saving = true
                            app.stopTimelapse { c ->
                                saving = false
                                if (c != null) ui.replace(Screen.Timelapse(c.id)) else ui.back()
                            }
                        })
                    }
                } else OutlineButton("Delete", { confirmDelete = true }, Modifier.weight(1f), height = 56.dp)
            }
        }
        toast?.let { t -> ActionToast(t, Modifier.align(Alignment.BottomCenter)) { toast = null } }
        saved?.first?.let { c -> DeleteDialogs(c, app.captures, ui, app::refreshCaptures, "The log and its ${c.frames} frames will be removed from this phone.", confirmDelete) { confirmDelete = false } }
    }
}

@Composable
private fun Fact(k: String, v: String) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(k, style = text(12.sp, color = C.Secondary))
        BasicText(v, style = text(12.sp, FontWeight.Medium))
    }
}

/** Max temperature per sample; drag to read any sample. */
@Composable
private fun Chart(samples: List<TimelapseSample>, idx: Int, modifier: Modifier, onScrub: (Int?) -> Unit) {
    val tm = rememberTextMeasurer()
    val n = samples.size
    val scrubCb by rememberUpdatedState(onScrub)
    val count by rememberUpdatedState(n)
    // display-unit values; NaN marks a sample without any valid pixel: never plotted, never connected across
    val values = samples.map { Units.conv(it.max).takeIf { v -> v.isFinite() } ?: Float.NaN }
    var vMin = Float.POSITIVE_INFINITY; var vMax = Float.NEGATIVE_INFINITY
    for (v in values) if (v.isFinite()) { if (v < vMin) vMin = v; if (v > vMax) vMax = v }
    val hasValues = vMin <= vMax
    // a padded range snapped to the grid step
    val step = if (hasValues && vMax - vMin > 30) 10f else 5f
    val lo = if (hasValues) floor((vMin - 1) / step) * step else 0f
    val hi = if (hasValues) maxOf(ceil((vMax + 1) / step) * step, lo + step) else step
    val t0 = samples.first().time
    val spanMin = (samples.last().time - t0) / 60_000f
    val tickMin = listOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 240f).firstOrNull { spanMin / it <= 4 } ?: 480f

    Box(modifier.clip(RoundedCornerShape(20.dp)).background(C.Surface)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val left = 44.dp.toPx(); val right = size.width - 16.dp.toPx()
                fun pick(x: Float) = scrubCb((((x - left) / (right - left)).coerceIn(0f, 1f) * (count - 1)).roundToInt())
                awaitEachGesture {
                    pick(awaitFirstDown().position.x)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) { pick(it.position.x); it.consume() } }
                    } while (ev.changes.any { it.pressed })
                }
            },
        ) {
            val left = 44.dp.toPx(); val right = size.width - 16.dp.toPx()
            val top = 16.dp.toPx(); val bottom = size.height - 30.dp.toPx()
            fun x(i: Int) = if (n <= 1) right else left + (right - left) * i / (n - 1)
            fun xt(ms: Long) = if (spanMin <= 0f) right else left + (right - left) * (ms / 60_000f) / spanMin
            fun y(v: Float) = bottom - (bottom - top) * (v - lo) / (hi - lo)
            val label = text(11.sp, color = C.Muted)
            // grid
            if (hasValues) {
                var g = lo
                while (g <= hi + 0.01f) {
                    drawLine(Color(0x0FFFFFFF), Offset(left, y(g)), Offset(right, y(g)), 1.dp.toPx())
                    val l = tm.measure("${g.roundToInt()}°", label)
                    drawText(l, topLeft = Offset(left - 8.dp.toPx() - l.size.width, y(g) - l.size.height / 2f))
                    g += step
                }
            }
            var t = 0f
            while (t <= spanMin + 0.001f) {
                val l = tm.measure("${t.roundToInt()} min", label)
                drawText(l, topLeft = Offset((xt((t * 60_000).toLong()) - l.size.width / 2f).coerceIn(left - 10.dp.toPx(), right - l.size.width), bottom + 8.dp.toPx()))
                t += tickMin
            }
            if (hasValues) {
                // area + line, one run per stretch of valid samples (no joining across gaps)
                val line = Path(); val area = Path()
                var i = 0
                while (i < n) {
                    if (!values[i].isFinite()) { i++; continue }
                    var j = i
                    while (j + 1 < n && values[j + 1].isFinite()) j++
                    if (j == i) drawCircle(C.Accent, 2.5.dp.toPx(), Offset(x(i), y(values[i])))
                    else {
                        line.moveTo(x(i), y(values[i]))
                        area.moveTo(x(i), bottom); area.lineTo(x(i), y(values[i]))
                        for (k in i + 1..j) { line.lineTo(x(k), y(values[k])); area.lineTo(x(k), y(values[k])) }
                        area.lineTo(x(j), bottom); area.close()
                    }
                    i = j + 1
                }
                drawPath(area, Color(0x244FD1E8))
                drawPath(line, C.Accent, style = Stroke(2.dp.toPx()))
            } else {
                val msg = tm.measure("No valid temperatures", text(13.sp, FontWeight.SemiBold, C.Secondary))
                drawText(msg, topLeft = Offset((left + right - msg.size.width) / 2f, (top + bottom - msg.size.height) / 2f))
            }
            // cursor and tooltip (the cursor and time stay available at samples without a valid reading)
            val cx = x(idx)
            val cv = values[idx]
            val cy = if (cv.isFinite()) y(cv) else null
            drawLine(Color(0x664FD1E8), Offset(cx, top), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            if (cy != null) drawCircle(C.Accent, 5.dp.toPx(), Offset(cx, cy))
            val l1 = tm.measure("${CLOCK.format(Date(samples[idx].time))} · ${elapsed(samples[idx].time - t0)}", text(12.sp, color = C.Secondary))
            val l2 = tm.measure(if (cv.isFinite()) "${Units.t(samples[idx].max)} ${Units.label}" else "—", text(14.sp, FontWeight.SemiBold))
            val tw = maxOf(l1.size.width, l2.size.width) + 20.dp.toPx()
            val th = l1.size.height + l2.size.height + 14.dp.toPx()
            val tx = (cx - tw / 2).coerceIn(8.dp.toPx(), size.width - tw - 8.dp.toPx())
            val ty = if (cy != null) (cy - th - 14.dp.toPx()).coerceAtLeast(4.dp.toPx()) else top
            drawRoundRect(C.Surface3, Offset(tx, ty), Size(tw, th), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
            drawText(l1, topLeft = Offset(tx + 10.dp.toPx(), ty + 7.dp.toPx()))
            drawText(l2, topLeft = Offset(tx + 10.dp.toPx(), ty + 7.dp.toPx() + l1.size.height))
        }
    }
}

/** Elapsed time as m:ss (or h:mm:ss). */
private fun elapsed(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.US, s / 60, s % 60)
}
