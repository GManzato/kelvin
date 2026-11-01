package com.gilles.flirone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilles.flirone.Capture
import com.gilles.flirone.Captures
import com.gilles.flirone.Screen
import com.gilles.flirone.UiState
import com.gilles.flirone.Units
import com.gilles.flirone.CaptureKind
import com.gilles.flirone.durationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class TypeFilter(val label: String) { All("All"), Photos("Photos"), Videos("Videos"), Timelapse("Timelapse") }
private enum class DateFilter { Any, Today, Week, Month }

/** 5 · Gallery: captures grouped by day, type and date filters, max-temperature badges. */
@Composable
fun GalleryScreen(ui: UiState, captures: Captures) {
    var all by remember { mutableStateOf<List<Capture>?>(null) }
    LaunchedEffect(ui.captureCount) { all = withContext(Dispatchers.IO) { captures.list() } }
    var type by remember { mutableStateOf(TypeFilter.All) }
    var date by remember { mutableStateOf(DateFilter.Any) }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }

    val now = Calendar.getInstance()
    fun startOf(field: Int): Long = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (field) {
            Calendar.WEEK_OF_YEAR -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            Calendar.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
        }
    }.timeInMillis
    val list = all.orEmpty()
    val shown = list.filter { c ->
        val typeOk = when (type) {
            TypeFilter.All -> true
            TypeFilter.Photos -> c.kind == CaptureKind.Photo
            TypeFilter.Videos -> c.kind == CaptureKind.Video
            TypeFilter.Timelapse -> c.kind == CaptureKind.Timelapse
        }
        val dateOk = when (date) {
            DateFilter.Any -> true
            DateFilter.Today -> c.time >= startOf(Calendar.DAY_OF_MONTH)
            DateFilter.Week -> c.time >= startOf(Calendar.WEEK_OF_YEAR)
            DateFilter.Month -> c.time >= startOf(Calendar.MONTH)
        }
        typeOk && dateOk
    }
    val groups = shown.groupBy { DAY_KEY.format(Date(it.time)) }.values.toList()

    Column(Modifier.fillMaxSize().background(C.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).tap { ui.back() }, contentAlignment = Alignment.Center) { Icon(Ic.Back, C.Text, 22.dp) }
            BasicText("Gallery", style = text(24.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).padding(start = 4.dp))
            BasicText("${shown.size} of ${list.size}", style = text(14.sp, color = C.Secondary))
            Box(Modifier.size(44.dp).tap { ui.open(Screen.Settings) }, contentAlignment = Alignment.Center) { Icon(Ic.Sliders, C.Text2, 22.dp) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in TypeFilter.entries) {
                val sel = t == type
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (sel) Color(0x294FD1E8) else Color.Transparent)
                        .border(1.dp, if (sel) Color(0x804FD1E8) else Color(0x24FFFFFF), RoundedCornerShape(18.dp))
                        .tap { type = t }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (sel) Icon(Ic.Check, C.Accent, 14.dp)
                    BasicText(t.label, style = text(14.sp, FontWeight.Medium, if (sel) C.Accent else C.Soft))
                }
            }
        }
        val month = SimpleDateFormat("MMMM", Locale.ENGLISH).format(Date())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((d, label) in listOf(DateFilter.Any to "Any date", DateFilter.Today to "Today", DateFilter.Week to "This week", DateFilter.Month to month)) {
                val sel = d == date
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (sel) C.Surface3 else C.Surface)
                        .tap { date = d }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText(label, style = text(13.sp, FontWeight.Medium, if (sel) C.Text else C.Secondary)) }
            }
        }
        if (all != null && groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                CenteredText(
                    if (list.isEmpty()) "No captures yet.\nPress the shutter on the live view; every photo keeps its 4,800 temperature readings."
                    else "Nothing here for this filter.\nCaptures keep their temperature data, so you can re-measure them later.",
                    text(14.sp, color = C.Secondary, lineHeight = 21.sp),
                )
            }
        } else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(groups, key = { it.first().id }) { g ->
                val first = Date(g.first().time)
                val label = dayLabel(first)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(label, style = text(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    BasicText("${g.size} item${if (g.size == 1) "" else "s"}", style = text(12.sp, color = C.Muted))
                }
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in g.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) Tile(c, thumbs, captures, Modifier.weight(1f)) {
                            ui.open(if (c.kind == CaptureKind.Timelapse) Screen.Timelapse(c.id) else Screen.Viewer(c.id))
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(c: Capture, thumbs: MutableMap<String, Bitmap>, captures: Captures, modifier: Modifier, onClick: () -> Unit) {
    LaunchedEffect(c.id) {
        if (c.id !in thumbs) withContext(Dispatchers.IO) { captures.thumb(c) }?.let { thumbs[c.id] = it }
    }
    Box(modifier.aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)).background(C.Surface).tap(onClick)) {
        thumbs[c.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low) }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xB80B0B0F)).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(C.Hot))
            BasicText("${Units.t(c.max)}°", style = text(11.sp, FontWeight.SemiBold))
        }
        if (c.kind != CaptureKind.Photo) Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xB80B0B0F)).padding(start = 5.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(if (c.kind == CaptureKind.Video) Ic.Play else Ic.Clock, Color.White, 12.dp)
            BasicText(
                if (c.kind == CaptureKind.Video) durationText(c.durationMs) else "${maxOf(1L, (c.durationMs + 30_000) / 60_000)} min",
                style = text(11.sp, FontWeight.SemiBold),
            )
        }
    }
}

private val DAY_KEY = SimpleDateFormat("yyyyMMdd", Locale.US)
private val DAY_LABEL = SimpleDateFormat("EEE d MMM", Locale.ENGLISH)

private fun dayLabel(d: Date): String {
    val today = DAY_KEY.format(Date())
    val yesterday = DAY_KEY.format(Date(System.currentTimeMillis() - 86_400_000L))
    val key = DAY_KEY.format(d)
    val prefix = when (key) { today -> "Today · "; yesterday -> "Yesterday · "; else -> "" }
    return prefix + DAY_LABEL.format(d)
}
