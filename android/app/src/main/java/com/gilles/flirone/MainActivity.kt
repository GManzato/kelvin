package com.gilles.flirone

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gilles.flirone.ui.CompareScreen
import com.gilles.flirone.ui.ConnectingScreen
import com.gilles.flirone.ui.DeniedScreen
import com.gilles.flirone.ui.GalleryScreen
import com.gilles.flirone.ui.LiveScreen
import com.gilles.flirone.ui.PlugInScreen
import com.gilles.flirone.ui.RestartingScreen
import com.gilles.flirone.ui.UnsupportedScreen
import com.gilles.flirone.ui.ViewerScreen
import com.gilles.flirone.ui.SettingsScreen
import androidx.compose.runtime.remember
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.CompositionLocalProvider
import com.gilles.flirone.ui.LocalOpenSettings
import android.content.pm.ActivityInfo
import androidx.compose.ui.platform.LocalContext
import com.gilles.flirone.ui.TimelapseScreen
import com.gilles.flirone.ui.CompatibilityScreen
import com.gilles.flirone.ui.LocalOpenCompatibility

class MainActivity : ComponentActivity() {
    private val app get() = application as ThermalApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app.camera.start()
        handleUsbIntent(intent)
        setContent {
            val openSettings = remember { { app.ui.connectHelp = false; app.ui.open(Screen.Settings) } }
            val openCompatibility = remember { { app.ui.sheet = SheetKind.None; app.ui.open(Screen.Compatibility) } }
            CompositionLocalProvider(LocalOpenSettings provides openSettings, LocalOpenCompatibility provides openCompatibility) { App(app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * Power saving (Settings): stop the frame stream in the background; the user taps to resume.
     * A running timelapse keeps streaming.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && app.ui.settings.powerSaving && !app.timelapse.running) app.camera.pause()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION")
        val dev = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        app.camera.scan(dev)
    }
}

@Composable
private fun App(app: ThermalApp) {
    val cam = app.camera
    val ui = app.ui
    BackHandler(enabled = ui.sheet != SheetKind.None || ui.connectHelp || ui.stack.size > 1) {
        when {
            ui.connectHelp -> ui.connectHelp = false
            ui.sheet != SheetKind.None -> ui.sheet = SheetKind.None
            else -> ui.back()
        }
    }
    // The stream pauses while a screen covers the live view (camera battery) and resumes on return.
    // Settings keeps it running for the alignment preview, a timelapse for its log.
    val streamWanted = ui.screen == Screen.Live || ui.screen == Screen.Settings || app.timelapse.running
    LaunchedEffect(streamWanted) {
        if (streamWanted) {
            if (ui.autoPaused) { ui.autoPaused = false; cam.resume() }
        } else if (cam.conn == Conn.Live && !cam.paused) {
            ui.autoPaused = true
            cam.pause()
        }
    }
    // Only the live view turns to landscape (the board set's other screens are portrait); it follows
    // the user's auto-rotate setting. The manifest's configChanges keep the activity (and a recording) alive.
    val activity = LocalContext.current as? android.app.Activity
    val onLive = ui.screen == Screen.Live && !ui.connectHelp
    LaunchedEffect(onLive) {
        activity?.requestedOrientation =
            if (onLive) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    // Calibration sound (Settings): a click when the shutter starts a recalibration.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 60) }.getOrNull() }
    LaunchedEffect(cam.calibrating) {
        if (cam.calibrating && ui.settings.calibrationSound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 60)
    }
    val gallery = { ui.connectHelp = false; ui.open(Screen.Gallery) }
    when (val s = ui.screen) {
        Screen.Gallery -> return GalleryScreen(ui, app.captures)
        is Screen.Viewer -> return ViewerScreen(s.id, ui, app.captures, app::refreshCaptures)
        is Screen.Compare -> return CompareScreen(s.before, s.after, ui, app.captures)
        Screen.Settings -> return SettingsScreen(ui, cam)
        Screen.Compatibility -> return CompatibilityScreen(ui, cam)
        is Screen.Timelapse -> return TimelapseScreen(s.id, app)
        Screen.Live -> Unit
    }
    if (ui.connectHelp) {
        PlugInScreen(cam, startWithTips = true, onBack = { ui.connectHelp = false }, onGallery = gallery)
        return
    }
    when (val c = cam.conn) {
        Conn.NoCamera -> PlugInScreen(cam, onGallery = gallery)
        is Conn.Connecting -> ConnectingScreen(c.step)
        is Conn.Restarting -> RestartingScreen(c, cam.now, onGallery = gallery)
        Conn.Denied -> DeniedScreen(cam, onGallery = gallery)
        is Conn.Unsupported -> UnsupportedScreen(c, onGallery = gallery)
        Conn.Live, is Conn.Reconnecting ->
            if (cam.frame != null) LiveScreen(app) else ConnectingScreen(2)
    }
}
