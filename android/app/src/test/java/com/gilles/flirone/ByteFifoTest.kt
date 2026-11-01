package com.gilles.flirone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteFifoTest {
    @Test
    fun appendKeepsOnlyReceivedBytesWhenUsbBufferIsReused() {
        val fifo = ByteFifo(2)
        val usbBuffer = byteArrayOf(10, 20, 99, 99)
        fifo.append(usbBuffer, 2)
        usbBuffer[0] = 30
        usbBuffer[1] = 40
        usbBuffer[2] = 50
        fifo.append(usbBuffer, 3)

        assertEquals(5, fifo.size)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50), fifo.copy(0, fifo.size))
    }

    @Test
    fun frameMarkerSplitAcrossReadsSurvivesCompactionAndGrowth() {
        val fifo = ByteFifo(2)
        val magic = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0, 0)
        val firstRead = byteArrayOf(11, 22, 0xEF.toByte(), 0xBE.toByte())
        fifo.append(firstRead, firstRead.size)
        assertEquals(-1, fifo.indexOf(magic))

        // Preserve the partial marker while discarding unrelated stream bytes.
        fifo.drop(2)
        assertFalse(fifo.startsWith(magic))
        fifo.append(byteArrayOf(0, 0, 42, 43, 44), 5)

        assertEquals(0, fifo.indexOf(magic))
        assertTrue(fifo.startsWith(magic))
        fifo.drop(magic.size)
        assertArrayEquals(byteArrayOf(42, 43, 44), fifo.copy(0, fifo.size))
    }

    @Test
    fun overlappingMarkersAreFoundInStreamOrder() {
        val fifo = ByteFifo(3)
        val marker = byteArrayOf(1, 2, 1)
        fifo.append(byteArrayOf(9, 1, 2, 1, 2, 1, 8), 7)
        assertEquals(1, fifo.indexOf(marker))

        fifo.drop(2)
        assertEquals(1, fifo.indexOf(marker))
        fifo.drop(4)
        assertEquals(-1, fifo.indexOf(marker))
        assertArrayEquals(byteArrayOf(8), fifo.copy(0, fifo.size))
    }

    @Test
    fun multibyteFieldsAreUnsignedAndRespectTheirByteOrderAndOffset() {
        val fifo = ByteFifo(1)
        fifo.append(byteArrayOf(9, 0x80.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x98.toByte()), 7)

        assertEquals(128, fifo[1])
        assertEquals(0x80FF, fifo.u16be(1))
        assertEquals(0x98BADCFE, fifo.u32le(3))
    }

    @Test
    fun clearingOrDrainingDoesNotExposeMarkersFromPreviousSession() {
        val fifo = ByteFifo(8)
        val oldMarker = byteArrayOf(1, 2, 3)
        fifo.append(byteArrayOf(1, 2, 3, 4, 5), 5)
        fifo.clear()
        fifo.append(byteArrayOf(1, 2), 2)
        assertEquals(-1, fifo.indexOf(oldMarker))
        assertFalse(fifo.startsWith(oldMarker))

        fifo.drop(100)
        fifo.append(byteArrayOf(7, 8, 9), 3)
        assertEquals(3, fifo.size)
        assertEquals(-1, fifo.indexOf(oldMarker))
        assertArrayEquals(byteArrayOf(7, 8, 9), fifo.copy(0, fifo.size))
    }

}
