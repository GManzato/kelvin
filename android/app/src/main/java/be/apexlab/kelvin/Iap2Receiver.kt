package be.apexlab.kelvin

import java.io.IOException

/** Streaming iAP2 framing. USB I/O and outgoing sequence numbers belong to [Iap2Link]. */
internal class Iap2Receiver(
    private val writePacket: (Int, ByteArray) -> Unit,
    private val writeRaw: (ByteArray) -> Unit,
    private val report: (String, String) -> Unit,
) {
    private val buffer = ByteFifo(READ_SIZE)
    private var hasSyn = false
    private var detectReported = false
    var rxSeq = 0
        private set
    var linked = false
        private set
    var controlSid = -1
        private set
    var eaSid = -1
        private set
    val bufferedBytes: Int get() = buffer.size

    fun poll(bytes: ByteArray, len: Int = bytes.size): List<Pair<Int, ByteArray>> {
        if (len < 0 || len > bytes.size || len > READ_SIZE) {
            throw IOException("Invalid iAP2 input chunk length")
        }
        if (len > MAX_PACKET_LENGTH + READ_SIZE - buffer.size) {
            buffer.clear()
            throw IOException("iAP2 receive buffer limit exceeded")
        }
        buffer.append(bytes, len)
        var offset = 0
        var out: ArrayList<Pair<Int, ByteArray>>? = null
        try {
            while (offset < buffer.size) {
                val remaining = buffer.size - offset
                if (buffer[offset] != 0xFF) { offset++; continue }
                if (remaining < 2) break
                if (buffer[offset + 1] == 0x55) {
                    var matches = true
                    for (k in 0 until minOf(remaining, DETECT.size)) {
                        if (buffer[offset + k] != (DETECT[k].toInt() and 0xFF)) {
                            matches = false
                            break
                        }
                    }
                    if (!matches) { offset++; continue }
                    if (remaining < DETECT.size) break
                    offset += DETECT.size
                    writeRaw(DETECT)
                    if (!detectReported) {
                        report("iap2_detect", "Received and answered accessory detect marker")
                        detectReported = true
                    }
                    continue
                }
                if (buffer[offset + 1] != 0x5A) { offset++; continue }
                if (remaining < HEADER_SIZE) break
                var headerSum = 0
                for (k in 0 until HEADER_SIZE) headerSum += buffer[offset + k]
                val length = buffer.u16be(offset + 2)
                if (headerSum and 0xFF != 0 || length < HEADER_SIZE || length == HEADER_SIZE + 1) {
                    offset++
                    continue
                }
                if (remaining < length) break
                val start = offset
                offset += length
                if (length > HEADER_SIZE) {
                    var payloadSum = 0
                    for (k in start + HEADER_SIZE until offset) payloadSum += buffer[k]
                    // A trustworthy header defines the discard boundary. Never parse its corrupt body.
                    if (payloadSum and 0xFF != 0) continue
                }
                val control = buffer[start + 4]
                val seq = buffer[start + 5]
                if (control and RST != 0) throw IOException("Accessory sent iAP2 RST")
                if (control and SYN != 0) {
                    val payload = if (length > HEADER_SIZE) buffer.copy(start + HEADER_SIZE, length - 10) else EMPTY
                    onSyn(payload, seq)
                    continue
                }
                if (length > HEADER_SIZE && control and ACK.inv() == 0) {
                    if (seq == rxSeq) {
                        writePacket(ACK, EMPTY)
                        continue
                    }
                    val payload = buffer.copy(start + HEADER_SIZE, length - 10)
                    rxSeq = seq
                    writePacket(ACK, EMPTY)
                    if (out == null) out = ArrayList()
                    out.add(buffer[start + 7] to payload)
                } else if (length == HEADER_SIZE && control == ACK && !linked && hasSyn) {
                    linked = true
                    report("iap2_sync", "Link established")
                }
            }
        } finally {
            // One compaction per read, including failure paths; garbage scanning stays linear.
            buffer.drop(offset)
        }
        return out ?: emptyList()
    }

    private fun onSyn(params: ByteArray, seq: Int) {
        if (params.size < 10 || (params.size - 10) % 3 != 0) {
            throw IOException("Malformed iAP2 SYN session parameters")
        }
        var control = -1
        var ea = -1
        var k = 10
        while (k < params.size) {
            val sid = params[k].toInt() and 0xFF
            when (params[k + 1].toInt() and 0xFF) {
                0 -> control = sid
                2 -> ea = sid
            }
            k += 3
        }
        controlSid = control
        eaSid = ea
        rxSeq = seq
        hasSyn = true
        linked = false
        report("iap2_sync", "Received SYN: control session=$controlSid, external accessory session=$eaSid")
        writePacket(SYN or ACK, params)
    }

    companion object {
        const val READ_SIZE = 65536
        const val MAX_PACKET_LENGTH = 65535
        private const val HEADER_SIZE = 9
        private const val SYN = 0x80
        private const val ACK = 0x40
        private const val RST = 0x10
        private val EMPTY = ByteArray(0)
        private val DETECT = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

        fun controlMessageId(bytes: ByteArray): Int {
            if (bytes.size < 6 || bytes[0] != 0x40.toByte() || bytes[1] != 0x40.toByte() ||
                u16be(bytes, 2) != bytes.size
            ) throw IOException("Malformed iAP2 control message header")
            return u16be(bytes, 4)
        }

        fun parseParams(bytes: ByteArray, start: Int = 0): List<Pair<Int, ByteArray>> {
            if (start < 0 || start > bytes.size) throw IOException("Invalid iAP2 parameter offset")
            val out = ArrayList<Pair<Int, ByteArray>>()
            var offset = start
            while (offset < bytes.size) {
                if (bytes.size - offset < 4) throw IOException("Truncated iAP2 parameter header")
                val length = u16be(bytes, offset)
                if (length < 4 || length > bytes.size - offset) {
                    throw IOException("Invalid iAP2 parameter length")
                }
                out.add(u16be(bytes, offset + 2) to bytes.copyOfRange(offset + 4, offset + length))
                offset += length
            }
            return out
        }

        private fun u16be(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}
