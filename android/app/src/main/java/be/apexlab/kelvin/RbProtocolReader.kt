package be.apexlab.kelvin

import org.json.JSONObject
import java.io.IOException
import java.util.zip.CRC32

/**
 * Thread-confined RBProtocol receiver. Packet fields/slices are valid only during [onPacket].
 * Frame streams can start between packets (e.g. after pause); only that channel searches for magic.
 * Once a candidate header is complete, any integrity/format error fails closed and clears state.
 */
internal class RbProtocolReader(
    private val channel: Channel,
    private val onPacket: (RbProtocolReader) -> Unit,
) {
    enum class Channel(val magic: Long, val headerSize: Int, val maxPayload: Int) {
        CONFIG(0x1CC, 16, 1 shl 20),
        FRAME(0xBEEF, 28, 4_000_000),
        FILEIO(0x5510, 24, 1 shl 17),
    }

    private val buffer = ByteFifo(if (channel == Channel.FRAME) 1 shl 20 else 4096)
    private val crc = CRC32()
    private val jsonDecoder = if (channel == Channel.FILEIO) null else RbJson()
    private var payloadStart = 0
    private var packetActive = false
    val bufferedBytes: Int get() = buffer.size
    var payloadLength = 0
        private set
    var thermalLength = 0
        private set
    var visualLength = 0
        private set
    var metadataLength = 0
        private set
    var streamId = 0L
        private set
    var json: JSONObject? = null
        private set

    fun clear() {
        buffer.clear()
        packetActive = false
        json = null
    }

    fun feed(bytes: ByteArray, len: Int = bytes.size, offset: Int = 0) {
        if (offset < 0 || len < 0 || len > bytes.size - offset || len > MAX_CHUNK_BYTES) {
            clear()
            throw IOException("Invalid RBProtocol input chunk")
        }
        if (len > channel.maxPayload + channel.headerSize + MAX_CHUNK_BYTES - buffer.size) {
            clear()
            throw IOException("RBProtocol receive buffer limit exceeded")
        }
        buffer.append(bytes, len, offset)
        var consumed = 0
        try {
            while (consumed < buffer.size) {
                if (channel == Channel.FRAME) {
                    while (buffer.size - consumed >= 4 && buffer.u32le(consumed) != channel.magic) consumed++
                }
                val remaining = buffer.size - consumed
                if (remaining < 4) break
                if (buffer.u32le(consumed) != channel.magic) throw IOException("Invalid RBProtocol channel magic")
                if (remaining < channel.headerSize) break
                val checksumOffset = channel.headerSize - 4
                if (checksum(consumed, checksumOffset) != buffer.u32le(consumed + checksumOffset)) {
                    throw IOException("RBProtocol header CRC mismatch")
                }
                if (buffer.u32le(consumed + 4) != 1L) throw IOException("Unsupported RBProtocol version")
                val total = buffer.u32le(consumed + 8)
                if (total > channel.maxPayload) throw IOException("RBProtocol payload length exceeds limit")
                var thermal = 0L
                var visual = 0L
                var metadata = 0L
                var descriptor = 0L
                if (channel == Channel.FRAME) {
                    thermal = buffer.u32le(consumed + 12)
                    visual = buffer.u32le(consumed + 16)
                    metadata = buffer.u32le(consumed + 20)
                    if (thermal < MIN_THERMAL_BYTES || metadata < 3 || metadata > RbJson.MAX_BYTES ||
                        thermal + visual + metadata != total
                    ) throw IOException("Invalid RBProtocol frame payload lengths")
                } else if (channel == Channel.FILEIO) {
                    descriptor = buffer.u32le(consumed + 12)
                    if (descriptor == 0L) throw IOException("Invalid RBProtocol file stream identifier")
                    if (total == 0L && buffer.u32le(consumed + 16) != 0L) {
                        throw IOException("Invalid RBProtocol file EOF checksum")
                    }
                }
                val bodySize = total.toInt() // only after the unsigned length has passed the cap
                val packetSize = channel.headerSize + bodySize
                if (remaining < packetSize) break
                val bodyStart = consumed + channel.headerSize
                if (channel == Channel.FILEIO && checksum(bodyStart, bodySize) != buffer.u32le(consumed + 16)) {
                    throw IOException("RBProtocol file payload CRC mismatch")
                }
                // Parse metadata before allocating/decoding thermal samples or invoking consumers.
                val parsedJson = when (channel) {
                    Channel.CONFIG -> if (bodySize == 0) null else jsonDecoder!!.decode(buffer.copy(bodyStart, bodySize))
                    Channel.FRAME -> jsonDecoder!!.decode(buffer.copy(bodyStart + thermal.toInt() + visual.toInt(), metadata.toInt()))
                    Channel.FILEIO -> null
                }
                payloadStart = bodyStart
                payloadLength = bodySize
                thermalLength = thermal.toInt()
                visualLength = visual.toInt()
                metadataLength = metadata.toInt()
                streamId = descriptor
                json = parsedJson
                consumed += packetSize
                packetActive = true
                try {
                    onPacket(this)
                } finally {
                    packetActive = false
                    json = null
                }
            }
        } catch (e: IOException) {
            clear()
            throw e
        } finally {
            buffer.drop(consumed) // single compaction, including garbage scans and error paths
        }
    }

    fun copyPayload(offset: Int, len: Int): ByteArray {
        checkSlice(offset, len)
        return buffer.copy(payloadStart + offset, len)
    }

    fun u16lePayload(offset: Int): Int {
        checkSlice(offset, 2)
        return buffer[payloadStart + offset] or (buffer[payloadStart + offset + 1] shl 8)
    }

    fun decodeCameraFrame(): FlirCamera.Frame {
        check(packetActive && channel == Channel.FRAME)
        val raw = if (thermalLength == KNOWN_THERMAL_BYTES) IntArray(FlirCamera.W * FlirCamera.H).also { raw ->
            // Existing verified layout: 60 image rows, then 3 telemetry packets; no new models.
            for (y in 0 until FlirCamera.H) {
                val row = payloadStart + y * 164 + 4
                for (x in 0 until FlirCamera.W) {
                    raw[y * FlirCamera.W + x] = buffer[row + 2 * x] or (buffer[row + 2 * x + 1] shl 8)
                }
            }
        } else null
        return FlirCamera.Frame(raw, thermalLength, copyPayload(thermalLength, visualLength), checkNotNull(json))
    }

    private fun checkSlice(offset: Int, len: Int) {
        check(packetActive) { "RBProtocol packet view is no longer active" }
        if (offset < 0 || len < 0 || offset > payloadLength - len) throw IndexOutOfBoundsException("Invalid RBProtocol payload slice")
    }

    private fun checksum(offset: Int, len: Int): Long {
        crc.reset()
        buffer.updateChecksum(crc, offset, len)
        return crc.value
    }

    companion object {
        const val MAX_CHUNK_BYTES = 1 shl 17
        private const val MIN_THERMAL_BYTES = 60 * 164L
        private const val KNOWN_THERMAL_BYTES = 63 * 164
    }
}
