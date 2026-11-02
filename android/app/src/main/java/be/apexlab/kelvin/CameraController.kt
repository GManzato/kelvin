package be.apexlab.kelvin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Connection states, one per board in the design's "Connection states" row. */
sealed interface Conn {
    /** 7a: nothing plugged in (or the camera is off). */
    data object NoCamera : Conn

    /** 7b: step 0 = waiting for USB permission, 1 = reading camera info (iAP2), 2 = starting stream. */
    data class Connecting(val step: Int) : Conn

    /** Streaming (or paused, see [CameraController.paused]). */
    data object Live : Conn

    /** 7c / 7l: lost mid-session; the live view keeps the last frame. */
    data class Reconnecting(val since: Long, val attempt: Int) : Conn

    /** 7e: the camera still holds a previous session's link and ignores us until it re-enumerates. */
    data class Restarting(val since: Long) : Conn

    /** 7f: the user refused Android's USB dialog. */
    data object Denied : Conn

    /** 7g: another FLIR model. */
    data class Unsupported(val name: String, val detail: String) : Conn
}

/** Camera info from the iAP2 identification and the config channel. */
data class CameraInfo(
    val model: String = "",
    val firmware: String = "",
    val serial: String = "",
    val battery: Int? = null,
    val voltage: Double? = null,
    val charging: Boolean = false,
    /** Sensor (Lepton) firmware version from the camera's sledInformation message. */
    val lepton: String = "",
)

/**
 * Owns the USB device and the [FlirCamera] session for the whole process, so the stream survives
 * activity recreation. All state is Compose snapshot state, written on the main thread.
 */
class CameraController(private val ctx: Context) : FlirCamera.Listener {
    private val usb = ctx.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    val diagnostics = CompatibilityDiagnostics(ctx)

    var conn: Conn by mutableStateOf(Conn.NoCamera)
        private set
    var info by mutableStateOf(CameraInfo())
        private set
    var paused by mutableStateOf(false)
        private set
    var calibrating by mutableStateOf(false)
        private set
    var fps by mutableStateOf(0.0)
        private set
    /** Latest frame; kept while reconnecting or paused so the live view can show it frozen. */
    var frame by mutableStateOf<LiveFrame?>(null)
        private set
    /** Main-thread clock, ticking every second, for countdowns and "frozen for" timers. */
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())
        private set

    /** Set by the live view when the visible camera fills the screen (needs a larger decode). */
    @Volatile var wantLargeVisible = false
    /** Set by the live view in the Edges view: compute an edge map of the visible image. */
    @Volatile var wantEdges = false
    /** Alignment from Settings: subject distance (parallax between the lenses) and a 180° flip. */
    @Volatile var distanceM = 0.6f
    @Volatile var visibleFlip = false

    private var camera: FlirCamera? = null
    private var hadFrames = false
    private var attempt = 0
    private var frameTimes = ArrayDeque<Long>()
    private var started = false

    /** [visible]: visible camera cropped and aligned to the thermal field of view; [edges]: its outlines. */
    class LiveFrame(val raw: IntArray, val visible: Bitmap?, val edges: Bitmap?, val jpeg: ByteArray, val at: Long)

    /** Called on the main thread for every new frame (video recording, timelapse logging). */
    var onFrameListener: ((LiveFrame) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev = intent.usbDevice() ?: return
            if (!dev.isFlir()) return
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    diagnostics.record("usb_permission", if (granted) "Granted" else "Denied")
                    diagnostics.observeDevice(dev)
                    if (granted) open(dev) else conn = Conn.Denied
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(dev)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(dev)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            now = SystemClock.elapsedRealtime()
            main.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(ACTION_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        main.post(ticker)
        diagnostics.record("controller", "Started; scanning attached FLIR devices")
        scan()
    }

    /** "It's plugged in" / app launch / USB attach intent: look for the camera and connect. */
    fun scan(device: UsbDevice? = null) {
        if (camera != null) return
        usb.deviceList.values.filter { it.isFlir() }.forEach(diagnostics::observeDevice)
        val dev = device?.takeIf { it.isFlir() } ?: findDevice()
        if (dev == null) {
            if (conn !is Conn.Reconnecting && conn !is Conn.Restarting) conn = Conn.NoCamera
            diagnostics.record("usb_scan", "No FLIR USB device enumerated; check camera power, adapter and USB host support")
            return
        }
        diagnostics.observeDevice(dev)
        if (dev.productId != FlirCamera.PRODUCT_ID) {
            info = CameraInfo()
            unsupported(
                runCatching { dev.productName }.getOrNull().orEmpty().ifBlank { "FLIR USB camera" },
                "USB %04x:%04x · transport not supported yet; no camera commands were sent".format(dev.vendorId, dev.productId),
            )
            return
        }
        if (usb.hasPermission(dev)) open(dev) else requestPermission(dev)
    }

    fun requestPermission() {
        findDevice()?.let { requestPermission(it) } ?: run { conn = Conn.NoCamera }
    }

    private fun requestPermission(dev: UsbDevice) {
        diagnostics.record("usb_permission", "Requesting access to the supported Lightning USB transport")
        conn = Conn.Connecting(0)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )
        usb.requestPermission(dev, pi)
    }

    fun pause() {
        if (conn != Conn.Live || paused) return
        paused = true
        diagnostics.record("stream", "Paused")
        camera?.paused = true
    }

    fun resume() {
        if (!paused) return
        paused = false
        diagnostics.record("stream", "Resumed")
        camera?.paused = false
        if (camera == null) scan()
    }

    private fun open(dev: UsbDevice) {
        if (camera != null) return
        if (dev.productId != FlirCamera.PRODUCT_ID) {
            scan(dev)
            return
        }
        if (conn !is Conn.Reconnecting) conn = Conn.Connecting(1)
        frameTimes.clear() // don't average across the gap of a reconnect
        diagnostics.observeDevice(dev)
        diagnostics.record("usb_open", "Opening supported Lightning USB transport")
        Log.i(TAG, "opening ${dev.deviceName}")
        camera = FlirCamera(usb, dev, this, diagnostics).also { it.paused = paused; it.start() }
    }

    private fun stopCamera() {
        val c = camera ?: return
        camera = null
        Thread { c.stop() }.start()
    }

    private fun onAttached(dev: UsbDevice) {
        diagnostics.observeDevice(dev)
        diagnostics.record("usb_attach", "FLIR USB device attached")
        when (val c = conn) {
            is Conn.Reconnecting -> conn = c.copy(attempt = ++attempt)
            is Conn.Restarting -> Unit
            else -> Unit
        }
        if (camera == null) scan(dev)
    }

    private fun onDetached(dev: UsbDevice) {
        diagnostics.observeDevice(dev)
        diagnostics.record("usb_detach", "FLIR USB device detached")
        if (camera != null && camera?.device?.deviceName != dev.deviceName) return
        if (camera?.device?.deviceName == dev.deviceName) stopCamera()
        when (conn) {
            Conn.Live -> {
                conn = Conn.Reconnecting(SystemClock.elapsedRealtime(), ++attempt)
                paused = false
            }
            is Conn.Reconnecting -> Unit
            is Conn.Restarting -> Unit
            // The camera re-enumerates every ~5 s until someone completes the handshake.
            is Conn.Connecting -> main.postDelayed({ if (camera == null && findDevice() == null) conn = Conn.NoCamera }, 8000)
            else -> conn = Conn.NoCamera
        }
    }

    // ---- FlirCamera.Listener (camera threads) ---------------------------------------------------

    override fun onIdentified(info: Map<String, String>): Boolean {
        diagnostics.identified(info)
        val model = info["model"].orEmpty()
        val supported = model.startsWith("435-0004")
        main.post {
            this.info = CameraInfo(model = model, firmware = info["firmware"].orEmpty(), serial = info["serial"].orEmpty())
            when {
                supported -> if (conn !is Conn.Reconnecting) conn = Conn.Connecting(2)
                model.startsWith("435-0006") || info["name"].orEmpty().contains("Pro") ->
                    unsupported("FLIR ONE Pro", "Model $model · thermal layout and calibration not supported yet")
                else -> unsupported(info["name"] ?: "FLIR camera", "Model $model · not tested yet")
            }
        }
        return supported
    }

    override fun onEvent(event: JSONObject) {
        val data = event.optJSONObject("data") ?: return
        diagnostics.config(event.optString("type"), data)
        main.post {
            info = when (event.optString("type")) {
                "batteryVoltageUpdate" -> info.copy(battery = data.optInt("percentage"), voltage = data.optDouble("voltage"))
                "sledInformation" -> info.copy(lepton = data.optString("versionLepton"))
                "batteryChargingStateUpdate" -> info.copy(charging = data.optString("chargingState").let {
                    it.contains("Charging") && !it.contains("Fault")
                })
                else -> info
            }
        }
    }

    override fun onFrame(frame: FlirCamera.Frame) {
        val raw = frame.raw
        if (raw == null) {
            main.post { unsupported(info.model.ifBlank { "FLIR camera" }, "Unrecognised thermal block: ${frame.thermalBytes} bytes; no temperature conversion applied") }
            return
        }
        val visible = decodeVisible(frame.jpeg, if (wantLargeVisible) 2 else 4)
        val edges = if (wantEdges && visible != null) edgeMap(visible) else null
        val shutterFfc = frame.status?.optString("shutterState") == "FFC"
        val t = SystemClock.elapsedRealtime()
        main.post {
            val live = LiveFrame(raw, visible, edges, frame.jpeg, t)
            this.frame = live
            onFrameListener?.invoke(live)
            calibrating = shutterFfc
            frameTimes.addLast(t)
            while (frameTimes.size > 20) frameTimes.removeFirst()
            if (frameTimes.size >= 2) fps = (frameTimes.size - 1) * 1000.0 / (frameTimes.last() - frameTimes.first())
            if (conn != Conn.Live && camera != null) {
                conn = Conn.Live
                hadFrames = true
                attempt = 0
            }
        }
    }

    override fun onError(error: Throwable, stage: FlirCamera.Stage) {
        Log.w(TAG, "camera error at $stage", error)
        diagnostics.recordError(stage.name, error)
        main.post {
            stopCamera()
            val n = SystemClock.elapsedRealtime()
            conn = when {
                conn is Conn.Unsupported -> conn
                hadFrames -> (conn as? Conn.Reconnecting) ?: Conn.Reconnecting(n, ++attempt)
                stage == FlirCamera.Stage.HANDSHAKE -> (conn as? Conn.Restarting) ?: Conn.Restarting(n)
                else -> Conn.Restarting(n)
            }
        }
    }

    private fun unsupported(name: String, detail: String) {
        diagnostics.record("unsupported", "$name: $detail")
        stopCamera()
        conn = Conn.Unsupported(name, detail)
    }

    private fun decodeVisible(jpeg: ByteArray, sample: Int): Bitmap? {
        val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val m = Matrix().apply { postRotate(Orientation.VISIBLE_DEGREES + if (visibleFlip) 180f else 0f) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        val cw = (r.width * Orientation.VISIBLE_CROP).toInt()
        val ch = (r.height * Orientation.VISIBLE_CROP).toInt()
        // the lenses sit side by side; the thermal view shifts along that axis as the subject gets closer
        val shift = (Orientation.PARALLAX_M / distanceM * r.height).toInt()
        val top = ((r.height - ch) / 2 + if (visibleFlip) -shift else shift).coerceIn(0, r.height - ch)
        return Bitmap.createBitmap(r, (r.width - cw) / 2, top, cw, ch)
    }

    /** White outlines (alpha = Sobel magnitude) of the visible image at about thermal-overlay resolution. */
    private fun edgeMap(src: Bitmap): Bitmap {
        val w = 180
        val h = 240
        val s = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h).also { s.getPixels(it, 0, w, 0, 0, w, h) }
        val lum = FloatArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) }
        val out = IntArray(px.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun l(dx: Int, dy: Int) = lum[(y + dy) * w + x + dx]
            val gx = -l(-1, -1) - 2 * l(-1, 0) - l(-1, 1) + l(1, -1) + 2 * l(1, 0) + l(1, 1)
            val gy = -l(-1, -1) - 2 * l(0, -1) - l(1, -1) + l(-1, 1) + 2 * l(0, 1) + l(1, 1)
            val a = ((kotlin.math.sqrt(gx * gx + gy * gy) - 60f) * 1.6f).toInt().coerceIn(0, 230)
            out[y * w + x] = (a shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun findDevice(): UsbDevice? = usb.deviceList.values
        .filter { it.isFlir() }
        .sortedBy { if (it.productId == FlirCamera.PRODUCT_ID) 0 else 1 }
        .firstOrNull()

    private fun UsbDevice.isFlir() = vendorId == FlirCamera.VENDOR_ID

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val TAG = "CameraController"
        private const val ACTION_PERMISSION = "be.apexlab.kelvin.USB_PERMISSION"
        const val RESTART_SECONDS = 30
    }
}
