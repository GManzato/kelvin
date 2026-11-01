package com.gilles.flirone

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * FLIR ONE iOS/Lightning variant (09cb:1996) driver.
 *
 * 1. Claim IF0..IF2 and act as the Apple device on IF0 (iAP2 handshake).
 * 2. Open only the `com.flir.rosebud.config` EA session over iAP2: the camera then streams JSON
 *    (sledInformation, battery updates).
 * 3. `fileio` (IF1) and `frame` (IF2) are native transports: select alternate setting 1. Sending an
 *    EA start for them reboots the camera.
 * 4. Frames arrive on EP 0x85: 28-byte header (magic EF BE 00 00) + thermal + JPEG + status JSON.
 *
 * Pausing selects IF2 alternate setting 0 (frames stop) but keeps the iAP2 link alive: closing the
 * link instead leaves the camera unresponsive for ~30 s until it re-enumerates.
 */
class FlirCamera(
    private val usb: UsbManager,
    val device: UsbDevice,
    private val listener: Listener,
    private val diagnostics: CompatibilityDiagnostics,
) {
    enum class Stage { HANDSHAKE, STREAMING }

    interface Listener {
        /** Return false to stop before opening camera-specific sessions for an unknown model. */
        fun onIdentified(info: Map<String, String>): Boolean
        fun onEvent(event: JSONObject)
        fun onFrame(frame: Frame)
        fun onError(error: Throwable, stage: Stage)
    }

    /**
     * [raw]: 80×60 sensor counts, row-major, or null when the thermal block has another layout
     * (e.g. a 160×120 FLIR ONE Pro), see [thermalBytes].
     */
    class Frame(val raw: IntArray?, val thermalBytes: Int, val jpeg: ByteArray, val status: JSONObject?)

    @Volatile private var running = false
    @Volatile var paused = false
    private var conn: UsbDeviceConnection? = null
    private var worker: Thread? = null
    private var iapThread: Thread? = null
    private val cfg = ByteFifo(4096)

    fun start() {
        running = true
        worker = Thread({ run() }, "flir-frames").also { it.start() }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        iapThread?.join(1500)
        conn?.let { c ->
            for (id in 0..2) intf(id, 0)?.let { c.releaseInterface(it) }
            c.close()
        }
        conn = null
    }

    private fun intf(id: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == id && it.alternateSetting == alt }

    private fun endpoint(i: UsbInterface, dir: Int): UsbEndpoint =
        (0 until i.endpointCount).map { i.getEndpoint(it) }.first { it.direction == dir }

    private fun run() {
        var stage = Stage.HANDSHAKE
        try {
            diagnostics.record("usb_open", "Opening device connection")
            val c = usb.openDevice(device) ?: throw IOException("Cannot open USB device")
            conn = c
            for (id in 0..2) {
                val i = intf(id, 0) ?: throw IOException("Interface $id missing")
                if (!c.claimInterface(i, true)) throw IOException("Cannot claim interface $id")
                diagnostics.record("usb_claim", "Claimed interface $id alternate 0")
            }
            val if0 = intf(0, 0)!!
            val link = Iap2Link(c, endpoint(if0, UsbConstants.USB_DIR_IN), endpoint(if0, UsbConstants.USB_DIR_OUT), diagnostics::record)
            val info = link.handshake()
            if (!listener.onIdentified(info)) {
                diagnostics.record("model_gate", "Identification captured; model not supported. No config or frame session opened")
                return
            }

            diagnostics.record("config_session", "Opening rosebud.config EA protocol 0, session 1")
            link.startEa(PROTOCOL_CONFIG, CONFIG_SESSION)
            pump(link, 300)
            val fileio = intf(1, 1) ?: throw IOException("Fileio interface 1 alternate 1 missing")
            if (!c.setInterface(fileio)) throw IOException("Cannot enable fileio interface")
            diagnostics.record("fileio", "Enabled interface 1 alternate 1")
            pump(link, 500)

            stage = Stage.STREAMING
            iapThread = Thread({
                try {
                    while (running) handleEa(link.poll(50))
                } catch (t: Throwable) {
                    if (running) listener.onError(t, Stage.STREAMING)
                }
            }, "flir-iap2").also { it.start() }

            frameLoop(c)
        } catch (t: Throwable) {
            if (running) listener.onError(t, stage)
        }
    }

    private fun pump(link: Iap2Link, ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) handleEa(link.poll(20))
    }

    /** Config channel: EA packets carry a 2-byte session id, then RBProtocol config packets. */
    private fun handleEa(packets: List<Pair<Int, ByteArray>>) {
        for ((_, p) in packets) {
            if (p.size <= 2) continue
            cfg.append(p.copyOfRange(2, p.size), p.size - 2)
            while (cfg.size >= 16) {
                if (cfg.u32le(0) != 0x1CCL) { cfg.clear(); break }
                val len = cfg.u32le(8).toInt()
                if (cfg.size < 16 + len) break
                val js = String(cfg.copy(16, len)).trimEnd('\u0000')
                cfg.drop(16 + len)
                runCatching { listener.onEvent(JSONObject(js)) }
                    .onFailure { Log.w(TAG, "bad config json: $js") }
            }
        }
    }

    private fun frameLoop(c: UsbDeviceConnection) {
        val on = intf(2, 1) ?: throw IOException("Frame interface 2 alternate 1 missing")
        val off = intf(2, 0) ?: throw IOException("Frame interface 2 alternate 0 missing")
        val ep = endpoint(on, UsbConstants.USB_DIR_IN)
        diagnostics.record("frame_transport", "Ready for bulk frames on endpoint 0x%02x".format(ep.address))
        val rbuf = ByteArray(1 shl 17)
        val fifo = ByteFifo(1 shl 20)
        var streaming = false
        while (running) {
            if (paused == streaming) {
                streaming = !paused
                if (!c.setInterface(if (streaming) on else off)) throw IOException("Cannot switch frame interface")
                fifo.clear()
                diagnostics.record("frame_transport", if (streaming) "Enabled; waiting for frame data" else "Paused; alternate 0")
            }
            if (!streaming) { Thread.sleep(100); continue }
            val r = c.bulkTransfer(ep, rbuf, rbuf.size, 500)
            if (r <= 0) continue
            fifo.append(rbuf, r)
            while (true) {
                val i = fifo.indexOf(FRAME_MAGIC)
                if (i < 0) {
                    fifo.drop(maxOf(0, fifo.size - 3))
                    break
                }
                fifo.drop(i)
                if (fifo.size < 28) break
                val total = fifo.u32le(8)
                if (total > 4_000_000) { fifo.drop(4); continue }
                if (fifo.size < 28 + total) break
                val tLen = fifo.u32le(12).toInt()
                val jLen = fifo.u32le(16).toInt()
                val sLen = fifo.u32le(20).toInt()
                if (tLen < 0 || jLen < 0 || sLen < 0 || tLen.toLong() + jLen + sLen != total) {
                    throw IOException("Invalid frame lengths: total=$total thermal=$tLen jpeg=$jLen status=$sLen")
                }
                diagnostics.recordFrame(tLen, jLen, sLen)
                val raw = if (tLen == LEPTON_BYTES) IntArray(W * H).also { raw ->
                    // 63 Lepton VoSPI packets of 164 bytes (2-word id/crc + 80 px LE); rows 0-59 = image
                    for (y in 0 until H) {
                        val row = 28 + y * 164 + 4
                        for (x in 0 until W) raw[y * W + x] = fifo[row + 2 * x] or (fifo[row + 2 * x + 1] shl 8)
                    }
                } else null
                val jpeg = fifo.copy(28 + tLen, jLen)
                val status = runCatching {
                    JSONObject(String(fifo.copy(28 + tLen + jLen, sLen)).trimEnd('\u0000'))
                }.getOrNull()
                fifo.drop(28 + total.toInt())
                listener.onFrame(Frame(raw, tLen, jpeg, status))
            }
        }
    }

    companion object {
        private const val TAG = "FlirCamera"
        const val VENDOR_ID = 0x09CB
        const val PRODUCT_ID = 0x1996
        const val W = 80
        const val H = 60
        private const val LEPTON_BYTES = 63 * 164
        private const val PROTOCOL_CONFIG = 0 // com.flir.rosebud.config
        private const val CONFIG_SESSION = 1
        private val FRAME_MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x00)
    }
}
