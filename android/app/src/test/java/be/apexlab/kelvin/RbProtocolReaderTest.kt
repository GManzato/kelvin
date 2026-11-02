package be.apexlab.kelvin

import be.apexlab.kelvin.RbProtocolReader.Channel
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Wire-level tests for [RbProtocolReader]. Every fixture is built here with java.util.zip.CRC32 and a
 * little-endian ByteBuffer so the tests do not depend on any production encoder.
 */
class RbProtocolReaderTest {
    // ---- independent wire fixtures -------------------------------------------------------------

    private fun crc32(b: ByteArray, off: Int = 0, len: Int = b.size - off): Long {
        val c = CRC32()
        c.update(b, off, len)
        return c.value
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    /** LE uint32 fields followed by the CRC32 of those fields (the trailing header word). */
    private fun header(vararg fields: Long, badCrc: Boolean = false): ByteArray {
        val bb = ByteBuffer.allocate(4 * (fields.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
        for (f in fields) bb.putInt(f.toInt())
        val crc = crc32(bb.array(), 0, 4 * fields.size)
        bb.putInt((if (badCrc) crc xor 1L else crc).toInt())
        return bb.array()
    }

    private fun hdrSize(ch: Channel) = when (ch) { Channel.CONFIG -> 16; Channel.FRAME -> 28; Channel.FILEIO -> 24 }

    private fun configHeader(len: Long, version: Long = 1, magic: Long = 0x1cc, badCrc: Boolean = false) =
        header(magic, version, len, badCrc = badCrc)

    private fun configPacket(body: ByteArray, version: Long = 1, magic: Long = 0x1cc, badCrc: Boolean = false) =
        cat(configHeader(body.size.toLong(), version, magic, badCrc), body)

    private fun frameHeader(
        thermal: Long, visual: Long, meta: Long, total: Long = thermal + visual + meta,
        version: Long = 1, magic: Long = 0xbeef, badCrc: Boolean = false,
    ) = header(magic, version, total, thermal, visual, meta, badCrc = badCrc)

    private fun framePacket(thermal: ByteArray, visual: ByteArray, meta: ByteArray, badCrc: Boolean = false) =
        cat(frameHeader(thermal.size.toLong(), visual.size.toLong(), meta.size.toLong(), badCrc = badCrc), thermal, visual, meta)

    private fun fileHeader(
        len: Long, streamId: Long, payloadCrc: Long, version: Long = 1, magic: Long = 0x5510, badCrc: Boolean = false,
    ) = header(magic, version, len, streamId, payloadCrc, badCrc = badCrc)

    private fun filePacket(payload: ByteArray, streamId: Long, payloadCrc: Long = crc32(payload)) =
        cat(fileHeader(payload.size.toLong(), streamId, payloadCrc), payload)

    /** JSON text followed by exactly one NUL terminator. */
    private fun json(s: String) = cat(s.toByteArray(Charsets.UTF_8), byteArrayOf(0))

    private fun expectedPixel(seed: Int, y: Int, x: Int): Int = when {
        y == 0 && x == 0 -> 0x0102
        y == 0 && x == 79 -> 0x80FF
        y == 1 && x == 0 -> 0xFF01
        y == 59 && x == 79 -> 0xFEDC
        else -> (seed * 7919 + y * 257 + x * 131 + 0x8001) and 0xFFFF
    }

    /** 63 packets of 164 bytes: 4 junk bytes (id/crc words) + 80 LE pixels; packets 60..62 are telemetry filler. */
    private fun thermalKnown(seed: Int): ByteArray {
        val t = ByteArray(63 * 164)
        for (r in 0 until 63) {
            val b = r * 164
            t[b] = 0xAA.toByte(); t[b + 1] = 0x55; t[b + 2] = r.toByte(); t[b + 3] = 0xCC.toByte()
            for (x in 0 until 80) {
                val v = if (r < 60) expectedPixel(seed, r, x) else 0xFFFF
                t[b + 4 + 2 * x] = v.toByte()
                t[b + 5 + 2 * x] = (v ushr 8).toByte()
            }
        }
        return t
    }

    /** Bytes containing a NUL, a fake FRAME magic and SOI/EOI markers: it must be treated as opaque data. */
    private fun jpegLike(seed: Int) = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xEF.toByte(), 0xBE.toByte(), 0, 0, 0, seed.toByte(), 1, 2, 3,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun knownFrame(seed: Int, meta: String = "{\"seq\":$seed,\"state\":\"ok\"}") =
        framePacket(thermalKnown(seed), jpegLike(seed), json(meta))

    // ---- capture sink --------------------------------------------------------------------------

    private class Seen(
        val payloadLength: Int, val thermalLength: Int, val visualLength: Int, val metadataLength: Int,
        val streamId: Long, val json: JSONObject?, val payload: ByteArray, val frame: FlirCamera.Frame?,
    )

    /** Copies everything out of the reader inside the callback; the reader view is invalid afterwards. */
    private class Sink(channel: Channel) {
        val seen = ArrayList<Seen>()
        val reader = RbProtocolReader(channel) { r ->
            seen.add(
                Seen(
                    r.payloadLength, r.thermalLength, r.visualLength, r.metadataLength, r.streamId,
                    r.json?.let { JSONObject(it.toString()) },
                    r.copyPayload(0, r.payloadLength),
                    if (channel == Channel.FRAME) r.decodeCameraFrame() else null,
                ),
            )
        }
    }

    private fun Sink.feedChunks(bytes: ByteArray, chunk: Int) {
        var off = 0
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            reader.feed(bytes, n, off)
            off += n
        }
    }

    /** The feed must throw IOException before delivering anything and leave the reader empty. */
    private fun Sink.assertRejects(vararg chunks: ByteArray) {
        val before = seen.size
        try {
            for (c in chunks) reader.feed(c)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals("no callback for rejected data", before, seen.size)
        assertEquals("state cleared after failure", 0, reader.bufferedBytes)
    }

    /** The same reader must accept a fresh complete packet and deliver exactly one callback. */
    private fun Sink.assertRecovers(packet: ByteArray): Seen {
        val before = seen.size
        reader.feed(packet)
        assertEquals(before + 1, seen.size)
        assertEquals(0, reader.bufferedBytes)
        return seen.last()
    }

    private fun validPacket(ch: Channel): ByteArray = when (ch) {
        Channel.CONFIG -> configPacket(json("{\"ok\":1}"))
        Channel.FRAME -> knownFrame(3)
        Channel.FILEIO -> filePacket(byteArrayOf(1, 2, 3, 0, 0xFF.toByte()), 9)
    }

    // ---- CONFIG framing ------------------------------------------------------------------------

    @Test
    fun configDeliversJsonAtEverySplitPointAndByteByByte() {
        val pkt = configPacket(json("{\"event\":\"battery\",\"level\":87,\"nested\":{\"a\":[1,2,3]}}"))
        for (split in 1 until pkt.size) {
            val s = Sink(Channel.CONFIG)
            s.reader.feed(pkt, split, 0)
            assertTrue("partial packet must not be delivered (split=$split)", s.seen.isEmpty())
            s.reader.feed(pkt, pkt.size - split, split)
            assertEquals(1, s.seen.size)
            assertEquals(0, s.reader.bufferedBytes)
        }
        val s = Sink(Channel.CONFIG)
        for (i in pkt.indices) {
            assertTrue(s.seen.isEmpty())
            s.reader.feed(pkt, 1, i)
        }
        val got = s.seen.single()
        assertEquals(pkt.size - 16, got.payloadLength)
        assertEquals("battery", got.json!!.getString("event"))
        assertEquals(87, got.json!!.getInt("level"))
        assertEquals(3, got.json!!.getJSONObject("nested").getJSONArray("a").getInt(2))
        assertArrayEquals(pkt.copyOfRange(16, pkt.size), got.payload)
    }

    @Test
    fun configCoalescedPacketsIncludingZeroBodyNoticeDeliverInOrderWithPartialTail() {
        val a = configPacket(json("{\"n\":1}"))
        val notice = configHeader(0) // header-only protocol notice, legal and not a JSON error
        val b = configPacket(json("{\"n\":2}"))
        val tail = configPacket(json("{\"n\":3}"))
        val s = Sink(Channel.CONFIG)
        s.reader.feed(cat(a, notice, b, tail.copyOf(5)))
        assertEquals(3, s.seen.size)
        assertEquals(1, s.seen[0].json!!.getInt("n"))
        assertEquals(0, s.seen[1].payloadLength)
        assertNull("zero-body notice has no JSON", s.seen[1].json)
        assertEquals(0, s.seen[1].payload.size)
        assertEquals(2, s.seen[2].json!!.getInt("n"))
        assertTrue(s.reader.bufferedBytes in 1..5)

        s.reader.feed(tail, tail.size - 5, 5)
        assertEquals(4, s.seen.size)
        assertEquals(3, s.seen[3].json!!.getInt("n"))
        assertEquals(0, s.reader.bufferedBytes)
    }

    @Test
    fun configZeroBodyNoticeSplitInsideHeaderStillDelivers() {
        val notice = configHeader(0)
        for (split in 1 until notice.size) {
            val s = Sink(Channel.CONFIG)
            s.reader.feed(notice, split, 0)
            assertTrue(s.seen.isEmpty())
            s.reader.feed(notice, notice.size - split, split)
            assertEquals(1, s.seen.size)
            assertNull(s.seen[0].json)
        }
    }

    @Test
    fun configAndFileIoBadMagicFailsInsteadOfResynchronising() {
        // The wrong-channel header is otherwise perfectly valid (correct CRC); it must still be rejected, and
        // a good packet right behind it must not be found by scanning.
        val cfg = Sink(Channel.CONFIG)
        cfg.assertRejects(cat(configHeader(0, magic = 0xbeefL), validPacket(Channel.CONFIG)))
        cfg.assertRecovers(validPacket(Channel.CONFIG))

        val io = Sink(Channel.FILEIO)
        io.assertRejects(cat(fileHeader(0, 1, 0, magic = 0x1ccL), validPacket(Channel.FILEIO)))
        io.assertRecovers(validPacket(Channel.FILEIO))

        // Leading junk shifts a real packet away from offset 0: also a hard failure, not a resync.
        val shifted = Sink(Channel.CONFIG)
        shifted.assertRejects(cat(ByteArray(3) { 0x55 }, validPacket(Channel.CONFIG)))
        shifted.assertRecovers(validPacket(Channel.CONFIG))
    }

    @Test
    fun everyHeaderByteIsCrcProtectedAndRejectedBeforeAnyBodyArrives() {
        for (ch in Channel.values()) {
            val size = hdrSize(ch)
            assertEquals(size, ch.headerSize)
            val s = Sink(ch)
            val good = validPacket(ch)
            // FRAME treats a damaged magic as stream garbage, so only its non-magic bytes are CRC-checked.
            val first = if (ch == Channel.FRAME) 4 else 0
            for (i in first until size) {
                val bad = good.copyOf(size) // header only: the body never arrives
                bad[i] = (bad[i].toInt() xor 0x10).toByte()
                s.assertRejects(bad)
                s.assertRecovers(good)
            }
        }
    }

    @Test
    fun unsupportedVersionWithValidCrcIsRejectedOnEveryChannelBeforeBody() {
        for (version in longArrayOf(0, 2, 0xFFFFFFFFL)) {
            val headers = mapOf(
                Channel.CONFIG to configHeader(8, version = version),
                Channel.FRAME to frameHeader(9840, 0, 3, version = version),
                Channel.FILEIO to fileHeader(0, 1, 0, version = version),
            )
            for ((ch, h) in headers) {
                val s = Sink(ch)
                s.assertRejects(h)
                s.assertRecovers(validPacket(ch))
            }
        }
    }

    @Test
    fun payloadLengthIsUnsignedAndCappedPerChannelBeforeWaitingForBody() {
        for (len in longArrayOf(0x80000000L, 0xFFFFFFFFL)) {
            Sink(Channel.CONFIG).assertRejects(configHeader(len))
            Sink(Channel.FILEIO).assertRejects(fileHeader(len, 1, 0))
            Sink(Channel.FRAME).assertRejects(frameHeader(len, 0, 3, total = len + 3))
        }
        // One past each channel's maximum is rejected from the header alone.
        val cfgMax = Channel.CONFIG.maxPayload.toLong()
        val frameMax = Channel.FRAME.maxPayload.toLong()
        val ioMax = Channel.FILEIO.maxPayload.toLong()
        val cfg = Sink(Channel.CONFIG)
        cfg.assertRejects(configHeader(cfgMax + 1))
        val frame = Sink(Channel.FRAME)
        frame.assertRejects(frameHeader(frameMax + 1 - 3, 0, 3))
        val io = Sink(Channel.FILEIO)
        io.assertRejects(fileHeader(ioMax + 1, 1, 0))
        // Exactly at the cap the header is acceptable and the reader just waits for the body.
        cfg.reader.feed(configHeader(cfgMax))
        frame.reader.feed(frameHeader(frameMax - 3, 0, 3))
        io.reader.feed(fileHeader(ioMax, 1, 0))
        for (s in listOf(cfg, frame, io)) {
            assertTrue(s.seen.isEmpty())
            s.reader.clear()
            assertEquals(0, s.reader.bufferedBytes)
        }
        // Documented per-channel caps: the fixtures above would be meaningless otherwise.
        assertEquals(1048576, Channel.CONFIG.maxPayload)
        assertEquals(4000000, Channel.FRAME.maxPayload)
        assertEquals(131072, Channel.FILEIO.maxPayload)
    }

    @Test
    fun configAndFrameRejectMissingNulInvalidUtf8AndMalformedJsonWithoutLeakingContent() {
        val bad = listOf(
            "{\"secret\":\"hunter2\"}".toByteArray(),                    // no NUL terminator
            json("{\"secret\":\"hunter2\",}"),                           // trailing comma
            "{\"secret\":\"hunter2\"}\u0000\u0000".toByteArray(),        // second NUL
            json("{\"se\u0000cret\":\"hunter2\"}"),                      // embedded NUL
            json("{\"secret\":\"hunter2\"} x"),                          // trailing garbage
            json("[\"hunter2\"]"),                                       // not an object
            cat("{\"secret\":\"hunter2\u00FF\"}".toByteArray(Charsets.ISO_8859_1), byteArrayOf(0)), // invalid UTF-8
            json("{\"secret\":hunter2}"),                                // unquoted token
        )
        for (ch in listOf(Channel.CONFIG, Channel.FRAME)) {
            val s = Sink(ch)
            for (body in bad) {
                val pkt = if (ch == Channel.CONFIG) configPacket(body)
                else framePacket(ByteArray(9840), ByteArray(0), body)
                val before = s.seen.size
                try {
                    s.reader.feed(pkt)
                    fail("expected IOException for $ch body")
                } catch (e: IOException) {
                    assertFalse(e.message.orEmpty().contains("hunter2"))
                    assertFalse(e.cause?.message.orEmpty().contains("hunter2"))
                }
                assertEquals(before, s.seen.size)
                assertEquals(0, s.reader.bufferedBytes)
                s.assertRecovers(validPacket(ch))
            }
        }
    }

    // ---- FRAME framing and decode --------------------------------------------------------------

    @Test
    fun frameDecodesKnownThermalLayoutRowBoundariesJpegAndMetadata() {
        val seed = 5
        val pkt = knownFrame(seed)
        val s = Sink(Channel.FRAME)
        s.feedChunks(pkt, 1000)
        val got = s.seen.single()
        assertEquals(63 * 164, got.thermalLength)
        assertEquals(jpegLike(seed).size, got.visualLength)
        assertEquals(got.thermalLength + got.visualLength + got.metadataLength, got.payloadLength)
        assertEquals(pkt.size - 28, got.payloadLength)

        val f = got.frame!!
        assertEquals(63 * 164, f.thermalBytes)
        val raw = f.raw!!
        assertEquals(80 * 60, raw.size)
        // Hand-picked row boundaries: first/last pixel of row 0, first pixel of row 1, last pixel of last row.
        assertEquals(0x0102, raw[0])
        assertEquals(0x80FF, raw[79])      // high bit set: no sign extension
        assertEquals(0xFF01, raw[80])
        assertEquals(0xFEDC, raw[59 * 80 + 79])
        for (y in 0 until 60) for (x in 0 until 80) {
            assertEquals("pixel y=$y x=$x", expectedPixel(seed, y, x), raw[y * 80 + x])
        }
        assertArrayEquals(jpegLike(seed), f.jpeg)
        assertEquals(seed, f.status!!.getInt("seq"))
        assertEquals("ok", f.status!!.getString("state"))
        assertEquals(seed, got.json!!.getInt("seq"))
    }

    @Test
    fun framePayloadSlicesAndLittleEndianReadsAlignWithSectionBoundaries() {
        val thermal = thermalKnown(2)
        val jpeg = jpegLike(2)
        val meta = json("{\"m\":true}")
        val pkt = framePacket(thermal, jpeg, meta)
        var checked = false
        val reader = RbProtocolReader(Channel.FRAME) { r ->
            assertEquals(thermal.size, r.thermalLength)
            assertEquals(jpeg.size, r.visualLength)
            assertEquals(meta.size, r.metadataLength)
            assertArrayEquals(thermal.copyOfRange(164 * 59 + 4, 164 * 59 + 8), r.copyPayload(164 * 59 + 4, 4))
            assertArrayEquals(jpeg, r.copyPayload(r.thermalLength, r.visualLength))
            assertArrayEquals(meta, r.copyPayload(r.thermalLength + r.visualLength, r.metadataLength))
            assertEquals(0, r.copyPayload(r.payloadLength, 0).size)
            assertEquals(0x0102, r.u16lePayload(4))                       // row 0 pixel 0
            assertEquals(0x80FF, r.u16lePayload(4 + 2 * 79))              // row 0 pixel 79
            assertEquals(0xFF01, r.u16lePayload(164 + 4))                 // row 1 pixel 0
            assertEquals(0x55AA, r.u16lePayload(0))                       // packet id word bytes AA 55
            assertEquals(0xCC00, r.u16lePayload(2))                       // bytes 00 CC
            assertEquals(0xFF or (0xD8 shl 8), r.u16lePayload(r.thermalLength)) // first JPEG bytes FF D8
            checked = true
        }
        reader.feed(pkt)
        assertTrue(checked)
    }

    @Test
    fun frameWithEmptyJpegAndUnknownThermalLayoutKeepsRawNullAndStatus() {
        for (thermalLen in intArrayOf(9840, 20000, 38400, 10331, 10333)) {
            val thermal = ByteArray(thermalLen) { (it * 31).toByte() }
            val s = Sink(Channel.FRAME)
            s.reader.feed(framePacket(thermal, ByteArray(0), json("{\"layout\":$thermalLen}")))
            val f = s.seen.single().frame!!
            assertNull("layout $thermalLen is not the 80x60 layout", f.raw)
            assertEquals(thermalLen, f.thermalBytes)
            assertEquals(0, f.jpeg.size)
            assertEquals(thermalLen, f.status!!.getInt("layout"))
            assertEquals(0, s.seen.single().visualLength)
        }
    }

    @Test
    fun frameTotalsMinimaAndMetadataLimitAreEnforcedFromTheHeaderAlone() {
        val s = Sink(Channel.FRAME)
        // total disagrees with the section sum, both directions
        s.assertRejects(frameHeader(9840, 10, 3, total = 9840 + 10 + 3 + 1))
        s.assertRejects(frameHeader(9840, 10, 3, total = 9840 + 10 + 3 - 1))
        // minimum thermal block is 60 Lepton rows (60*164)
        s.assertRejects(frameHeader(9839, 0, 3))
        // minimum metadata is "{}" plus NUL
        s.assertRejects(frameHeader(9840, 0, 2))
        s.assertRejects(frameHeader(9840, 100, 0))
        // metadata above the shared JSON limit
        s.assertRejects(frameHeader(9840, 0, RbJson.MAX_BYTES.toLong() + 1))
        // the same sums just inside every boundary only wait for the body
        for (h in listOf(
            frameHeader(9840, 0, 3),
            frameHeader(9840, 0, RbJson.MAX_BYTES.toLong()),
            frameHeader(9840, 1, 3),
        )) {
            s.reader.feed(h)
            assertTrue(s.seen.isEmpty())
            s.reader.clear()
        }
        s.assertRecovers(validPacket(Channel.FRAME))
    }

    @Test
    fun frameLengthSumIsComputedWithoutUint32Wraparound() {
        // thermal + visual + metadata wraps to exactly `total` in 32-bit arithmetic but not in 64-bit.
        val h = frameHeader(0xFFFFFFFFL, 10333, 3, total = 10335)
        val s = Sink(Channel.FRAME)
        s.assertRejects(h)
        s.assertRecovers(validPacket(Channel.FRAME))
    }

    @Test
    fun frameTruncatedBodyWaitsAndPauseClearDropsTheIncompleteOldFrame() {
        val old = knownFrame(1)
        val fresh = knownFrame(2, "{\"seq\":2}")
        val s = Sink(Channel.FRAME)
        s.reader.feed(old, old.size - 10, 0)
        assertTrue(s.seen.isEmpty())
        assertTrue(s.reader.bufferedBytes > 0)

        s.reader.clear() // pause boundary
        assertEquals(0, s.reader.bufferedBytes)
        // The old frame's tail now looks like garbage and must not complete the dropped frame.
        s.reader.feed(old, 10, old.size - 10)
        assertTrue(s.seen.isEmpty())

        s.reader.feed(fresh)
        val got = s.seen.single()
        assertEquals(2, got.json!!.getInt("seq"))
        assertEquals(expectedPixel(2, 30, 40), got.frame!!.raw!![30 * 80 + 40])
        assertEquals(0, s.reader.bufferedBytes)

        // Same for CONFIG: an incomplete packet is dropped, not completed by later bytes.
        val c = Sink(Channel.CONFIG)
        val cfg = configPacket(json("{\"dropped\":true}"))
        c.reader.feed(cfg, cfg.size - 3, 0)
        c.reader.clear()
        assertEquals(0, c.reader.bufferedBytes)
        c.assertRecovers(configPacket(json("{\"kept\":true}")))
        assertTrue(c.seen.single().json!!.getBoolean("kept"))
    }

    @Test
    fun frameCoalescedWithGarbageDeliversEachFrameInOrderAndDecodedValuesAreImmutable() {
        val a = knownFrame(10)
        val b = knownFrame(11)
        val junk1 = ByteArray(37) { 0x11 }
        val junk2 = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0x00, 0x22, 0xEF.toByte()) // partial magics only
        val junk3 = ByteArray(300) { 0x33 }
        val s = Sink(Channel.FRAME)
        s.reader.feed(cat(junk1, a, junk2, b, junk3))
        assertEquals(2, s.seen.size)
        assertTrue(s.reader.bufferedBytes <= 3)

        // Later traffic and clear() must not mutate what the callbacks captured.
        s.reader.feed(knownFrame(12))
        s.reader.clear()
        for ((i, seed) in intArrayOf(10, 11, 12).withIndex()) {
            val f = s.seen[i].frame!!
            assertEquals(seed, f.status!!.getInt("seq"))
            assertEquals(expectedPixel(seed, 0, 1), f.raw!![1])
            assertEquals(expectedPixel(seed, 59, 0), f.raw!![59 * 80])
            assertArrayEquals(jpegLike(seed), f.jpeg)
        }
    }

    @Test
    fun frameGarbageStaysBoundedAndLinearAndMagicSplitAcrossFeedsIsFound() {
        val pathological = ByteArray(RbProtocolReader.MAX_CHUNK_BYTES) {
            when (it % 3) { 0 -> 0xEF.toByte(); 1 -> 0xBE.toByte(); else -> 0.toByte() } // EF BE 00 EF BE 00 ...: never a full magic
        }
        val plain = ByteArray(RbProtocolReader.MAX_CHUNK_BYTES) { 0x11 }
        val s = Sink(Channel.FRAME)
        repeat(24) { i ->
            s.reader.feed(if (i % 2 == 0) pathological else plain)
            assertTrue("garbage retained at most a partial magic, was ${s.reader.bufferedBytes}", s.reader.bufferedBytes <= 3)
        }
        assertTrue(s.seen.isEmpty())

        // A real frame right behind that garbage is still found, with the magic split at each header position.
        val frame = knownFrame(4)
        for (split in 1..40) {
            val t = Sink(Channel.FRAME)
            t.reader.feed(cat(ByteArray(50) { 0x11 }, frame.copyOf(split)))
            assertTrue(t.seen.isEmpty())
            t.reader.feed(frame, frame.size - split, split)
            assertEquals("split=$split", 1, t.seen.size)
            assertEquals(4, t.seen[0].frame!!.status!!.getInt("seq"))
        }
        s.reader.feed(frame)
        assertEquals(1, s.seen.size)
    }

    @Test
    fun frameCandidateWithBadHeaderAfterGarbageFailsClosedWithoutScanningItsBody() {
        val embedded = knownFrame(7)
        // Header CRC is wrong; the "body" contains a complete, valid frame that must never be delivered.
        val corrupt = cat(
            frameHeader(embedded.size.toLong() - 3, 0, 3, badCrc = true), embedded,
        )
        val s = Sink(Channel.FRAME)
        s.assertRejects(cat(ByteArray(11) { 0x44 }, corrupt))
        s.assertRecovers(knownFrame(8))
        assertEquals(8, s.seen.single().json!!.getInt("seq"))

        // Same for a valid-CRC header whose sizes are inconsistent.
        val inconsistent = cat(frameHeader(9840, 0, 3, total = 12000), embedded)
        s.assertRejects(inconsistent)
        s.assertRecovers(knownFrame(9))
    }

    // ---- FILEIO framing ------------------------------------------------------------------------

    @Test
    fun fileIoDeliversBinaryPayloadStreamIdAndEofWhenCrcsMatch() {
        val payload = ByteArray(5000) { ((it * 37) xor (it ushr 3)).toByte() }
        payload[10] = 0x34; payload[11] = 0x92.toByte(); payload[12] = 0xFF.toByte()
        payload[20] = 0; payload[21] = 0 // raw binary: NULs are fine
        val data = filePacket(payload, 0xFFFFFFFFL)
        val eof = filePacket(ByteArray(0), 3, payloadCrc = 0)
        val s = Sink(Channel.FILEIO)
        s.feedChunks(cat(data, eof), 97)

        assertEquals(2, s.seen.size)
        assertEquals(payload.size, s.seen[0].payloadLength)
        assertEquals("streamId is an unsigned uint32", 0xFFFFFFFFL, s.seen[0].streamId)
        assertArrayEquals(payload, s.seen[0].payload)
        assertNull(s.seen[0].json)
        assertEquals(0, s.seen[1].payloadLength)
        assertEquals(3L, s.seen[1].streamId)
        assertEquals(0, s.seen[1].payload.size)
        assertEquals(0, s.reader.bufferedBytes)

        var le = -1
        val r = RbProtocolReader(Channel.FILEIO) { p -> le = p.u16lePayload(10) }
        r.feed(data)
        assertEquals(0x9234, le)
    }

    @Test
    fun fileIoRejectsBadStreamIdVersionAndHeaderCrcBeforePayload() {
        val s = Sink(Channel.FILEIO)
        s.assertRejects(fileHeader(4, 0, crc32(byteArrayOf(1, 2, 3, 4))))             // streamId must be >= 1
        s.assertRejects(fileHeader(4, 1, crc32(byteArrayOf(1, 2, 3, 4)), badCrc = true))
        s.assertRejects(fileHeader(4, 1, crc32(byteArrayOf(1, 2, 3, 4)), version = 2))
        s.assertRecovers(validPacket(Channel.FILEIO))
    }

    @Test
    fun fileIoPayloadCrcMismatchIsRejectedOnlyOnceCompleteAndNothingCorruptIsDelivered() {
        val payload = ByteArray(300) { it.toByte() }
        val good = filePacket(payload, 2)
        val corrupt = good.copyOf()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 1).toByte()

        val s = Sink(Channel.FILEIO)
        s.reader.feed(corrupt, corrupt.size - 1, 0) // header and all but the last byte: still waiting
        assertTrue(s.seen.isEmpty())
        try {
            s.reader.feed(corrupt, 1, corrupt.size - 1)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue("corrupt payload bytes must never reach the callback", s.seen.isEmpty())
        assertEquals(0, s.reader.bufferedBytes)

        // Corrupt first byte, wrong CRC word, in one coalesced feed ahead of a good packet.
        val firstByte = good.copyOf().also { it[24] = (it[24].toInt() xor 0x80).toByte() }
        s.assertRejects(cat(firstByte, good))
        assertEquals(payload.size, s.assertRecovers(good).payloadLength)
        assertArrayEquals(payload, s.seen.last().payload)
    }

    @Test
    fun fileIoEofMustCarryZeroChecksum() {
        val s = Sink(Channel.FILEIO)
        s.assertRejects(fileHeader(0, 1, 1)) // len 0 with a non-zero CRC
        s.assertRejects(fileHeader(0, 1, 0xFFFFFFFFL))
        val eof = s.assertRecovers(fileHeader(0, 1, 0))
        assertEquals(0, eof.payloadLength)
    }

    @Test
    fun fileIoPayloadAtCapIsDeliveredInMaxSizedChunksAndOneOverIsRejected() {
        val max = Channel.FILEIO.maxPayload
        val payload = ByteArray(max) { (it * 13).toByte() }
        val s = Sink(Channel.FILEIO)
        s.feedChunks(filePacket(payload, 1), RbProtocolReader.MAX_CHUNK_BYTES)
        assertEquals(max, s.seen.single().payloadLength)
        assertArrayEquals(payload, s.seen.single().payload)
        assertEquals(0, s.reader.bufferedBytes)

        s.assertRejects(fileHeader(max + 1L, 1, 0))
    }

    // ---- feed argument handling ----------------------------------------------------------------

    @Test
    fun feedUsesTheRequestedSourceSliceAndNeverTouchesGuardBytes() {
        val pkt = configPacket(json("{\"slice\":42}"))
        // Guard bytes would be rejected as a bad magic if the reader looked at them.
        val buf = cat(ByteArray(9) { 0xEE.toByte() }, pkt, ByteArray(11) { 0xDD.toByte() })
        val s = Sink(Channel.CONFIG)
        s.reader.feed(buf, pkt.size, 9)
        assertEquals(42, s.seen.single().json!!.getInt("slice"))
        assertEquals(0, s.reader.bufferedBytes)

        // Two sub-slices of the same array, and a leading zero-length slice.
        val t = Sink(Channel.CONFIG)
        t.reader.feed(buf, 0, 3)
        t.reader.feed(buf, 5, 9)
        assertTrue(t.seen.isEmpty())
        t.reader.feed(buf, pkt.size - 5, 14)
        assertEquals(42, t.seen.single().json!!.getInt("slice"))
        assertEquals(0, t.reader.bufferedBytes)

        // len default is the whole array, offset default 0
        val u = Sink(Channel.CONFIG)
        u.reader.feed(pkt)
        assertEquals(1, u.seen.size)

        // A FRAME slice behaves the same way.
        val frame = knownFrame(6)
        val fbuf = cat(ByteArray(5) { 0xEF.toByte() }, frame, ByteArray(4) { 0xEF.toByte() })
        val f = Sink(Channel.FRAME)
        f.reader.feed(fbuf, frame.size, 5)
        assertEquals(6, f.seen.single().frame!!.status!!.getInt("seq"))
        assertEquals(0, f.reader.bufferedBytes)
    }

    @Test
    fun feedRejectsInvalidOffsetsLengthsAndOversizedChunksWithoutDeliveringAndStaysUsable() {
        val s = Sink(Channel.CONFIG)
        val arr = ByteArray(RbProtocolReader.MAX_CHUNK_BYTES + 64)
        val attempts = listOf<() -> Unit>(
            { s.reader.feed(arr, 10, arr.size - 5) },                    // runs past the end
            { s.reader.feed(arr, -1, 0) },                               // negative length
            { s.reader.feed(arr, 4, -1) },                               // negative offset
            { s.reader.feed(arr, arr.size + 1, 0) },                     // length beyond the array
            { s.reader.feed(arr, RbProtocolReader.MAX_CHUNK_BYTES + 1, 0) }, // above max read size
        )
        for ((i, attempt) in attempts.withIndex()) {
            try {
                attempt()
                fail("attempt $i must be rejected")
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: IndexOutOfBoundsException) {
            }
            assertTrue(s.seen.isEmpty())
            s.reader.clear()
        }
        // A read of exactly MAX_CHUNK_BYTES is fine on a stream channel that treats it as garbage.
        val f = Sink(Channel.FRAME)
        f.reader.feed(arr, RbProtocolReader.MAX_CHUNK_BYTES, 0)
        assertTrue(f.reader.bufferedBytes <= 3)
        f.assertRecovers(knownFrame(1))
        s.assertRecovers(validPacket(Channel.CONFIG))
    }
}
