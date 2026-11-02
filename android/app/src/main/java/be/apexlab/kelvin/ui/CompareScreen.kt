package be.apexlab.kelvin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import be.apexlab.kelvin.Capture
import be.apexlab.kelvin.CaptureData
import be.apexlab.kelvin.Captures
import be.apexlab.kelvin.PH
import be.apexlab.kelvin.PW
import be.apexlab.kelvin.UiState
import be.apexlab.kelvin.Units
import be.apexlab.kelvin.fmt1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Side(val c: Capture, val d: CaptureData)

/** Compare: before/after split view on one locked range, with max/avg/min changes. */
@Composable
fun CompareScreen(beforeId: String, afterId: String, ui: UiState, captures: Captures) {
    var sides by remember { mutableStateOf<Pair<Side, Side>?>(null) }
    LaunchedEffect(beforeId, afterId) {
        sides = withContext(Dispatchers.IO) {
            val b = captures.get(beforeId) ?: return@withContext null
            val a = captures.get(afterId) ?: return@withContext null
            Side(b, captures.data(b)) to Side(a, captures.data(a))
        }
    }
    Column(Modifier.fillMaxSize().background(C.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
            BasicText("Compare", style = text(17.sp, FontWeight.SemiBold))
        }
        val pair = sides ?: return@Column
        val (before, after) = pair
        val lo = minOf(before.d.min, after.d.min)
        val hi = maxOf(before.d.max, after.d.max)
        val palette = after.c.palette
        val imgB = remember(pair) { captures.render(before.d.temps, palette, lo, hi).asImageBitmap() }
        val imgA = remember(pair) { captures.render(after.d.temps, palette, lo, hi).asImageBitmap() }
        SplitView(imgB, imgA, "BEFORE · ${TIME.format(Date(before.c.time))}", "AFTER · ${TIME.format(Date(after.c.time))}")

        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(Units.t(lo), style = text(12.sp, FontWeight.SemiBold))
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(palette.colors.map { Color(it) })))
            BasicText(Units.tu(hi), style = text(12.sp, FontWeight.SemiBold))
        }
        CenteredText("SAME LOCKED RANGE ON BOTH SIDES", text(11.sp, color = C.Muted, spacing = 0.06.em), Modifier.fillMaxWidth().padding(top = 6.dp))

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
            TableRow("", "BEFORE", "AFTER", "CHANGE", header = true)
            val rows = listOf(
                Triple("Max", before.d.max, after.d.max),
                Triple("Average", before.d.avg, after.d.avg),
                Triple("Min", before.d.min, after.d.min),
            )
            rows.forEachIndexed { i, (k, b, a) ->
                val change = a - b
                TableRow(k, Units.t(b), Units.t(a), (if (change > 0) "+" else "") + fmt1(Units.delta(change)), changeColor = if (change > 0.05f) Color(0xFFFF8A80) else C.Secondary, divider = i < rows.size - 1)
            }
        }
    }
}

@Composable
private fun SplitView(before: ImageBitmap, after: ImageBitmap, labelB: String, labelA: String) {
    var split by remember { mutableFloatStateOf(0.5f) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(PW.toFloat() / PH).background(Color.Black).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                split = (down.position.x / size.width).coerceIn(0f, 1f)
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { if (it.pressed) { split = (it.position.x / size.width).coerceIn(0f, 1f); it.consume() } }
                } while (ev.changes.any { it.pressed })
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dst = IntSize(size.width.toInt(), size.height.toInt())
            drawImage(after, IntOffset.Zero, IntSize(PW, PH), IntOffset.Zero, dst, filterQuality = FilterQuality.Low)
            clipRect(right = size.width * split) {
                drawImage(before, IntOffset.Zero, IntSize(PW, PH), IntOffset.Zero, dst, filterQuality = FilterQuality.Low)
            }
            val x = size.width * split
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 4.dp.toPx())
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            val c = Offset(x, size.height / 2)
            drawCircle(Color.Black.copy(alpha = 0.35f), 23.dp.toPx(), c + Offset(0f, 3.dp.toPx()))
            drawCircle(Color.White, 22.dp.toPx(), c)
            val a = 5.dp.toPx()
            for (dir in listOf(-1f, 1f)) {
                val tip = c + Offset(dir * 9.dp.toPx(), 0f)
                drawLine(C.Background, tip, tip + Offset(-dir * a, -a), 2.dp.toPx())
                drawLine(C.Background, tip, tip + Offset(-dir * a, a), 2.dp.toPx())
            }
        }
        for ((label, align) in listOf(labelB to Alignment.TopStart, labelA to Alignment.TopEnd)) {
            BasicText(
                label, style = text(12.sp, FontWeight.SemiBold),
                modifier = Modifier.align(align).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB80B0B0F)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun TableRow(k: String, b: String, a: String, change: String, header: Boolean = false, changeColor: Color = C.Text, divider: Boolean = true) {
    val style = if (header) text(11.sp, FontWeight.SemiBold, C.Muted, 0.12.em) else text(15.sp)
    Row(Modifier.fillMaxWidth().padding(vertical = if (header) 8.dp else 10.dp)) {
        BasicText(k, style = style.copy(color = if (header) C.Muted else C.Soft), modifier = Modifier.weight(1.2f))
        BasicText(b, style = style.copy(textAlign = TextAlign.End), modifier = Modifier.weight(1f))
        BasicText(a, style = style.copy(textAlign = TextAlign.End), modifier = Modifier.weight(1f))
        BasicText(change, style = style.copy(textAlign = TextAlign.End, color = if (header) C.Muted else changeColor, fontWeight = if (header) FontWeight.SemiBold else FontWeight.SemiBold), modifier = Modifier.weight(1f))
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
}

private val TIME = SimpleDateFormat("HH:mm", Locale.US)
