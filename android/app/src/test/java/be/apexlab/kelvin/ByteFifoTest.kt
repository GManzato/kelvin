package be.apexlab.kelvin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun multibyteFieldsAreUnsignedAndRespectTheirByteOrderAndOffset() {
        val fifo = ByteFifo(1)
        fifo.append(byteArrayOf(9, 0x80.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x98.toByte()), 7)

        assertEquals(128, fifo[1])
        assertEquals(0x80FF, fifo.u16be(1))
        assertEquals(0x98BADCFE, fifo.u32le(3))
    }

    @Test
    fun clearingOrDrainingDoesNotExposeBytesFromPreviousSession() {
        val fifo = ByteFifo(8)
        fifo.append(byteArrayOf(1, 2, 3, 4, 5), 5)
        fifo.clear()
        fifo.append(byteArrayOf(1, 2), 2)
        assertEquals(2, fifo.size)
        assertArrayEquals(byteArrayOf(1, 2), fifo.copy(0, fifo.size))

        fifo.drop(100)
        fifo.append(byteArrayOf(7, 8, 9), 3)
        assertEquals(3, fifo.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), fifo.copy(0, fifo.size))
    }

}
