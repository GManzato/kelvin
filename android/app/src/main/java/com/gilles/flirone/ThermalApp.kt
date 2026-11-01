package com.gilles.flirone

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SheetKind { None, Palette, Alarms, Emissivity }

/** Shutter mode on the live view. */
enum class CaptureMode(val label: String) { Photo("Photo"), Video("Video"), Timelapse("Timelapse") }

/** App screens; the live view (with its connection states) is the root. */
sealed interface Screen {
    data object Live : Screen
    data object Gallery : Screen
    data class Viewer(val id: String) : Screen
    data class Compare(val before: String, val after: String) : Screen
    data object Settings : Screen
    data object Compatibility : Screen
    /** Timelapse log: the running one ([id] null) or a saved capture. */
    data class Timelapse(val id: String?) : Screen
}

/** UI state that outlives the activity (rotation, background). */
class UiState {
    var settings by mutableStateOf(ViewSettings())
        private set
    /** Called after every settings change (persistence, units, camera alignment). */
    var onSettings: (ViewSettings) -> Unit = {}
    var sheet by mutableStateOf(SheetKind.None)
    var lowBatteryDismissed by mutableStateOf(false)
    /** "Connection help" from the long-reconnect banner: shows the plug-in screen with tips. */
    var connectHelp by mutableStateOf(false)
    var lastCapture by mutableStateOf<Capture?>(null)
    var lastThumb by mutableStateOf<Bitmap?>(null)
    var captureCount by mutableIntStateOf(0)
    var toast by mutableStateOf<String?>(null)
    var mode by mutableStateOf(CaptureMode.Photo)
    /** The stream was paused because another screen covers the live view (resumes on return). */
    var autoPaused = false
    /** Capture viewer edits (palette, spots) per capture id, kept while the app runs. */
    val viewerEdits = HashMap<String, Pair<Palette, List<Pt>>>()

    var stack by mutableStateOf(listOf<Screen>(Screen.Live))
        private set
    val screen: Screen get() = stack.last()

    fun open(s: Screen) {
        stack = stack + s
    }

    /** Replaces the top screen (e.g. after deleting the capture being viewed). */
    fun replace(s: Screen) {
        stack = stack.dropLast(1) + s
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun update(f: (ViewSettings) -> ViewSettings) {
        settings = f(settings)
        onSettings(settings)
    }

    /** Initial settings from storage (no change callback). */
    fun restore(s: ViewSettings) {
        settings = s
    }
}

class ThermalApp : Application() {
    lateinit var camera: CameraController
    lateinit var captures: Captures
    lateinit var recorder: VideoRecorder
    val timelapse = TimelapseLogger()
    val ui = UiState()

    override fun onCreate() {
        super.onCreate()
        camera = CameraController(this)
        captures = Captures(this)
        recorder = VideoRecorder(captures, cacheDir)
        val prefs = Prefs(this)
        ui.restore(prefs.load())
        apply(ui.settings)
        ui.onSettings = { s ->
            prefs.save(s)
            apply(s)
        }
        camera.onFrameListener = { f ->
            recorder.offer(f, ui.settings)
            timelapse.offer(f.raw, ui.settings)
        }
        refreshCaptures()
    }

    private fun apply(s: ViewSettings) {
        Units.fahrenheit = s.fahrenheit
        camera.distanceM = s.distanceM
        camera.visibleFlip = s.visibleFlip
    }

    fun refreshCaptures() {
        ui.captureCount = captures.count()
        ui.lastCapture = captures.latest()
        ui.lastThumb = ui.lastCapture?.let(captures::thumb)
    }

    /** Stops video recording and saves it; [done] gets the capture (main thread). */
    fun stopRecording(done: (Capture?) -> Unit = {}) {
        if (!recorder.recording) return done(null)
        recorder.stop { c ->
            refreshCaptures()
            ui.toast = if (c != null) "Video saved · ${durationText(c.durationMs)}" else "Video too short, not saved"
            done(c)
        }
    }

    /** Stops the timelapse log and saves it as a capture; [done] gets it (main thread). */
    fun stopTimelapse(done: (Capture?) -> Unit = {}) {
        val log = timelapse.stop()
        if (log.isEmpty()) return done(null)
        val s = ui.settings
        val info = camera.info
        Thread {
            val last = log.last()
            val thumb = Bitmap.createScaledBitmap(captures.render(last.temps, s.palette, last.min, last.max), PW * 8, PH * 8, true)
            val c = runCatching { captures.saveTimelapse(log, s, info, thumb) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                refreshCaptures()
                ui.toast = if (c != null) "Timelapse saved · ${log.size} frames" else "Timelapse could not be saved"
                done(c)
            }
        }.start()
    }
}
