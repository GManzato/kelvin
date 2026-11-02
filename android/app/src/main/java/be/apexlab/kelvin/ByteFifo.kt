package be.apexlab.kelvin

import java.util.zip.Checksum

/** Growable byte queue: append at the end, consume from the front. */
class ByteFifo(initialCapacity: Int) {
    private var a = ByteArray(initialCapacity)
    var size = 0
        private set

    fun append(src: ByteArray, len: Int, offset: Int = 0) {
        if (size + len > a.size) a = a.copyOf(maxOf(a.size * 2, size + len))
        System.arraycopy(src, offset, a, size, len)
        size += len
    }

    fun drop(count: Int) {
        val k = minOf(count, size)
        System.arraycopy(a, k, a, 0, size - k)
        size -= k
    }

    fun clear() {
        size = 0
    }

    operator fun get(i: Int): Int = a[i].toInt() and 0xFF

    fun copy(offset: Int, len: Int): ByteArray = a.copyOfRange(offset, offset + len)

    fun updateChecksum(checksum: Checksum, offset: Int, len: Int) {
        checksum.update(a, offset, len)
    }

    fun u16be(off: Int): Int = (get(off) shl 8) or get(off + 1)

    fun u32le(off: Int): Long =
        get(off).toLong() or (get(off + 1).toLong() shl 8) or
            (get(off + 2).toLong() shl 16) or (get(off + 3).toLong() shl 24)
}
