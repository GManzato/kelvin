package be.apexlab.kelvin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class ConfigEaReceiverTest {
    @Test
    fun unrelatedLinkAndLogicalSessionsCannotContaminateAFragmentedConfig() {
        val events = ArrayList<JSONObject>()
        var notices = 0
        val receiver = ConfigEaReceiver(2, 0x1234, { events.add(it) }, { notices++ })
        val wire = packet("{\"type\":\"battery\",\"data\":{\"percent\":42}}\u0000".toByteArray())
        receiver.accept(listOf(
            2 to ea(wire.copyOfRange(0, 10)),
            1 to byteArrayOf(0x40), // control traffic is not a truncated config EA packet
            2 to (byteArrayOf(0x34, 0x12) + byteArrayOf(9, 8, 7, 6)),
            2 to ea(byteArrayOf()),
            2 to ea(wire.copyOfRange(10, wire.size)),
        ))
        assertEquals(1, events.size)
        assertEquals("battery", events.single().getString("type"))
        assertEquals(42, events.single().getJSONObject("data").getInt("percent"))
        assertEquals(0, notices)
    }

    @Test
    fun headerOnlyNoticesAreNotJsonEventsAndDoNotHideFollowingMessages() {
        val events = ArrayList<JSONObject>()
        var notices = 0
        val receiver = ConfigEaReceiver(2, 0x1234, { events.add(it) }, { notices++ })
        receiver.accept(listOf(2 to ea(packet(byteArrayOf()) + packet("{\"ready\":true}\u0000".toByteArray()))))
        assertEquals(1, notices)
        assertEquals(1, events.size)
        assertTrue(events.single().getBoolean("ready"))
    }

    @Test
    fun truncatedMatchingEaHeaderClearsOldPartialStateBeforeAFreshPacket() {
        val events = ArrayList<JSONObject>()
        val receiver = ConfigEaReceiver(2, 0x1234, { events.add(it) }, {})
        val wire = packet("{\"ready\":true}\u0000".toByteArray())
        receiver.accept(listOf(2 to ea(wire.copyOfRange(0, 10))))
        for (short in listOf(byteArrayOf(), byteArrayOf(0x12))) {
            assertThrows(IOException::class.java) { receiver.accept(listOf(2 to short)) }
        }
        receiver.accept(listOf(2 to ea(wire)))
        assertEquals(1, events.size)
        assertTrue(events.single().getBoolean("ready"))
    }

    @Test
    fun malformedConfigNeverBecomesAnEventAndFreshValidDataCanFollow() {
        val events = ArrayList<JSONObject>()
        val receiver = ConfigEaReceiver(2, 0x1234, { events.add(it) }, {})
        val valid = packet("{\"value\":17}\u0000".toByteArray())
        val badCrc = valid.clone().also { it[12] = (it[12].toInt() xor 1).toByte() }
        val badBodies = listOf(badCrc, packet("{\"value\":17}".toByteArray()), packet("{\"value\":}\u0000".toByteArray()))
        for (bad in badBodies) {
            assertThrows(IOException::class.java) { receiver.accept(listOf(2 to ea(bad))) }
            assertTrue(events.isEmpty())
        }
        receiver.accept(listOf(2 to ea(valid)))
        assertEquals(17, events.single().getInt("value"))
    }

    private fun ea(bytes: ByteArray) = byteArrayOf(0x12, 0x34) + bytes

    private fun packet(body: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x1CC).putInt(1).putInt(body.size).array()
        val crc = CRC32().apply { update(header, 0, 12) }.value
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(12, crc.toInt())
        return header + body
    }
}
