package be.apexlab.kelvin

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.security.SecureRandom

/**
 * Minimal iAP2 "Apple device" side over USB host mode (port of iap2host.py).
 *
 * The FLIR ONE iOS variant waits for an iPhone-like host on its iAP interface: answer the detect
 * marker, sync the link, run authentication (we send a challenge and ignore the answer; the
 * accessory never authenticates the host), identification, then open External Accessory sessions.
 */
class Iap2Link(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val report: (String, String) -> Unit,
) {
    private val buf = ByteFifo(1 shl 16)
    private val rx = ByteArray(16384)
    private var txSeq = 0x2B
    private var rxSeq = 0
    private var lsp: ByteArray? = null
    private var linked = false
    private var controlSid = -1
    private var eaSid = -1
    private var detectReported = false

    private fun csum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0
        for (k in off until off + len) s += b[k].toInt() and 0xFF
        return (-s) and 0xFF
    }

    @Synchronized
    private fun writeRaw(b: ByteArray) {
        if (conn.bulkTransfer(epOut, b, b.size, 1000) < 0) throw IOException("iAP2 write failed")
    }

    @Synchronized
    private fun write(control: Int, payload: ByteArray = EMPTY, session: Int = 0) {
        val len = 9 + if (payload.isNotEmpty()) payload.size + 1 else 0
        val p = ByteArray(len)
        p[0] = 0xFF.toByte(); p[1] = 0x5A
        p[2] = (len shr 8).toByte(); p[3] = len.toByte()
        p[4] = control.toByte(); p[5] = txSeq.toByte(); p[6] = rxSeq.toByte(); p[7] = session.toByte()
        p[8] = csum(p, 0, 8).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, p, 9, payload.size)
            p[len - 1] = csum(payload, 0, payload.size).toByte()
        }
        writeRaw(p)
    }

    @Synchronized
    private fun send(session: Int, payload: ByteArray) {
        txSeq = (txSeq + 1) and 0xFF
        write(ACK, payload, session)
    }

    private fun sendControl(msgId: Int, params: ByteArray = EMPTY) {
        val m = ByteArray(6 + params.size)
        m[0] = 0x40; m[1] = 0x40
        m[2] = (m.size shr 8).toByte(); m[3] = m.size.toByte()
        m[4] = (msgId shr 8).toByte(); m[5] = msgId.toByte()
        System.arraycopy(params, 0, m, 6, params.size)
        send(controlSid, m)
    }

    /** Reads once (up to [timeoutMs]) and returns complete data packets as (link session, payload). */
    fun poll(timeoutMs: Int): List<Pair<Int, ByteArray>> {
        val r = conn.bulkTransfer(epIn, rx, rx.size, timeoutMs)
        if (r > 0) buf.append(rx, r)
        val out = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            if (buf.startsWith(DETECT)) {
                buf.drop(DETECT.size)
                writeRaw(DETECT)
                if (!detectReported) {
                    report("iap2_detect", "Received and answered accessory detect marker")
                    detectReported = true
                }
                continue
            }
            val i = buf.indexOf(LINK_START)
            val j = buf.indexOf(DETECT_START)
            if (i < 0) {
                if (j >= 0 && buf.size - j >= DETECT.size) { buf.drop(j + 1); continue }
                return out
            }
            if (j in 0 until i) {
                buf.drop(j)
                if (buf.size < DETECT.size) return out
                if (!buf.startsWith(DETECT)) buf.drop(1)
                continue
            }
            buf.drop(i)
            if (buf.size < 9) return out
            var hsum = 0
            for (k in 0 until 9) hsum += buf[k]
            if (hsum and 0xFF != 0) { buf.drop(1); continue }
            val length = buf.u16be(2)
            val control = buf[4]
            val seq = buf[5]
            val session = buf[7]
            if (buf.size < length) return out
            val payload = if (length > 9) buf.copy(9, length - 10) else EMPTY
            buf.drop(length)
            if (control and RST != 0) throw IOException("accessory sent iAP2 RST")
            if (control and SYN != 0) { onSyn(payload, seq); continue }
            if (payload.isNotEmpty() && (control and ACK.inv()) == 0) {
                if (seq == rxSeq) { write(ACK); continue } // retransmit of a packet we already have
                rxSeq = seq
                write(ACK)
                out.add(session to payload)
            } else if (control and ACK != 0 && !linked && lsp != null) {
                linked = true
                report("iap2_sync", "Link established")
            }
        }
    }

    private fun onSyn(params: ByteArray, seq: Int) {
        lsp = params
        rxSeq = seq
        // session triples (id, type, version) after the 10-byte header; type 0 = control, 2 = EA
        var k = 10
        while (k + 2 < params.size) {
            val sid = params[k].toInt() and 0xFF
            when (params[k + 1].toInt()) {
                0 -> controlSid = sid
                2 -> eaSid = sid
            }
            k += 3
        }
        Log.i(TAG, "link sync: control=$controlSid ea=$eaSid")
        report("iap2_sync", "Received SYN: control session=$controlSid, external accessory session=$eaSid")
        write(SYN or ACK, params) // accept the accessory's link parameters as-is
    }

    private fun waitCsm(msgId: Int, timeoutMs: Long = 5000): List<Pair<Int, ByteArray>> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            for ((sess, p) in poll(100)) {
                if (sess == controlSid && p.size >= 6 && p[0].toInt() == 0x40 && p[1].toInt() == 0x40) {
                    val mid = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
                    if (mid == msgId) return parseParams(p, 6)
                    Log.w(TAG, "unhandled control message 0x%04x".format(mid))
                }
            }
        }
        throw IOException("iAP2: timed out waiting for 0x%04x".format(msgId))
    }

    /** Link sync, authentication and identification. Returns the accessory identification. */
    fun handshake(): Map<String, String> {
        report("iap2_sync", "Waiting for accessory link synchronisation")
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (!linked && SystemClock.elapsedRealtime() < deadline) poll(100)
        if (!linked) throw IOException("iAP2 link sync failed")
        report("iap2_auth", "Requesting accessory authentication certificate")
        sendControl(0xAA00) // RequestAuthenticationCertificate
        waitCsm(0xAA01)
        report("iap2_auth", "Certificate received; sending authentication challenge")
        sendControl(0xAA02, tlv(0, ByteArray(32).also { SecureRandom().nextBytes(it) })) // challenge
        waitCsm(0xAA03)
        report("iap2_auth", "Authentication response received")
        sendControl(0xAA05) // AuthenticationSucceeded
        sendControl(0x1D00) // StartIdentification
        report("iap2_identification", "Waiting for accessory identification")
        val ident = waitCsm(0x1D01)
        sendControl(0x1D02) // IdentificationAccepted
        report("iap2_identification", "Identification received and accepted")
        val names = mapOf(0 to "name", 1 to "model", 2 to "manufacturer", 3 to "serial", 4 to "firmware", 5 to "hardware")
        return ident.filter { it.first in names }
            .associate { (id, v) -> names.getValue(id) to String(v).trimEnd('\u0000') }
    }

    fun startEa(protocolId: Int, sessionId: Int) {
        sendControl(
            0xEA00,
            tlv(0, byteArrayOf(protocolId.toByte())) +
                tlv(1, byteArrayOf((sessionId shr 8).toByte(), sessionId.toByte())),
        )
    }

    companion object {
        private const val TAG = "Iap2"
        private const val SYN = 0x80
        private const val ACK = 0x40
        private const val RST = 0x10
        private val EMPTY = ByteArray(0)
        private val DETECT = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        private val DETECT_START = byteArrayOf(0xFF.toByte(), 0x55)
        private val LINK_START = byteArrayOf(0xFF.toByte(), 0x5A)

        private fun tlv(id: Int, payload: ByteArray): ByteArray {
            val len = payload.size + 4
            return byteArrayOf((len shr 8).toByte(), len.toByte(), (id shr 8).toByte(), id.toByte()) + payload
        }

        private fun parseParams(b: ByteArray, start: Int): List<Pair<Int, ByteArray>> {
            val out = ArrayList<Pair<Int, ByteArray>>()
            var o = start
            while (o + 4 <= b.size) {
                val len = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
                val id = ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
                if (len < 4 || o + len > b.size) break
                out.add(id to b.copyOfRange(o + 4, o + len))
                o += len
            }
            return out
        }
    }
}
