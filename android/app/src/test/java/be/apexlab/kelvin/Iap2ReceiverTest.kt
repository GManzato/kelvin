package be.apexlab.kelvin

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [Iap2Receiver]. Every wire fixture is built here, independently of the
 * production code, and the assertions are on what the receiver actually delivers, which ACK /
 * SYN|ACK packets it asks the link to write, and which state it exposes.
 *
 * Wire format: FF 5A, u16be total length, control, seq, ack, session, header checksum (the first
 * nine bytes sum to 0 mod 256); a payload (when present) is followed by its own checksum byte
 * (payload plus checksum sum to 0 mod 256). Length 9 = empty, 10 = illegal, 11..65535 = data.
 */
class Iap2ReceiverTest {
    // ---- independent wire fixtures ------------------------------------------------------------

    private companion object {
        const val SYN = 0x80
        const val ACK = 0x40
        const val RST = 0x10
        val EMPTY = ByteArray(0)
        val DETECT = b(0xFF, 0x55, 0x02, 0x00, 0xEE, 0x10)

        /** Ten link-parameter bytes preceding the session triples in a SYN payload. */
        val LSP = b(0x01, 0x05, 0x10, 0x00, 0x08, 0x00, 0x10, 0x00, 0x01, 0x00)

        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        fun csum(a: ByteArray, off: Int = 0, len: Int = a.size - off): Int {
            var s = 0
            for (k in off until off + len) s += a[k].toInt() and 0xFF
            return (-s) and 0xFF
        }

        /** Deterministic payload that never contains 0xFF (so it cannot fake a marker). */
        fun payload(n: Int, seed: Int = 1) = ByteArray(n) { (((it * 7 + seed) % 0xF0) + 1).toByte() }

        /**
         * Frame with explicit declared [length] and raw [body] (the bytes after the 9-byte
         * header, checksum included, or nothing for header-only frames).
         */
        fun raw(
            length: Int,
            control: Int,
            seq: Int,
            body: ByteArray = EMPTY,
            session: Int = 0,
            ack: Int = 0,
            badHeader: Boolean = false,
        ): ByteArray {
            val h = ByteArray(9)
            h[0] = 0xFF.toByte(); h[1] = 0x5A
            h[2] = (length shr 8).toByte(); h[3] = length.toByte()
            h[4] = control.toByte(); h[5] = seq.toByte(); h[6] = ack.toByte(); h[7] = session.toByte()
            h[8] = (csum(h, 0, 8) xor if (badHeader) 0x01 else 0).toByte()
            return h + body
        }

        fun frame(
            control: Int,
            seq: Int,
            payload: ByteArray = EMPTY,
            session: Int = 0,
            badHeader: Boolean = false,
            badPayload: Boolean = false,
        ): ByteArray {
            if (payload.isEmpty()) return raw(9, control, seq, EMPTY, session, 0, badHeader)
            val sum = (csum(payload) + if (badPayload) 1 else 0) and 0xFF
            return raw(9 + payload.size + 1, control, seq, payload + b(sum), session, 0, badHeader)
        }

        fun synPayload(vararg sessions: Triple<Int, Int, Int>): ByteArray {
            var p = LSP
            for ((id, type, ver) in sessions) p += b(id, type, ver)
            return p
        }

        fun tlv(id: Int, body: ByteArray = EMPTY) = rawTlv(body.size + 4, id, body)

        fun rawTlv(len: Int, id: Int, body: ByteArray = EMPTY) =
            b(len shr 8, len and 0xFF, id shr 8, id and 0xFF) + body

        fun csm(id: Int, params: ByteArray = EMPTY, declared: Int = 6 + params.size) =
            b(0x40, 0x40, declared shr 8, declared and 0xFF, id shr 8, id and 0xFF) + params

        fun hex(a: ByteArray) = a.joinToString("") { "%02x".format(it) }
    }

    private class Harness {
        val packets = mutableListOf<Pair<Int, ByteArray>>()
        val raws = mutableListOf<ByteArray>()
        val reports = mutableListOf<Pair<String, String>>()
        val rx = Iap2Receiver(
            { control, payload -> packets.add(control to payload) },
            { raws.add(it) },
            { kind, message -> reports.add(kind to message) },
        )

        fun poll(bytes: ByteArray) = rx.poll(bytes, bytes.size)

        /** Feeds a SYN carrying control session 0x0A and EA session 0x0B, then forgets the replies. */
        fun sync(seq: Int = 0x10): Harness {
            poll(frame(SYN, seq, synPayload(Triple(0x0A, 0, 1), Triple(0x0B, 2, 1))))
            packets.clear(); reports.clear(); raws.clear()
            return this
        }
    }

    private fun assertIo(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("expected IOException")
    }

    private fun ignoringIo(block: () -> Unit) {
        try { block() } catch (e: IOException) { }
    }

    private fun assertDelivered(expected: List<Pair<Int, ByteArray>>, actual: List<Pair<Int, ByteArray>>) {
        assertEquals("delivery count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("session #$i", expected[i].first, actual[i].first)
            assertArrayEquals("payload #$i", expected[i].second, actual[i].second)
        }
    }

    private fun assertAcks(h: Harness, count: Int) {
        assertEquals("ACK count", count, h.packets.size)
        for (p in h.packets) {
            assertEquals(ACK, p.first)
            assertEquals(0, p.second.size)
        }
    }

    // ---- link establishment -------------------------------------------------------------------

    @Test
    fun validSynRecordsSessionsAndAnswersSynAckWithTheSameParameters() {
        val h = Harness()
        val params = synPayload(Triple(0x0A, 0, 1), Triple(0x0B, 2, 1), Triple(0x0C, 1, 1))
        val out = h.poll(frame(SYN, 0x30, params))

        assertTrue(out.isEmpty())
        assertEquals(1, h.packets.size)
        assertEquals(SYN or ACK, h.packets[0].first)
        assertArrayEquals(params, h.packets[0].second)
        assertEquals(0x30, h.rx.rxSeq)
        assertEquals(0x0A, h.rx.controlSid)
        assertEquals(0x0B, h.rx.eaSid)
        assertFalse("SYN alone must not complete the link", h.rx.linked)
        assertTrue(h.raws.isEmpty())
        assertTrue(h.reports.any { it.first == "iap2_sync" })
        assertEquals(0, h.rx.bufferedBytes)
    }

    @Test
    fun ackLinksOnlyAfterAValidSynAndNeedsNoReply() {
        val early = Harness()
        early.poll(frame(ACK, 0x11))
        assertFalse("ACK without a SYN must not link", early.rx.linked)
        assertTrue(early.packets.isEmpty())

        val h = Harness().sync(0x10)
        assertFalse(h.rx.linked)
        val out = h.poll(frame(ACK, 0x10))
        assertTrue(out.isEmpty())
        assertTrue(h.rx.linked)
        assertTrue("a bare ACK must not be ACKed back", h.packets.isEmpty())
        assertEquals(0x10, h.rx.rxSeq)

        h.poll(frame(ACK, 0x10))
        assertTrue(h.rx.linked)
        assertTrue(h.packets.isEmpty())
    }

    @Test
    fun malformedSynNeverEstablishesOrMutatesState() {
        // 10-byte header + whole 3-byte session triples is the only valid shape.
        val full = synPayload(Triple(0x21, 0, 1), Triple(0x22, 2, 1), Triple(0x23, 1, 1))
        for (n in intArrayOf(0, 5, 9, 11, 12, 14, 15)) {
            val h = Harness()
            val seqBefore = h.rx.rxSeq
            val ctlBefore = h.rx.controlSid
            val eaBefore = h.rx.eaSid
            ignoringIo { h.poll(frame(SYN, 0x44, full.copyOf(n))) }
            assertFalse("SYN of $n bytes linked", h.rx.linked)
            assertEquals("rxSeq changed by $n-byte SYN", seqBefore, h.rx.rxSeq)
            assertEquals("controlSid changed by $n-byte SYN", ctlBefore, h.rx.controlSid)
            assertEquals("eaSid changed by $n-byte SYN", eaBefore, h.rx.eaSid)
            assertTrue("SYN|ACK sent for $n-byte SYN", h.packets.isEmpty())
            // a later bare ACK must not link either: no valid SYN was ever accepted
            ignoringIo { h.poll(frame(ACK, 0x44)) }
            assertFalse(h.rx.linked)
        }

        // A SYN whose payload checksum is wrong is a discarded frame, not a link sync.
        val bad = Harness()
        val seq0 = bad.rx.rxSeq
        ignoringIo { bad.poll(frame(SYN, 0x44, synPayload(Triple(0x21, 0, 1)), badPayload = true)) }
        assertTrue(bad.packets.isEmpty())
        assertEquals(seq0, bad.rx.rxSeq)
        assertFalse(bad.rx.linked)

        // After a good SYN a malformed one leaves the accepted state untouched.
        val h = Harness().sync(0x10)
        ignoringIo { h.poll(frame(SYN, 0x77, full.copyOf(14))) }
        assertTrue(h.packets.isEmpty())
        assertEquals(0x10, h.rx.rxSeq)
        assertEquals(0x0A, h.rx.controlSid)
        assertEquals(0x0B, h.rx.eaSid)
        h.poll(frame(ACK, 0x10))
        assertTrue("the earlier valid SYN still allows linking", h.rx.linked)
    }

    // ---- data delivery ------------------------------------------------------------------------

    @Test
    fun dataIsDeliveredOnceAckedAndDuplicatesAreReAckedButNotRedelivered() {
        val h = Harness().sync(0x10)
        val p1 = payload(40, 1)
        val p2 = payload(3, 9)

        assertDelivered(listOf(7 to p1), h.poll(frame(ACK, 0x11, p1, session = 7)))
        assertEquals(0x11, h.rx.rxSeq)
        assertAcks(h, 1)

        assertTrue("retransmit must not be redelivered", h.poll(frame(ACK, 0x11, p1, session = 7)).isEmpty())
        assertEquals(0x11, h.rx.rxSeq)
        assertAcks(h, 2)

        assertDelivered(listOf(7 to p2), h.poll(frame(ACK, 0x12, p2, session = 7)))
        assertEquals(0x12, h.rx.rxSeq)
        assertAcks(h, 3)
        assertEquals(0, h.rx.bufferedBytes)
    }

    @Test
    fun sequenceNumbersWrapFromFfToZeroWithoutSignErrors() {
        val h = Harness().sync(0xFE)
        assertEquals(0xFE, h.rx.rxSeq)
        val a = payload(5, 1)
        val b = payload(6, 2)
        val c = payload(7, 3)

        assertDelivered(listOf(1 to a), h.poll(frame(ACK, 0xFF, a, session = 1)))
        assertEquals(0xFF, h.rx.rxSeq)
        assertDelivered(listOf(1 to b), h.poll(frame(ACK, 0x00, b, session = 1)))
        assertEquals(0x00, h.rx.rxSeq)
        assertTrue(h.poll(frame(ACK, 0x00, b, session = 1)).isEmpty())
        assertDelivered(listOf(1 to c), h.poll(frame(ACK, 0x01, c, session = 1)))
        assertEquals(0x01, h.rx.rxSeq)
        assertAcks(h, 4)
    }

    @Test
    fun fragmentedAndCoalescedStreamsDeliverEveryPacketInOrderWithOneAckEach() {
        val frames = listOf(
            frame(ACK, 0x11, payload(1, 1), session = 1),
            frame(ACK, 0x12, payload(300, 2), session = 2),
            frame(ACK, 0x13, payload(5000, 3), session = 3),
        )
        val expected = listOf(1 to payload(1, 1), 2 to payload(300, 2), 3 to payload(5000, 3))
        val stream = frames.reduce { a, c -> a + c }

        // one byte per poll
        val slow = Harness().sync(0x10)
        val slowOut = ArrayList<Pair<Int, ByteArray>>()
        for (i in stream.indices) {
            slowOut += slow.poll(byteArrayOf(stream[i]))
            assertTrue(slow.rx.bufferedBytes <= Iap2Receiver.MAX_PACKET_LENGTH)
        }
        assertDelivered(expected, slowOut)
        assertAcks(slow, 3)
        assertEquals(0x13, slow.rx.rxSeq)
        assertEquals(0, slow.rx.bufferedBytes)

        // everything in one read
        val fast = Harness().sync(0x10)
        assertDelivered(expected, fast.poll(stream))
        assertAcks(fast, 3)
        assertEquals(0, fast.rx.bufferedBytes)

        // arbitrary 97-byte slices straddling frame boundaries
        val sliced = Harness().sync(0x10)
        val slicedOut = ArrayList<Pair<Int, ByteArray>>()
        var off = 0
        while (off < stream.size) {
            val end = minOf(off + 97, stream.size)
            slicedOut += sliced.poll(stream.copyOfRange(off, end))
            off = end
        }
        assertDelivered(expected, slicedOut)
        assertAcks(sliced, 3)
        assertEquals(0, sliced.rx.bufferedBytes)
    }

    @Test
    fun pollHonoursLenAndIgnoresStaleBytesInTheReusedUsbBuffer() {
        val h = Harness().sync(0x10)
        val first = frame(ACK, 0x11, payload(20, 4), session = 2)
        val stale = frame(ACK, 0x12, payload(20, 5), session = 2)
        val usb = ByteArray(Iap2Receiver.READ_SIZE) { 0x33 }
        System.arraycopy(first, 0, usb, 0, first.size)
        System.arraycopy(stale, 0, usb, first.size, stale.size)

        assertDelivered(listOf(2 to payload(20, 4)), h.rx.poll(usb, first.size))
        assertEquals(0x11, h.rx.rxSeq)
        assertAcks(h, 1)
        assertEquals(0, h.rx.bufferedBytes)
    }

    // ---- DETECT marker and garbage ------------------------------------------------------------

    @Test
    fun detectMarkerSurvivesEverySplitAndIsEchoedOncePerOccurrence() {
        for (split in 1 until DETECT.size) {
            val h = Harness()
            val out1 = h.poll(b(1, 2, 3) + DETECT.copyOfRange(0, split))
            assertTrue(out1.isEmpty())
            assertTrue("partial marker must be echoed only when complete", h.raws.isEmpty())
            assertTrue(h.rx.bufferedBytes <= DETECT.size - 1)
            h.poll(DETECT.copyOfRange(split, DETECT.size))
            assertEquals("split=$split", 1, h.raws.size)
            assertArrayEquals(DETECT, h.raws[0])
            assertEquals(0, h.rx.bufferedBytes)
            assertTrue(h.packets.isEmpty())
        }

        // a lone FF in front of the marker must not hide it
        val lone = Harness()
        lone.poll(b(0xFF) + DETECT)
        assertEquals(1, lone.raws.size)
        assertArrayEquals(DETECT, lone.raws[0])

        // several markers in one read, interleaved with garbage and a frame
        val h = Harness().sync(0x10)
        val good = frame(ACK, 0x11, payload(9, 2), session = 4)
        val stream = DETECT + b(9, 9) + DETECT + DETECT + good + b(0xFF, 0x00) + DETECT
        assertDelivered(listOf(4 to payload(9, 2)), h.poll(stream))
        assertEquals(4, h.raws.size)
        for (r in h.raws) assertArrayEquals(DETECT, r)
        assertAcks(h, 1)
        assertEquals("detect is reported once", 1, h.reports.count { it.first == "iap2_detect" })
        assertEquals(0, h.rx.bufferedBytes)
    }

    @Test
    fun garbageRetentionIsLimitedToAPossibleMarkerOrHeader() {
        val h = Harness()

        // no 0xFF at all: nothing may be retained
        val noFf = ByteArray(Iap2Receiver.READ_SIZE) { ((it % 0xFE) + 1).toByte() }
        repeat(3) {
            assertTrue(h.rx.poll(noFf, noFf.size).isEmpty())
            assertEquals(0, h.rx.bufferedBytes)
        }

        // only the final FF may be a marker start
        val ffs = ByteArray(Iap2Receiver.READ_SIZE) { 0xFF.toByte() }
        repeat(2) {
            h.rx.poll(ffs, ffs.size)
            assertTrue("kept ${h.rx.bufferedBytes} FF bytes", h.rx.bufferedBytes <= 1)
        }

        // endless headers whose checksum is wrong: never more than one partial header remains
        val badHeader = raw(12, 0x40, 1, badHeader = true).copyOf(9)
        val headers = ByteArray(Iap2Receiver.READ_SIZE) { badHeader[it % 9] }
        repeat(3) {
            h.rx.poll(headers, headers.size)
            assertTrue("kept ${h.rx.bufferedBytes} header bytes", h.rx.bufferedBytes <= 8)
        }

        // endless truncated DETECT markers (missing the final byte)
        val almost = DETECT.copyOf(DETECT.size - 1)
        val markers = ByteArray(Iap2Receiver.READ_SIZE) { almost[it % almost.size] }
        repeat(3) {
            h.rx.poll(markers, markers.size)
            assertTrue("kept ${h.rx.bufferedBytes} marker bytes", h.rx.bufferedBytes <= DETECT.size - 1)
        }

        assertTrue(h.packets.isEmpty())
        assertTrue(h.raws.isEmpty())

        // after all that junk the stream still resynchronises on a real frame
        val ok = Harness().sync(0x10)
        ok.rx.poll(headers, headers.size)
        assertDelivered(listOf(5 to payload(11, 6)), ok.poll(frame(ACK, 0x11, payload(11, 6), session = 5)))
    }

    // ---- invalid headers / lengths ------------------------------------------------------------

    @Test
    fun invalidHeaderChecksumAndIllegalLengthsResynchroniseOneByteAtATime() {
        val good = frame(ACK, 0x11, payload(8, 3), session = 3)
        val illegal = mapOf(
            "bad header checksum" to frame(ACK, 0x30, payload(6, 1), badHeader = true),
            "length 0" to raw(0, ACK, 0x30),
            "length 1" to raw(1, ACK, 0x30),
            "length 8" to raw(8, ACK, 0x30),
            "length 10 immediately followed by good" to raw(10, ACK, 0x30),
            "length 10 with checksum-only body" to raw(10, ACK, 0x30, b(0)),
            "bad header, length 10" to raw(10, ACK, 0x30, b(0), badHeader = true),
        )
        for ((name, junk) in illegal) {
            val h = Harness().sync(0x10)
            val out = h.poll(junk + good)
            assertDelivered(listOf(3 to payload(8, 3)), out)
            assertEquals(name, 0x11, h.rx.rxSeq)
            assertAcks(h, 1)
            assertEquals(name, 0, h.rx.bufferedBytes)

            // the same junk arriving split in two reads behaves identically
            val s = Harness().sync(0x10)
            val all = junk + good
            val cut = 4
            val part1 = s.poll(all.copyOf(cut))
            val part2 = s.poll(all.copyOfRange(cut, all.size))
            assertDelivered(listOf(3 to payload(8, 3)), part1 + part2)
            assertAcks(s, 1)
        }

        // an illegal length-10 header must not make the receiver wait for more bytes
        val h = Harness().sync(0x10)
        assertTrue(h.poll(raw(10, ACK, 0x30)).isEmpty())
        assertEquals(0, h.rx.bufferedBytes)
        assertDelivered(listOf(3 to payload(8, 3)), h.poll(good))
        assertTrue(h.packets.size == 1)

        // length 9 with a valid header is a legal empty frame, not junk
        val empty = Harness().sync(0x10)
        assertTrue(empty.poll(raw(9, ACK, 0x10)).isEmpty())
        assertTrue(empty.rx.linked)
    }

    @Test
    fun badPayloadChecksumDiscardsExactlyTheDeclaredFrameWithoutAckOrStateChange() {
        val p = payload(30, 2)
        val bad = frame(ACK, 0x11, p, session = 3, badPayload = true)
        val good = frame(ACK, 0x11, p, session = 3)

        val h = Harness().sync(0x10)
        assertTrue(h.poll(bad).isEmpty())
        assertTrue("no ACK for a corrupted payload", h.packets.isEmpty())
        assertEquals(0x10, h.rx.rxSeq)
        assertEquals(0, h.rx.bufferedBytes)

        // the sender's retry with the same sequence number is accepted and ACKed
        assertDelivered(listOf(3 to p), h.poll(good))
        assertEquals(0x11, h.rx.rxSeq)
        assertAcks(h, 1)

        // retry arriving in the same read, directly behind the corrupted copy
        val same = Harness().sync(0x10)
        assertDelivered(listOf(3 to p), same.poll(bad + good))
        assertAcks(same, 1)
        assertEquals(0x11, same.rx.rxSeq)

        // a corrupted retransmit of the already-accepted sequence is not ACKed either
        val dup = Harness().sync(0x10)
        assertTrue(dup.poll(frame(ACK, 0x10, p, session = 3, badPayload = true)).isEmpty())
        assertTrue(dup.packets.isEmpty())
        assertEquals(0x10, dup.rx.rxSeq)

        // fragmented corrupted frame
        val frag = Harness().sync(0x10)
        assertTrue(frag.poll(bad.copyOf(15)).isEmpty())
        assertTrue(frag.poll(bad.copyOfRange(15, bad.size)).isEmpty())
        assertTrue(frag.packets.isEmpty())
        assertEquals(0x10, frag.rx.rxSeq)
        assertEquals(0, frag.rx.bufferedBytes)

        // a complete, valid frame hidden inside the corrupted frame's payload must NOT surface:
        // only the declared frame is discarded, never re-scanned byte by byte
        val inner = frame(ACK, 0x11, payload(12, 8), session = 9)
        val outerBad = frame(ACK, 0x12, inner, session = 3, badPayload = true)
        val nested = Harness().sync(0x10)
        val after = frame(ACK, 0x12, payload(4, 5), session = 6)
        assertDelivered(listOf(6 to payload(4, 5)), nested.poll(outerBad + after))
        assertAcks(nested, 1)
        assertEquals(0x12, nested.rx.rxSeq)
    }

    @Test
    fun rstWithInvalidHeaderChecksumIsIgnoredButAValidRstDisconnects() {
        val good = frame(ACK, 0x11, payload(8, 7), session = 3)

        for (control in intArrayOf(RST, RST or ACK)) {
            val h = Harness().sync(0x10)
            // not a frame: header checksum is wrong, so no disconnect and the stream resyncs
            val out = h.poll(frame(control, 0x30, badHeader = true) + good)
            assertDelivered(listOf(3 to payload(8, 7)), out)
            assertAcks(h, 1)

            val bad2 = Harness().sync(0x10)
            assertDelivered(
                listOf(3 to payload(8, 7)),
                bad2.poll(frame(control, 0x30, payload(5, 1), badHeader = true) + good),
            )

            val rst = Harness().sync(0x10)
            assertIo { rst.poll(frame(control, 0x30)) }

            // the same valid RST split across two reads still disconnects
            val split = Harness().sync(0x10)
            val wire = frame(control, 0x30)
            split.poll(wire.copyOf(5))
            assertIo { split.poll(wire.copyOfRange(5, wire.size)) }
        }
    }

    // ---- strict parameter / message parsing ---------------------------------------------------

    @Test
    fun parseParamsAcceptsOnlyFullyWellFormedTlvs() {
        val ok = tlv(0, "ab".toByteArray()) + tlv(7) + tlv(0x1234, b(1, 2, 3)) + tlv(9)
        val parsed = Iap2Receiver.parseParams(ok)
        assertEquals(4, parsed.size)
        assertEquals(0, parsed[0].first); assertArrayEquals("ab".toByteArray(), parsed[0].second)
        assertEquals(7, parsed[1].first); assertEquals(0, parsed[1].second.size)
        assertEquals(0x1234, parsed[2].first); assertArrayEquals(b(1, 2, 3), parsed[2].second)
        assertEquals(9, parsed[3].first); assertEquals(0, parsed[3].second.size)

        // start offset skips the CSM header
        val withHeader = b(0x40, 0x40, 0, 6 + ok.size, 0xAA, 0x01) + ok
        assertEquals(4, Iap2Receiver.parseParams(withHeader, 6).size)
        assertTrue(Iap2Receiver.parseParams(b(0x40, 0x40, 0, 6, 0xAA, 0x01), 6).isEmpty())

        val rejects = mapOf(
            "length 0" to rawTlv(0, 1),
            "length 1" to rawTlv(1, 1),
            "length 2" to rawTlv(2, 1),
            "length 3" to rawTlv(3, 1, b(1)),
            "length beyond remaining" to rawTlv(10, 1, b(1, 2)),
            "length 0xFFFF" to rawTlv(0xFFFF, 1, b(1, 2)),
            "tail of 1 byte" to tlv(1, b(1)) + b(0),
            "tail of 2 bytes" to tlv(1, b(1)) + b(0, 5),
            "tail of 3 bytes" to tlv(1, b(1)) + b(0, 5, 0),
            "good prefix then zero length" to tlv(1, b(1)) + tlv(2) + rawTlv(0, 3),
            "good prefix then short length" to tlv(1, b(1)) + rawTlv(3, 3, b(1)),
            "good prefix then overlong tail" to tlv(1, b(1)) + tlv(2, b(2)) + rawTlv(9, 3, b(1)),
            "good prefix then 3-byte tail" to tlv(1) + tlv(2) + b(0, 4, 0),
        )
        for ((name, bytes) in rejects) {
            assertIo { Iap2Receiver.parseParams(bytes) }
            // the identical malformed tail is still rejected at a non-zero start offset
            assertIo { Iap2Receiver.parseParams(b(9, 9, 9, 9, 9, 9) + bytes, 6) }
        }
    }

    @Test
    fun controlMessageIdRequiresAHeaderOfSixBytesAndExactDeclaredLength() {
        assertEquals(0xAA01, Iap2Receiver.controlMessageId(csm(0xAA01, tlv(0, b(1, 2)))))
        assertEquals(0xEA00, Iap2Receiver.controlMessageId(csm(0xEA00)))
        assertEquals(0x1D01, Iap2Receiver.controlMessageId(csm(0x1D01, payload(200, 3))))

        for (n in 0..5) assertIo { Iap2Receiver.controlMessageId(csm(0xAA01).copyOf(n)) }

        val params = payload(8, 2)
        // declared length shorter than the bytes present
        assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, params, declared = 6 + params.size - 1)) }
        assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, params, declared = 6)) }
        // declared length longer than the bytes present
        assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, params, declared = 6 + params.size + 1)) }
        assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, params, declared = 0xFFFF)) }
        // declared length smaller than the header itself
        assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, declared = 5)) }
    }

    // ---- size limits --------------------------------------------------------------------------

    @Test
    fun maxSizePacketsStreamInReadSizeChunksAndPartialStateStaysBounded() {
        assertEquals(65536, Iap2Receiver.READ_SIZE)
        assertEquals(65535, Iap2Receiver.MAX_PACKET_LENGTH)

        val p1 = payload(65535 - 10, 1)
        val p2 = payload(65535 - 10, 2)
        val f1 = frame(ACK, 0x11, p1, session = 5)
        val f2 = frame(ACK, 0x12, p2, session = 6)
        assertEquals(65535, f1.size)
        assertEquals(65535, f2.size)
        val stream = f1 + f2

        // (a) first read ends one byte short of frame 1: logical buffer peaks at 65534 + 65536
        val a = Harness().sync(0x10)
        assertTrue(a.rx.poll(stream, 65534).isEmpty())
        assertTrue("partial state ${a.rx.bufferedBytes}", a.rx.bufferedBytes <= Iap2Receiver.MAX_PACKET_LENGTH)
        assertTrue(a.packets.isEmpty())
        val rest = stream.copyOfRange(65534, stream.size)
        assertEquals(Iap2Receiver.READ_SIZE, rest.size)
        assertDelivered(listOf(5 to p1, 6 to p2), a.rx.poll(rest, rest.size))
        assertAcks(a, 2)
        assertEquals(0x12, a.rx.rxSeq)
        assertEquals(0, a.rx.bufferedBytes)

        // (b) chunking the stream at READ_SIZE boundaries
        val chunked = Harness().sync(0x10)
        val out = ArrayList<Pair<Int, ByteArray>>()
        var off = 0
        while (off < stream.size) {
            val end = minOf(off + Iap2Receiver.READ_SIZE, stream.size)
            out += chunked.rx.poll(stream.copyOfRange(off, end), end - off)
            assertTrue(chunked.rx.bufferedBytes <= Iap2Receiver.MAX_PACKET_LENGTH)
            off = end
        }
        assertDelivered(listOf(5 to p1, 6 to p2), out)
        assertAcks(chunked, 2)
        assertEquals(0, chunked.rx.bufferedBytes)

        // (c) a valid max-length header with an incomplete body is held as bounded partial state
        val c = Harness().sync(0x10)
        val open = raw(65535, ACK, 0x11) + ByteArray(60000) { 0x41 }
        c.rx.poll(open, open.size)
        assertTrue(c.rx.bufferedBytes <= Iap2Receiver.MAX_PACKET_LENGTH)
        assertTrue(c.packets.isEmpty())
    }

    // ---- diagnostics must not leak payloads ---------------------------------------------------

    @Test
    fun exceptionsAndReportsNeverContainRawPayloadBytes() {
        val secret = "SECRET-PAYLOAD-42".toByteArray()
        fun assertClean(text: String?) {
            val t = (text ?: "").lowercase()
            assertFalse(t, t.contains("secret"))
            assertFalse(t, t.contains(hex(secret.copyOf(6))))
            assertFalse(t, t.contains("83, 69, 67"))
        }

        val syn = Harness()
        val seq0 = syn.rx.rxSeq
        try { syn.poll(frame(SYN, 0x44, secret)) } catch (e: IOException) { assertClean(e.message) }
        assertEquals(seq0, syn.rx.rxSeq)
        for ((_, msg) in syn.reports) assertClean(msg)
        assertFalse(syn.rx.linked)

        val bad = Harness().sync(0x10)
        bad.poll(frame(ACK, 0x11, secret, badPayload = true))
        for ((_, msg) in bad.reports) assertClean(msg)
        assertTrue(bad.packets.isEmpty())

        assertClean(assertIo { Iap2Receiver.parseParams(rawTlv(2, 1, secret)) }.message)
        assertClean(assertIo { Iap2Receiver.parseParams(tlv(1) + secret.copyOf(3)) }.message)
        assertClean(assertIo { Iap2Receiver.controlMessageId(csm(0xAA01, secret, declared = 9)) }.message)

        val rst = Harness().sync(0x10)
        assertClean(assertIo { rst.poll(frame(RST, 0x30)) }.message)
    }
}
