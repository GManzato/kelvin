package com.gilles.flirone.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gilles.flirone.Capture
import com.gilles.flirone.CaptureData
import com.gilles.flirone.Captures
import com.gilles.flirone.PH
import com.gilles.flirone.PW
import com.gilles.flirone.Palette
import com.gilles.flirone.Pt
import com.gilles.flirone.Screen
import com.gilles.flirone.UiState
import com.gilles.flirone.CaptureKind
import com.gilles.flirone.Units
import kotlinx.coroutines.Dispatchers
import com.gilles.flirone.range
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

private const val MAX_SPOTS = 3

/** 4 · Capture viewer: re-measure and re-colour a saved radiometric photo. */
@Composable
fun ViewerScreen(id: String, ui: UiState, captures: Captures, onChanged: () -> Unit) {
    var capture by remember(id) { mutableStateOf<Capture?>(null) }
    var data by remember(id) { mutableStateOf<CaptureData?>(null) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val c = captures.get(id)
            capture = c
            data = c?.takeIf { it.kind == CaptureKind.Photo }?.let(captures::data)
        }
    }
    val c = capture
    val d = data
    if (c != null && c.kind == CaptureKind.Video) return VideoViewer(c, ui, captures, onChanged)
    if (c == null || d == null) {
        Box(Modifier.fillMaxSize().background(C.Background))
        return
    }
    ViewerContent(c, d, ui, captures, onChanged)
}

@Composable
private fun ViewerContent(c: Capture, d: CaptureData, ui: UiState, captures: Captures, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    // edits survive leaving for Compare and coming back
    var palette by remember(c.id) { mutableStateOf(ui.viewerEdits[c.id]?.first ?: c.palette) }
    var spots by remember(c.id) { mutableStateOf(ui.viewerEdits[c.id]?.second ?: listOfNotNull(c.spot)) }
    SideEffect { ui.viewerEdits[c.id] = palette to spots }
    val (lo, hi) = remember(c.id) { c.range(d) }
    var toast by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val image = remember(c.id, palette) { captures.render(d.temps, palette, lo, hi).asImageBitmap() }
    val tm = rememberTextMeasurer()
    val units: (Float) -> String = Units::tu

    // the compare partner: the previous capture in time, or the next one for the oldest
    val partner = remember(c.id) {
        captures.list().filter { it.kind == CaptureKind.Photo }.let { all -> all.getOrNull(all.indexOfFirst { it.id == c.id } + 1) ?: all.firstOrNull { it.id != c.id } }
    }

    Box(Modifier.fillMaxSize().background(C.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // ===== image with spots =====
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(PW.toFloat() / PH)) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(c.id) {
                        detectTapGestures { o ->
                            val p = Pt((o.x / size.width * PW).coerceIn(0f, PW - 0.01f), (o.y / size.height * PH).coerceIn(0f, PH - 0.01f))
                            spots = if (spots.size < MAX_SPOTS) spots + p else {
                                // all spots used: move the nearest one
                                val near = spots.indices.minBy { hypot(spots[it].x - p.x, spots[it].y - p.y) }
                                spots.toMutableList().also { it[near] = p }
                            }
                        }
                    },
                ) {
                    drawImage(image, IntOffset.Zero, IntSize(PW, PH), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
                    val sx = size.width / PW
                    val sy = size.height / PH
                    val hot = d.hottest
                    val hp = Offset((hot % PW + 0.5f) * sx, (hot / PW + 0.5f) * sy)
                    drawCircle(Color.White.copy(alpha = 0.9f), 6.dp.toPx(), hp, style = Stroke(1.dp.toPx()))
                    drawCircle(C.Hot, 4.5.dp.toPx(), hp, style = Stroke(2.dp.toPx()))
                    val hl = tm.measure(d.reading(hot).text, text(13.sp, FontWeight.SemiBold, shadow = ReadingShadow))
                    // label to the left of the marker when it would run off the right edge
                    val hx = if (hp.x + 10.dp.toPx() + hl.size.width > size.width) hp.x - 10.dp.toPx() - hl.size.width else hp.x + 10.dp.toPx()
                    drawText(hl, topLeft = Offset(hx, hp.y - hl.size.height / 2f))
                    spots.forEachIndexed { n, p ->
                        val o = Offset(p.x * sx, p.y * sy)
                        val half = 20.dp.toPx(); val gap = 6.dp.toPx()
                        for (shadow in listOf(true, false)) {
                            val col = if (shadow) Color.Black.copy(alpha = 0.6f) else Color.White
                            val w = if (shadow) 3f else 1.5.dp.toPx()
                            drawLine(col, Offset(o.x - half, o.y), Offset(o.x - gap, o.y), w); drawLine(col, Offset(o.x + gap, o.y), Offset(o.x + half, o.y), w)
                            drawLine(col, Offset(o.x, o.y - half), Offset(o.x, o.y - gap), w); drawLine(col, Offset(o.x, o.y + gap), Offset(o.x, o.y + half), w)
                        }
                        val r = d.reading(p)
                        val label = tm.measure(buildAnnotatedString {
                            withStyle(text(22.sp, FontWeight.SemiBold, shadow = ReadingShadow).toSpanStyle()) { append(r.text) }
                            withStyle(text(13.sp, shadow = ReadingShadow).toSpanStyle()) { append(" " + Units.label) }
                        })
                        val badgeR = 9.dp.toPx()
                        val lx = o.x + 14.dp.toPx()
                        val ly = o.y - half - label.size.height / 2f
                        drawCircle(Color.White, badgeR, Offset(lx, ly + label.size.height / 2f))
                        val num = tm.measure("${n + 1}", text(11.sp, FontWeight.Bold, C.Background))
                        drawText(num, topLeft = Offset(lx - num.size.width / 2f, ly + label.size.height / 2f - num.size.height / 2f))
                        drawText(label, topLeft = Offset(lx + badgeR + 4.dp.toPx(), ly))
                    }
                }
                // hint
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xB30B0B0F)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    BasicText("Tap the image to add a spot · ${spots.size} of $MAX_SPOTS", style = text(12.sp, color = C.Text2))
                }
                // translucent app bar
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color(0xD10B0B0F), Color.Transparent))))
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        BasicText("Thermal photo", style = text(17.sp, FontWeight.SemiBold, shadow = ReadingShadow))
                        BasicText(DATE.format(Date(c.time)), style = text(12.sp, color = C.Text2, shadow = ReadingShadow))
                    }
                    if (spots.isNotEmpty()) Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(C.Glass).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                            .tap { spots = emptyList() }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { BasicText("Clear spots", style = text(13.sp, FontWeight.SemiBold)) }
                }
            }

            // ===== scale =====
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(Units.t(lo), style = text(13.sp, FontWeight.SemiBold, Color(0xFF8EC5FF)))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(palette.colors.map { Color(it) })))
                BasicText(Units.tu(hi), style = text(13.sp, FontWeight.SemiBold, Color(0xFFFF8A80)))
            }
            // ===== palette after the fact =====
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (p in Palette.entries) {
                    val sel = p == palette
                    Box(
                        Modifier.size(44.dp).then(if (sel) Modifier.border(2.dp, C.Accent, CircleShape).padding(4.dp) else Modifier)
                            .clip(CircleShape).background(Brush.sweepGradient(p.colors.map { Color(it) } + Color(p.colors.first()))).tap { palette = p },
                    )
                }
            }
            CenteredText(
                palette.label.uppercase() + if (palette == c.palette) " · AS CAPTURED" else " · CHANGED AFTER CAPTURE",
                text(11.sp, FontWeight.SemiBold, C.Muted, 0.14.em), Modifier.fillMaxWidth(),
            )
            // ===== stats =====
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("MIN", d.reading(d.temps.indices.minBy { d.temps[it] }).text, Color(0xFF8EC5FF), Modifier.weight(1f))
                Stat("AVG", Units.t(d.avg), C.Secondary, Modifier.weight(1f))
                Stat("MAX", d.reading(d.hottest).text, Color(0xFFFF8A80), Modifier.weight(1f))
            }
            // ===== details =====
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Detail("Emissivity", "%.2f · %s".format(Locale.US, c.emissivity, c.emissivityLabel), Modifier.weight(1f))
                    Detail("Reflected", Units.tu(c.reflectedC), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Detail("Sensor", "80 × 60", Modifier.weight(1f))
                    Detail("Data", "Radiometric", Modifier.weight(1f))
                }
            }
            // ===== actions =====
            Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Action(Ic.Share, "Share image", C.Text, Modifier.weight(1f)) {
                    val f = captures.shareImage(c, d, palette, spots, units)
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share thermal image"))
                }
                Action(Ic.Table, "Export CSV", C.Text, Modifier.weight(1f)) {
                    val name = captures.exportCsv(c, d, Units.label, Units::conv)
                    toast = if (name != null) "CSV exported" to "Download/Kelvin/$name · 80×60 · ${Units.label}" else "CSV export failed" to "Storage not available"
                }
                Action(Ic.Split, "Compare", if (partner != null) C.Text else C.Muted, Modifier.weight(1f)) {
                    partner?.let { p -> ui.open(if (p.time < c.time) Screen.Compare(p.id, c.id) else Screen.Compare(c.id, p.id)) }
                }
                Action(Ic.Trash, "Delete", C.Hot, Modifier.weight(1f)) { confirmDelete = true }
            }
        }

        toast?.let { t -> ActionToast(t, Modifier.align(Alignment.BottomCenter)) { toast = null } }

        DeleteDialogs(c, captures, ui, onChanged, "The image and its 4,800 temperature readings will be removed from this phone.", confirmDelete) { confirmDelete = false }
    }
}

/** Delete confirmation, then "deleted" with Undo (the capture sits in the trash until the next start). */
@Composable
fun DeleteDialogs(c: Capture, captures: Captures, ui: UiState, onChanged: () -> Unit, what: String, confirm: Boolean, onDismissConfirm: () -> Unit) {
    var deleted by remember(c.id) { mutableStateOf(false) }
    if (confirm && !deleted) Dialog(onDismiss = onDismissConfirm) {
        BasicText("Delete this capture?", style = text(18.sp, FontWeight.SemiBold))
        BasicText(what, style = text(14.sp, color = C.Body, lineHeight = 20.sp), modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OutlineButton("Cancel", onDismissConfirm, height = 44.dp)
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Hot).tap {
                if (captures.delete(c)) { deleted = true; onChanged() }
                onDismissConfirm()
            }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { BasicText("Delete", style = text(15.sp, FontWeight.SemiBold, Color.White)) }
        }
    }
    if (deleted) Dialog(onDismiss = { ui.back() }) {
        BasicText("Capture deleted", style = text(18.sp, FontWeight.SemiBold))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OutlineButton("Undo", {
                if (captures.undoDelete(c.id) != null) { deleted = false; onChanged() }
            }, height = 44.dp)
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Accent).tap { ui.back() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                BasicText("Back to gallery", style = text(15.sp, FontWeight.SemiBold, C.OnAccent))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText(label, style = text(11.sp, FontWeight.SemiBold, color, 0.1.em))
        BasicText(buildAnnotatedString {
            withStyle(text(22.sp, FontWeight.SemiBold).toSpanStyle()) { append(value) }
            withStyle(text(13.sp, color = C.Soft).toSpanStyle()) { append(" " + Units.label) }
        }, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun Detail(k: String, v: String, modifier: Modifier) {
    Row(modifier) {
        BasicText(k, style = text(13.sp, color = C.Secondary), modifier = Modifier.weight(1f))
        BasicText(v, style = text(13.sp, FontWeight.Medium))
    }
}

@Composable
internal fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(64.dp).clip(RoundedCornerShape(12.dp)).tap(onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Icon(icon, color, 22.dp)
        BasicText(label, style = text(12.sp, FontWeight.Medium, color))
    }
}

/** Result of an action (export, share) with a detail line; hides itself. */
@Composable
internal fun ActionToast(t: Pair<String, String>, modifier: Modifier, onDone: () -> Unit) {
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp).fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(C.Surface3).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Ic.Check, C.Accent, 18.dp)
        Column {
            BasicText(t.first, style = text(13.sp, FontWeight.SemiBold))
            BasicText(t.second, style = mono(11.sp, C.Body))
        }
    }
    LaunchedEffect(t) { delay(2400); onDone() }
}

/** Centered modal card over a scrim. */
@Composable
fun Dialog(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).tap(onDismiss), contentAlignment = Alignment.Center) {
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(24.dp)).background(C.Surface2).tap { }.padding(22.dp), content = content)
    }
}

internal val DATE = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.ENGLISH)
