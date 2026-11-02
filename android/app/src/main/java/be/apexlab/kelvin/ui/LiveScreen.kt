package be.apexlab.kelvin.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import be.apexlab.kelvin.Analysis
import be.apexlab.kelvin.CameraController
import be.apexlab.kelvin.Captures
import be.apexlab.kelvin.Conn
import be.apexlab.kelvin.IsoMode
import be.apexlab.kelvin.PH
import be.apexlab.kelvin.PW
import be.apexlab.kelvin.Pt
import be.apexlab.kelvin.Reading
import be.apexlab.kelvin.SheetKind
import be.apexlab.kelvin.Screen
import be.apexlab.kelvin.Thermal
import be.apexlab.kelvin.Tool
import be.apexlab.kelvin.UiState
import be.apexlab.kelvin.ViewMode
import be.apexlab.kelvin.Units
import be.apexlab.kelvin.CaptureMode
import be.apexlab.kelvin.ThermalApp
import be.apexlab.kelvin.TimelapseLogger
import be.apexlab.kelvin.durationText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import be.apexlab.kelvin.fmt1
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Maps portrait sensor pixels to the image area (centre-crop, like the design's object-fit: cover).
 * [rot] turns the portrait image clockwise for landscape (the camera turns with the phone).
 */
private class Mapping(val w: Float, val h: Float, val rot: Int = 0) {
    private val iw = if (rot % 180 == 0) PW else PH
    private val ih = if (rot % 180 == 0) PH else PW
    val scale = max(w / iw, h / ih)
    val ox = (w - iw * scale) / 2
    val oy = (h - ih * scale) / 2
    fun toScreen(p: Pt): Offset {
        val (x, y) = when (rot) { 90 -> (PH - p.y) to p.x; 270 -> p.y to (PW - p.x); else -> p.x to p.y }
        return Offset(ox + x * scale, oy + y * scale)
    }
    /** Sensor position for a screen point, not clamped to the image. */
    fun toSensorRaw(o: Offset): Pt {
        val x = (o.x - ox) / scale
        val y = (o.y - oy) / scale
        val (px, py) = when (rot) { 90 -> y to (PH - x); 270 -> (PW - y) to x; else -> x to y }
        return Pt(px, py)
    }
    fun toSensor(o: Offset) = toSensorRaw(o).let { Pt(it.x.coerceIn(0f, PW - 0.01f), it.y.coerceIn(0f, PH - 0.01f)) }
}

/** Clockwise turn for the portrait sensor image: the display rotation, followed live (incl. 180° flips). */
@Composable
private fun rememberImageRotation(): Int {
    val ctx = LocalContext.current
    val dm = remember { ctx.getSystemService(DisplayManager::class.java) }
    fun current() = when (ctx.display?.rotation) { Surface.ROTATION_90 -> 270; Surface.ROTATION_270 -> 90; else -> 0 }
    var rot by remember { mutableIntStateOf(current()) }
    LocalConfiguration.current // re-read on configuration changes too
    rot = current()
    DisposableEffect(dm) {
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) { rot = current() }
        }
        dm.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(l) }
    }
    return rot
}

@Composable
fun LiveScreen(app: ThermalApp) {
    val cam = app.camera
    val ui = app.ui
    val captures = app.captures
    val frame = cam.frame
    val s = ui.settings
    val conn = cam.conn
    val streaming = conn == Conn.Live && !cam.paused
    val recon = conn as? Conn.Reconnecting
    val frozenSec = recon?.let { ((cam.now - it.since) / 1000).toInt() } ?: 0
    // part of the sensor image that is on screen (centre crop, below the top bar); set by the image area
    var region by remember { mutableStateOf(listOf(0, 0, PW, PH)) }
    val analysis = remember(frame, s, ui.sheet, region) {
        frame?.let { Thermal.analyze(it.raw, s, isoPreview = ui.sheet == SheetKind.Alarms, visible = region.toIntArray()) }
    }
    val thermalImage = remember(analysis) {
        analysis?.let { Bitmap.createBitmap(it.pixels, PW, PH, Bitmap.Config.ARGB_8888).asImageBitmap() }
    }
    val visibleImage = remember(frame) { frame?.visible?.asImageBitmap() }
    val edgesImage = remember(frame) { frame?.edges?.asImageBitmap() }
    val isoShown = ui.sheet == SheetKind.Alarms || s.alarmOn
    // the isotherm preview always draws on the thermal image, whatever the view mode
    val view = if (isoShown) ViewMode.Thermal else s.view
    cam.wantLargeVisible = view == ViewMode.Visible || view == ViewMode.Blend
    cam.wantEdges = view == ViewMode.Edges

    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    val ctx = LocalContext.current

    AlarmEffects(ctx, ui, analysis, sheetOpen = ui.sheet != SheetKind.None, active = streaming)
    // a recording belongs to the live view: leaving it (gallery, camera lost) saves the video
    DisposableEffect(Unit) { onDispose { app.stopRecording() } }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rot = if (landscape) rememberImageRotation() else 0

    val enabled = (streaming && analysis != null) || app.recorder.recording || app.timelapse.running
    val onMode = { m: CaptureMode ->
        if (m != CaptureMode.Video) app.stopRecording()
        ui.mode = m
    }
    val onShutter: () -> Unit = shoot@{
        when (ui.mode) {
            CaptureMode.Video -> {
                if (app.recorder.recording) app.stopRecording()
                else if (!app.recorder.start(s, cam.info)) ui.toast = "Video recording not available"
                return@shoot
            }
            CaptureMode.Timelapse -> {
                if (app.timelapse.running) app.stopTimelapse { c -> c?.let { ui.open(Screen.Timelapse(it.id)) } }
                else { app.timelapse.start(); ui.toast = "Timelapse started · 1 frame / ${TimelapseLogger.INTERVAL_MS / 1000} s" }
                return@shoot
            }
            CaptureMode.Photo -> Unit
        }
        val a = analysis ?: return@shoot
        val f = frame ?: return@shoot
        scope.launch { flash.snapTo(0.6f); flash.animateTo(0f, tween(320)) }
        val settings = s
        val info = cam.info
        Thread {
            val small = Bitmap.createBitmap(a.pixels, PW, PH, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createScaledBitmap(small, PW * 8, PH * 8, true)
            val saved = captures.save(a, settings, big, f.jpeg, info)
            ctx.mainExecutor.execute {
                ui.lastCapture = saved
                ui.lastThumb = big
                ui.captureCount = captures.count()
                ui.toast = "Photo saved"
            }
        }.start()
    }
    val onGallery = { ui.open(Screen.Gallery) }

    Box(Modifier.fillMaxSize().background(C.Background)) {
        val imageArea = @Composable { mod: Modifier ->
            BoxWithConstraints(mod.background(Color.Black)) {
                val density = LocalDensity.current
                val map = with(density) { Mapping(maxWidth.toPx(), maxHeight.toPx(), rot) }
                val topPx = with(density) { WindowInsets.statusBars.getTop(this) + (if (landscape) 56.dp else 100.dp).toPx() }
                // only pixels that are fully on screen, so markers never sit half off the edge
                val corners = listOf(Offset(0f, topPx), Offset(map.w, topPx), Offset(0f, map.h), Offset(map.w, map.h)).map(map::toSensorRaw)
                val visibleRegion = listOf(
                    kotlin.math.ceil(corners.minOf { it.x }).toInt().coerceIn(0, PW),
                    kotlin.math.ceil(corners.minOf { it.y }).toInt().coerceIn(0, PH),
                    corners.maxOf { it.x }.toInt().coerceIn(0, PW),
                    corners.maxOf { it.y }.toInt().coerceIn(0, PH),
                )
                LaunchedEffect(visibleRegion) { region = visibleRegion }
                val dim = when {
                    recon != null -> dimFilter(0.55f, 0.72f)
                    cam.paused -> dimFilter(0.5f, 0.45f)
                    else -> null
                }
                val base = if (view == ViewMode.Visible || view == ViewMode.Blend) visibleImage else thermalImage
                Canvas(Modifier.fillMaxSize()) {
                    if (base != null) drawImageCover(base, dim, rot = rot)
                    if (view == ViewMode.Blend && thermalImage != null) drawImageCover(thermalImage, dim, alpha = s.blend, rot = rot)
                    if (view == ViewMode.Edges && edgesImage != null) drawImageCover(edgesImage, dim, alpha = 0.85f, rot = rot)
                    drawRect(Color.White.copy(alpha = flash.value))
                }
                if (analysis != null) {
                    MeasurementOverlay(analysis, s, map, overlayAlpha = if (recon != null) 0.45f else 1f) { ui.update { st -> it(st) } }
                }

                // top gradient
                Box(
                    Modifier.fillMaxWidth().height(110.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xC70B0B0F), Color(0x730B0B0F), Color.Transparent))),
                )

                // quick emissivity / info chip
                if (analysis != null) InfoChip(
                    text = "${s.tool.name.uppercase()} · ε ${"%.2f".format(java.util.Locale.US, s.emissivity)} · ${"%.1f".format(java.util.Locale.US, if (streaming) cam.fps else 0.0)} FPS",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
                    alpha = if (recon != null) 0.45f else 1f,
                ) { ui.sheet = SheetKind.Emissivity }

                if (analysis != null) ScaleBar(
                    analysis, s,
                    if (landscape) Modifier.align(Alignment.CenterEnd).padding(top = 40.dp, end = 4.dp) else Modifier.align(Alignment.TopEnd).padding(top = 222.dp, end = 4.dp),
                    barH = if (landscape) 180.dp else 300.dp,
                    onToggleLock = {
                        ui.update { st ->
                            if (st.locked) st.copy(locked = false)
                            else st.copy(
                                locked = true,
                                lockLo = (Math.floor(analysis.autoMin * 2.0) / 2).toFloat().coerceIn(Thermal.RANGE_LO, Thermal.RANGE_HI - 1),
                                lockHi = (Math.ceil(analysis.autoMax * 2.0) / 2).toFloat().coerceIn(Thermal.RANGE_LO + 1, Thermal.RANGE_HI),
                            )
                        }
                    },
                    onRange = { lo, hi -> ui.update { it.copy(lockLo = lo, lockHi = hi) } },
                )

                // picture-in-picture (top left in landscape, clear of the scale bar)
                val pipImage = if (base === visibleImage) thermalImage else visibleImage
                Box(
                    if (landscape) Modifier.align(Alignment.TopStart).statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start)).padding(top = 56.dp, start = 12.dp)
                    else Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 12.dp),
                ) {
                    if (s.pip && pipImage != null) Pip(pipImage, if (base === visibleImage) "IR" else "VIS", dim, rot) { ui.update { it.copy(pip = false) } }
                    else if (frame != null) RoundButton(Ic.Pip, { ui.update { it.copy(pip = true) } })
                }

                if (cam.paused) PausedOverlay { cam.resume() }

                // top bar + status stack
                Column(Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start)).padding(horizontal = 12.dp)) {
                    TopBar(cam, ui)
                    StatusStack(cam, ui, analysis, frozenSec, app)
                }

                ui.toast?.let { msg ->
                    Toast(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
                    LaunchedEffect(msg) { delay(1800); ui.toast = null }
                }
            }
        }

        if (landscape) Row(Modifier.fillMaxSize()) {
            imageArea(Modifier.weight(1f).fillMaxHeight())
            SideControls(ui, app.recorder.recording, app.timelapse.running, enabled, onMode, onShutter, onGallery)
        } else Column(Modifier.fillMaxSize()) {
            imageArea(Modifier.fillMaxWidth().weight(1f))
            BottomControls(ui, app.recorder.recording, app.timelapse.running, enabled, onMode, onShutter, onGallery)
        }

        when (ui.sheet) {
            SheetKind.Palette -> PaletteSheet(ui)
            SheetKind.Alarms -> AlarmSheet(ui, analysis)
            SheetKind.Emissivity -> EmissivitySheet(ui, analysis)
            SheetKind.None -> Unit
        }
    }
}

private fun dimFilter(saturation: Float, brightness: Float): ColorFilter {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    val b = ColorMatrix(floatArrayOf(brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    m.timesAssign(b)
    return ColorFilter.colorMatrix(m)
}

private fun DrawScope.drawImageCover(img: ImageBitmap, filter: ColorFilter?, alpha: Float = 1f, rot: Int = 0) {
    val swap = rot % 180 != 0
    val scale = max(size.width / (if (swap) img.height else img.width), size.height / (if (swap) img.width else img.height))
    val w = (img.width * scale).roundToInt()
    val h = (img.height * scale).roundToInt()
    rotate(rot.toFloat(), pivot = center) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Low, // bilinear: the 60×80 image upscales smoothly
            colorFilter = filter,
            alpha = alpha,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Measurement overlay: hot/cold markers, spot A (+B for delta), box
// ---------------------------------------------------------------------------------------------

private val markerText = text(13.sp, FontWeight.SemiBold, shadow = ReadingShadow)

@Composable
private fun MeasurementOverlay(a: Analysis, s: be.apexlab.kelvin.ViewSettings, map: Mapping, overlayAlpha: Float, update: ((be.apexlab.kelvin.ViewSettings) -> be.apexlab.kelvin.ViewSettings) -> Unit) {
    val tm = rememberTextMeasurer()
    val currentMap by rememberUpdatedState(map)
    val currentSettings by rememberUpdatedState(s)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val st = currentSettings
                // move whichever spot is nearer the finger (B only exists for the delta tool)
                val useB = st.tool == Tool.Delta &&
                    dist(currentMap.toScreen(st.spotB), down.position) < dist(currentMap.toScreen(st.spotA), down.position)
                fun move(o: Offset) {
                    val p = currentMap.toSensor(o)
                    update { if (useB) it.copy(spotB = p) else it.copy(spotA = p) }
                }
                move(down.position)
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { if (it.pressed) { move(it.position); it.consume() } }
                } while (ev.changes.any { it.pressed })
            }
        },
    ) {
        val alpha = overlayAlpha
        marker(tm, map.toScreen(a.hot), C.Hot, a.hotReading, alpha)
        marker(tm, map.toScreen(a.cold), C.Cold, a.coldReading, alpha)

        if (s.tool == Tool.Box) {
            val r = Analysis.boxRect(s.spotA)
            // corners in screen space (the image may be rotated in landscape)
            val c1 = map.toScreen(Pt(r[0].toFloat(), r[1].toFloat()))
            val c2 = map.toScreen(Pt(r[2].toFloat(), r[3].toFloat()))
            val tl = Offset(minOf(c1.x, c2.x), minOf(c1.y, c2.y))
            val br = Offset(maxOf(c1.x, c2.x), maxOf(c1.y, c2.y))
            drawRoundRect(Color.Black.copy(alpha = 0.35f * alpha), tl - Offset(1f, 1f), Size(br.x - tl.x + 2, br.y - tl.y + 2), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = alpha), tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            val (mn, mx, avg) = a.box(s.spotA)
            val label = buildAnnotatedString {
                fun part(k: String, color: Color, v: String) {
                    withStyle(text(10.sp, FontWeight.SemiBold, color, 0.08.em).toSpanStyle()) { append("$k ") }
                    withStyle(text(12.sp, FontWeight.SemiBold).toSpanStyle()) { append(v) }
                }
                part("MAX", Color(0xFFFF8A80), mx.text); append("   ")
                part("AVG", C.Secondary, (if (mx.sat != Reading.Sat.None || mn.sat != Reading.Sat.None) "≈ " else "") + avg.text); append("   ")
                part("MIN", Color(0xFF8EC5FF), mn.text)
            }
            val lay = tm.measure(label)
            val pad = 9.dp.toPx()
            val chipH = lay.size.height + 10.dp.toPx()
            val chipW = lay.size.width + 2 * pad
            // above the box, or below it when the top bar would cover it
            val above = tl.y - chipH - 6.dp.toPx()
            val chipTop = if (above >= 140.dp.toPx()) above else br.y + 6.dp.toPx()
            val chipLeft = tl.x.coerceIn(8.dp.toPx(), size.width - chipW - 8.dp.toPx())
            drawRoundRect(Color(0xB80B0B0F), Offset(chipLeft, chipTop), Size(chipW, chipH), CornerRadius(8.dp.toPx()), alpha = alpha)
            drawText(lay, topLeft = Offset(chipLeft + pad, chipTop + 5.dp.toPx()), alpha = alpha)
        }

        val pa = map.toScreen(s.spotA)
        if (s.tool == Tool.Delta) {
            val pb = map.toScreen(s.spotB)
            drawLine(Color.White.copy(alpha = alpha), pa, pb, 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
            crosshair(pb, 40.dp.toPx(), 11.dp.toPx(), alpha, dot = false)
            val rb = a.at(s.spotB)
            spotLabel(tm, pb, "B", rb, big = false, alpha)
            val ra = a.at(s.spotA)
            val d = if (ra.sat != Reading.Sat.None || rb.sat != Reading.Sat.None) "— (out of range)" else fmt1(Units.delta(ra.c - rb.c)) + " " + Units.label
            val lay = tm.measure("Δ $d", text(14.sp, FontWeight.SemiBold))
            val mid = Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
            val w = lay.size.width + 20.dp.toPx()
            val h = lay.size.height + 12.dp.toPx()
            drawRoundRect(Color(0xC70B0B0F), Offset(mid.x - w / 2, mid.y - h / 2), Size(w, h), CornerRadius(10.dp.toPx()), alpha = alpha)
            drawText(lay, topLeft = Offset(mid.x - lay.size.width / 2f, mid.y - lay.size.height / 2f), alpha = alpha)
        }
        crosshair(pa, 48.dp.toPx(), 12.dp.toPx(), alpha, dot = true)
        spotLabel(tm, pa, if (s.tool == Tool.Delta) "A" else null, a.at(s.spotA), big = true, alpha)
    }
}

private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)

private fun DrawScope.marker(tm: TextMeasurer, p: Offset, color: Color, r: Reading, alpha: Float) {
    drawCircle(Color.Black.copy(alpha = 0.5f * alpha), 7.dp.toPx(), p)
    drawCircle(Color.White.copy(alpha = 0.9f * alpha), 6.dp.toPx(), p, style = Stroke(1.dp.toPx()))
    drawCircle(color.copy(alpha = alpha), 4.5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
    val lay = tm.measure(r.text, markerText.copy(color = if (r.sat != Reading.Sat.None) C.Warning else C.Text))
    // left of the marker when the label would run into the scale bar at the right edge
    val right = p.x + 10.dp.toPx()
    val x = if (right + lay.size.width > size.width - 66.dp.toPx()) p.x - 10.dp.toPx() - lay.size.width else right
    drawText(lay, topLeft = Offset(x, p.y - lay.size.height / 2f), alpha = alpha)
}

private fun DrawScope.crosshair(p: Offset, size: Float, gap: Float, alpha: Float, dot: Boolean) {
    val half = size / 2
    val sw = 1.5.dp.toPx()
    for (shadow in listOf(true, false)) {
        val c = if (shadow) Color.Black.copy(alpha = 0.6f * alpha) else Color.White.copy(alpha = alpha)
        val w = if (shadow) sw + 1.5f else sw
        drawLine(c, Offset(p.x, p.y - half), Offset(p.x, p.y - gap / 2), w)
        drawLine(c, Offset(p.x, p.y + gap / 2), Offset(p.x, p.y + half), w)
        drawLine(c, Offset(p.x - half, p.y), Offset(p.x - gap / 2, p.y), w)
        drawLine(c, Offset(p.x + gap / 2, p.y), Offset(p.x + half, p.y), w)
    }
    if (dot) drawCircle(Color.White.copy(alpha = alpha), 1.4.dp.toPx(), p)
}

private fun DrawScope.spotLabel(tm: TextMeasurer, p: Offset, tag: String?, r: Reading, big: Boolean, alpha: Float) {
    val label = buildAnnotatedString {
        if (tag != null) withStyle(text(11.sp, FontWeight.Bold, C.Soft, 0.1.em, ReadingShadow).toSpanStyle()) { append("$tag ") }
        withStyle(text(if (big) 34.sp else 22.sp, FontWeight.SemiBold, spacing = (-0.02).em, shadow = ReadingShadow).toSpanStyle()) { append(r.text) }
        if (big) withStyle(text(16.sp, FontWeight.Medium, C.Text2, shadow = ReadingShadow).toSpanStyle()) { append(" " + Units.label) }
    }
    val lay = tm.measure(label)
    val sat = if (r.sat != Reading.Sat.None && big) tm.measure("OUTSIDE CAMERA RANGE", text(10.sp, FontWeight.Bold, C.Warning, 0.12.em, ReadingShadow)) else null
    val total = lay.size.height + (sat?.size?.height ?: 0)
    // below the spot near the top of the image, above it elsewhere (design: labelTop)
    val top = if (p.y < 190.dp.toPx()) p.y + 22.dp.toPx() else p.y - total - (if (big) 26.dp.toPx() else 18.dp.toPx())
    drawText(lay, topLeft = Offset(p.x - lay.size.width / 2f, top), alpha = alpha)
    if (sat != null) drawText(sat, topLeft = Offset(p.x - sat.size.width / 2f, top + lay.size.height - 2.dp.toPx()), alpha = alpha)
}

// ---------------------------------------------------------------------------------------------
// Scale bar (auto / locked with handles)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ScaleBar(
    a: Analysis,
    s: be.apexlab.kelvin.ViewSettings,
    modifier: Modifier,
    barH: androidx.compose.ui.unit.Dp,
    onToggleLock: () -> Unit,
    onRange: (Float, Float) -> Unit,
) {
    val colors = s.palette.colors.map { Color(it) }
    val spot = a.at(s.spotA).c
    val tick = (1 - (spot - a.rangeMin) / maxOf(a.rangeMax - a.rangeMin, 0.5f)).coerceIn(0f, 1f)
    Column(modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!s.locked) {
            val maxSat = a.anySatHi
            val minSat = a.anySatLo
            Column(Modifier.tap(onToggleLock), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScaleLabel(if (maxSat) Reading(0f, Reading.Sat.High).text else Units.t(a.rangeMax), if (maxSat) C.Warning else C.Text)
                Bar(colors, barH, tick, ring = Color(0x4DFFFFFF), capHi = maxSat, capLo = minSat)
                ScaleLabel(if (minSat) Reading(0f, Reading.Sat.Low).text else Units.t(a.rangeMin), if (minSat) C.Warning else C.Text)
            }
        } else {
            val lo by rememberUpdatedState(s.lockLo)
            val hi by rememberUpdatedState(s.lockHi)
            RangeField(s.lockHi) { v -> onRange(lo, v.coerceIn(lo + 1f, Thermal.RANGE_HI)) }
            Box(
                Modifier.width(48.dp).height(barH).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val top = down.position.y < size.height / 2
                        var last = down.position.y
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { ch ->
                                if (!ch.pressed) return@forEach
                                // 0.2 °C per dp; dragging up raises the value
                                val delta = -(ch.position.y - last) / density * 0.2f
                                last = ch.position.y
                                if (top) onRange(lo, (hi + delta).coerceIn(lo + 1f, Thermal.RANGE_HI))
                                else onRange((lo + delta).coerceIn(Thermal.RANGE_LO, hi - 1f), hi)
                                ch.consume()
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Bar(colors, barH, tick, ring = C.Accent, capHi = false, capLo = false)
                Handle(Modifier.align(Alignment.TopCenter).offset(x = 9.dp, y = (-8).dp))
                Handle(Modifier.align(Alignment.BottomCenter).offset(x = 9.dp, y = 8.dp))
            }
            RangeField(s.lockLo) { v -> onRange(v.coerceIn(Thermal.RANGE_LO, hi - 1f), hi) }
        }
        Column(
            Modifier.padding(top = 2.dp).size(44.dp).clip(CircleShape)
                .background(if (s.locked) Color(0x294FD1E8) else Color(0xB814141A))
                .border(1.dp, Color(0x1FFFFFFF), CircleShape).tap(onToggleLock),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val color = if (s.locked) C.Accent else C.Text2
            Icon(if (s.locked) Ic.LockClosed else Ic.LockOpen, color, 14.dp)
            BasicText(if (s.locked) "LOCKED" else "AUTO", style = text(8.sp, FontWeight.Bold, color, 0.1.em))
        }
    }
}

@Composable
private fun ScaleLabel(s: String, color: Color) {
    Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
        BasicText(s, style = text(13.sp, FontWeight.SemiBold, color, shadow = ReadingShadow))
    }
}

/** Locked-range limit: shows the value; tap to type a new one (applied on Done or when focus leaves). */
@Composable
private fun RangeField(v: Float, onCommit: (Float) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun commit() {
        draft.replace(',', '.').replace('−', '-').toFloatOrNull()?.let { onCommit(Units.fromDisplay(it)) }
        editing = false
    }
    BasicTextField(
        value = if (editing) draft else Units.t(v),
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = text(12.sp, FontWeight.SemiBold).copy(textAlign = TextAlign.Center),
        cursorBrush = SolidColor(C.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.size(56.dp, 26.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC0B0B0F))
            .border(1.dp, C.Accent, RoundedCornerShape(8.dp))
            .onFocusChanged { f ->
                if (f.isFocused && !editing) { draft = Units.t(v).replace('−', '-'); editing = true }
                else if (!f.isFocused && editing) commit()
            },
        decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
    )
}

@Composable
private fun Handle(modifier: Modifier) {
    Column(
        modifier.size(28.dp, 16.dp).shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        repeat(2) { Box(Modifier.size(12.dp, 1.5.dp).background(C.Muted)) }
    }
}

@Composable
private fun Bar(colors: List<Color>, h: androidx.compose.ui.unit.Dp, tick: Float, ring: Color, capHi: Boolean, capLo: Boolean) {
    Canvas(Modifier.size(20.dp, h)) {
        val bw = 10.dp.toPx()
        val left = (size.width - bw) / 2
        val corner = CornerRadius(5.dp.toPx())
        drawRoundRect(ring, Offset(left - 1.dp.toPx(), -1.dp.toPx()), Size(bw + 2.dp.toPx(), size.height + 2.dp.toPx()), CornerRadius(6.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(colors.reversed()), Offset(left, 0f), Size(bw, size.height), corner)
        if (capHi) drawRoundRect(C.Warning, Offset(left - 3.dp.toPx(), -3.dp.toPx()), Size(16.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        if (capLo) drawRoundRect(C.Warning, Offset(left - 3.dp.toPx(), size.height - 1.dp.toPx()), Size(16.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val y = tick * size.height
        val tri = Path().apply {
            moveTo(left - 9.dp.toPx(), y - 5.dp.toPx())
            lineTo(left - 3.dp.toPx(), y)
            lineTo(left - 9.dp.toPx(), y + 5.dp.toPx())
            close()
        }
        drawPath(tri, Color.White)
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar, status stack, chips
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopBar(cam: CameraController, ui: UiState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassPill {
            when {
                cam.conn is Conn.Reconnecting -> {
                    Spinner(C.Warning, 14.dp, 2.dp)
                    BasicText("FLIR ONE", style = text(13.sp, FontWeight.SemiBold))
                    BasicText("· reconnecting", style = text(13.sp, color = C.Warning))
                }
                cam.paused -> {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.Muted))
                    BasicText("FLIR ONE", style = text(13.sp, FontWeight.SemiBold))
                    BasicText("· paused", style = text(13.sp, color = C.Secondary))
                }
                else -> {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0x2E3DDC84)).padding(3.dp).clip(CircleShape).background(C.Connected))
                    BasicText("FLIR ONE", style = text(13.sp, FontWeight.SemiBold))
                    BasicText("· connected", style = text(13.sp, color = C.Secondary))
                }
            }
        }
        Box(Modifier.weight(1f))
        cam.info.battery?.let { pct ->
            val low = pct < 20 && !cam.info.charging
            val color = if (low) C.Warning else C.Text
            GlassPill(Modifier.padding(start = 0.dp)) {
                BatteryRing(pct, cam.info.charging, color)
                BasicText("$pct%", style = text(13.sp, FontWeight.SemiBold, color))
            }
        }
        RoundButton(Ic.Bell, { ui.sheet = SheetKind.Alarms }, badge = if (ui.settings.alarmOn) C.Isotherm else null)
        RoundButton(Ic.Sliders, { ui.open(Screen.Settings) })
    }
}

@Composable
private fun StatusStack(cam: CameraController, ui: UiState, a: Analysis?, frozenSec: Int, app: ThermalApp) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val recon = cam.conn as? Conn.Reconnecting
        if (recon != null && frozenSec < 30) Banner(Color(0x59F5B83D)) {
            Box(Modifier.padding(top = 1.dp)) { Spinner(C.Warning, 22.dp, 2.dp) }
            Column(Modifier.weight(1f)) {
                BasicText("Camera disconnected — reconnecting", style = text(15.sp, FontWeight.SemiBold))
                BasicText("Showing the last frame. Usually back within a few seconds; keep the camera plugged in.", style = text(13.sp, color = C.Body, lineHeight = 18.sp), modifier = Modifier.padding(top = 3.dp))
                BasicText("FROZEN · $frozenSec S · ATTEMPT ${recon.attempt}", style = mono(11.sp, C.Warning), modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (recon != null && frozenSec >= 30) Banner(Color(0x8CF5B83D)) {
            Icon(Ic.Power, C.Warning, 22.dp, Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f)) {
                BasicText("Camera off or unplugged?", style = text(15.sp, FontWeight.SemiBold))
                BasicText("It turns itself off after 2 min idle — press its power button.", style = text(13.sp, color = C.Body, lineHeight = 18.sp), modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText("STILL TRYING · ${if (frozenSec < 60) "$frozenSec S" else "${frozenSec / 60} MIN ${frozenSec % 60} S"}", style = mono(11.sp, C.Warning))
                    Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x24F5B83D)).tap { ui.connectHelp = true }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        BasicText("Connection help", style = text(13.sp, FontWeight.SemiBold, C.Warning))
                    }
                }
            }
        }
        val pct = cam.info.battery
        if (pct != null && pct < 20 && !cam.info.charging && !ui.lowBatteryDismissed && recon == null) Banner(Color(0x59F5B83D)) {
            Icon(Ic.BatteryLow, C.Warning, 22.dp, Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f)) {
                BasicText("Camera battery low · $pct%", style = text(15.sp, FontWeight.SemiBold))
                BasicText("About ${maxOf(1, (pct * 60) / 100)} min of streaming left. Charge the FLIR ONE through its own USB‑C port.", style = text(13.sp, color = C.Body, lineHeight = 18.sp), modifier = Modifier.padding(top = 3.dp))
            }
            Box(Modifier.height(36.dp).tap { ui.lowBatteryDismissed = true }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                BasicText("Dismiss", style = text(13.sp, FontWeight.SemiBold, C.Warning))
            }
        }
        if (a != null && (a.anySatHi || a.anySatLo)) Chip(bg = Color(0xDB14141A), borderColor = Color(0x8CF5B83D)) {
            Icon(Ic.Warning, C.Warning, 14.dp)
            BasicText(if (a.anySatHi) "PARTS ABOVE ${Units.whole(Thermal.RANGE_HI)} ${Units.label} · CAMERA LIMIT" else "PARTS BELOW ${Units.whole(Thermal.RANGE_LO)} ${Units.label} · CAMERA LIMIT", style = text(12.sp, FontWeight.SemiBold, C.Warning))
        }
        if (cam.calibrating && cam.conn == Conn.Live) Chip {
            Icon(Ic.Clock, C.Text2, 14.dp)
            BasicText("CALIBRATING", style = text(12.sp, FontWeight.SemiBold, C.Text2, 0.08.em))
        }
        if (app.recorder.recording) Chip {
            Box(Modifier.size(8.dp).clip(CircleShape).background(C.Hot))
            val elapsed by produceState(0L, app.recorder.startedAt) {
                while (true) { value = android.os.SystemClock.elapsedRealtime() - app.recorder.startedAt; delay(500) }
            }
            BasicText("REC ${durationText(elapsed)}", style = text(13.sp, FontWeight.SemiBold))
        }
        if (app.timelapse.running) Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xDB14141A)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(17.dp))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val n = app.timelapse.samples.size
            BasicText("Timelapse · $n frame${if (n == 1) "" else "s"} · 1 / ${TimelapseLogger.INTERVAL_MS / 1000} s", style = text(13.sp, FontWeight.SemiBold))
            Box(
                Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(Color(0x294FD1E8)).tap { ui.open(Screen.Timelapse(null)) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText("View log", style = text(12.sp, FontWeight.Medium, C.Accent)) }
        }
        val st = ui.settings
        if (a != null && st.alarmOn && ui.sheet == SheetKind.None && cam.conn == Conn.Live && a.isoMatchPct > 0f) {
            val label = when (st.isoMode) {
                IsoMode.Above -> "ABOVE ${Units.whole(st.isoLo)} ${Units.label} · PEAK ${a.hotReading.text}"
                IsoMode.Below -> "BELOW ${Units.whole(st.isoLo)} ${Units.label} · LOW ${a.coldReading.text}"
                IsoMode.Between -> "${Units.whole(st.isoLo)}–${Units.whole(st.isoHi)} ${Units.label} IN VIEW"
            }
            Row(
                Modifier.height(32.dp).shadow(18.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.Isotherm).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Ic.Triangle, C.OnIsotherm, 14.dp)
                BasicText(label, style = text(13.sp, FontWeight.Bold, C.OnIsotherm))
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, modifier: Modifier, alpha: Float, onClick: () -> Unit) {
    Box(modifier.height(40.dp).tap(onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x990B0B0F)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
                .padding(start = 9.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(text, style = mono(11.sp, Color(0xFFDADAE0).copy(alpha = alpha)))
            Icon(Ic.ChevronUp, Color(0xFFDADAE0).copy(alpha = alpha), 10.dp)
        }
    }
}

@Composable
private fun Pip(img: ImageBitmap, label: String, dim: ColorFilter?, rot: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(if (rot % 180 == 0) DpSize(84.dp, 112.dp) else DpSize(112.dp, 84.dp))
            .shadow(18.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111111)).border(1.5.dp, Color(0xBFFFFFFF), RoundedCornerShape(12.dp)).tap(onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawImageCover(img, dim, rot = rot) }
        BasicText(
            label, style = mono(9.sp, Color.White, FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x8C000000)).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PausedOverlay(onResume: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0x590B0B0F)).tap(onResume),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(72.dp).shadow(28.dp, CircleShape).clip(CircleShape).background(Color(0xF0F2F2F5)), contentAlignment = Alignment.Center) {
            Icon(Ic.Play, C.Background, 28.dp)
        }
        BasicText("Stream paused · tap to resume", style = text(18.sp, FontWeight.SemiBold, shadow = ReadingShadow))
        CenteredText(
            "Paused while Kelvin was in the background, to save the camera battery. Showing the last frame.",
            text(13.sp, color = Color(0xFFDADAE0), shadow = ReadingShadow, lineHeight = 19.sp),
            Modifier.width(270.dp),
        )
    }
}

@Composable
private fun Toast(msg: String, modifier: Modifier) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF21C1C24)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ic.Check, C.Accent, 16.dp)
        BasicText(msg, style = text(13.sp, FontWeight.Medium))
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom controls: gallery thumbnail, shutter, palette
// ---------------------------------------------------------------------------------------------

@Composable
private fun BottomControls(
    ui: UiState,
    recording: Boolean,
    tlRunning: Boolean,
    enabled: Boolean,
    onMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(C.Background)
            .drawBehind { drawLine(Color(0x0FFFFFFF), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding().padding(top = 12.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // capture mode tabs
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in CaptureMode.entries) {
                val sel = m == ui.mode
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (sel) Color(0x294FD1E8) else Color.Transparent)
                        .tap { onMode(m) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText(m.label.uppercase(), style = text(12.sp, FontWeight.SemiBold, if (sel) C.Accent else C.Secondary, 0.1.em)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 36.dp, end = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            GalleryButton(ui, onGallery)
            Shutter(ui.mode, recording, tlRunning, enabled, onShutter)
            PaletteButton(ui)
        }
    }
}

/** Landscape: the controls run down the right edge; the current mode sits above the shutter, the next one below. */
@Composable
private fun SideControls(
    ui: UiState,
    recording: Boolean,
    tlRunning: Boolean,
    enabled: Boolean,
    onMode: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
) {
    val next = CaptureMode.entries[(ui.mode.ordinal + 1) % CaptureMode.entries.size]
    Column(
        Modifier.fillMaxHeight().background(C.Background)
            .drawBehind { drawLine(Color(0x0FFFFFFF), Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }
            // only the insets on the panel's own side (the punch-hole / 3-button bar may sit on either edge)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
            .width(120.dp)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        GalleryButton(ui, onGallery)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(ui.mode.label.uppercase(), style = text(12.sp, FontWeight.SemiBold, C.Accent, 0.1.em))
            Shutter(ui.mode, recording, tlRunning, enabled, onShutter)
            Box(Modifier.height(32.dp).tap { onMode(next) }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                BasicText(next.label.uppercase(), style = text(12.sp, FontWeight.SemiBold, C.Secondary, 0.1.em))
            }
        }
        PaletteButton(ui)
    }
}

@Composable
private fun GalleryButton(ui: UiState, onGallery: () -> Unit) {
    Column(Modifier.tap(onGallery), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(16.dp))) {
            ui.lastThumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        BasicText("${ui.captureCount} SHOTS", style = text(11.sp, color = C.Secondary, spacing = 0.08.em))
    }
}

/** Shutter per mode: white (photo), red dot / stop square (video), clock (timelapse, accent while logging). */
@Composable
private fun Shutter(mode: CaptureMode, recording: Boolean, tlRunning: Boolean, enabled: Boolean, onShutter: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.size(88.dp).drawBehind { drawCircle(Color(0x0FFFFFFF), size.minDimension / 2 + 6.dp.toPx()) }
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.35f), CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) onShutter()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dimmed = Color(0xFF5E5E66)
        val size = if (pressed) 63.dp else 70.dp
        when (mode) {
            CaptureMode.Photo -> Box(Modifier.size(size).clip(CircleShape).background(if (enabled) Color.White else dimmed))
            CaptureMode.Video ->
                if (recording) Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(C.Hot))
                else Box(Modifier.size(size).clip(CircleShape).background(if (enabled) C.Hot else dimmed))
            CaptureMode.Timelapse -> Box(
                Modifier.size(size).clip(CircleShape).background(if (tlRunning) C.Accent else if (enabled) Color.White else dimmed),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.TimelapseClock, C.Background, 26.dp) }
        }
    }
}

@Composable
private fun PaletteButton(ui: UiState) {
    Column(Modifier.tap { ui.sheet = SheetKind.Palette }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(C.Surface).border(1.dp, Color(0x1AFFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            val colors = ui.settings.palette.colors.map { Color(it) }
            Canvas(Modifier.size(30.dp)) {
                drawCircle(Color(0x59FFFFFF), size.minDimension / 2 + 3.5.dp.toPx())
                drawCircle(C.Surface, size.minDimension / 2 + 2.dp.toPx())
                drawCircle(Brush.sweepGradient(colors + colors.first()))
            }
        }
        BasicText(ui.settings.palette.label.uppercase(), style = text(11.sp, color = C.Secondary, spacing = 0.08.em))
    }
}

// ---------------------------------------------------------------------------------------------
// Alarm side effects: beep at most every 10 s, vibrate when the scene first matches
// ---------------------------------------------------------------------------------------------

@Composable
private fun AlarmEffects(ctx: Context, ui: UiState, a: Analysis?, sheetOpen: Boolean, active: Boolean) {
    val state = remember { object { var matched = false; var lastBeep = 0L } }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    val s = ui.settings
    val matching = a != null && s.alarmOn && !sheetOpen && active && a.isoMatchPct > 0f
    LaunchedEffect(matching, a) {
        val now = SystemClock.elapsedRealtime()
        if (matching) {
            if (!state.matched && s.vibrate) ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            if (s.sound && now - state.lastBeep >= 10_000) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150); state.lastBeep = now }
        }
        state.matched = matching
    }
}
