package be.apexlab.kelvin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Exercises the platform org.json/UTF-8 implementation, not the JVM test-only JSON library. */
@RunWith(AndroidJUnit4::class)
class ProtocolSafetyTest {
    @Test
    fun legalUtf8EscapesNumbersAndNestedValuesSurvivePlatformParsing() {
        val text = """{"name":"Kelvin μ","escaped":"quote: \" brace: } slash: \\","number":-1.25e2,"nested":{"ready":true},"items":[1,null]}"""
        val json = RbJson().decode(nul(text))
        assertEquals("Kelvin μ", json.getString("name"))
        assertEquals("quote: \" brace: } slash: \\", json.getString("escaped"))
        assertEquals(-125.0, json.getDouble("number"), 0.0)
        assertTrue(json.getJSONObject("nested").getBoolean("ready"))
        assertEquals(1, json.getJSONArray("items").getInt(0))
        assertTrue(json.getJSONArray("items").isNull(1))
    }

    @Test
    fun platformJsonLenienciesCannotTurnMalformedPacketsIntoEvents() {
        var events = 0
        val reader = RbProtocolReader(RbProtocolReader.Channel.CONFIG) { events++ }
        val malformed = listOf(
            """{value:1}""", """{"value":NaN}""", """{"value":01}""", """{"value":+1}""",
            """{"value":1,}""", """{"value":[1,,2]}""", """{"value":'x'}""",
            """{"value":/* comment */1}""", """{"value":"\q"}""", """{"value":"\uXYZ0"}""",
            """{"value":bare}""", """{"value":1} trailing""",
        )
        for (text in malformed) {
            assertThrows(IOException::class.java) { reader.feed(config(nul(text))) }
            assertEquals(0, events)
        }
        reader.feed(config(nul("{\"value\":17}")))
        assertEquals(1, events)
    }

    @Test
    fun exactNulUtf8AndDepthBoundsAreEnforcedOnTheDevice() {
        val decoder = RbJson()
        val bad = listOf(
            "{}".toByteArray(), byteArrayOf(0), nul("{}") + byteArrayOf(0),
            byteArrayOf('{'.code.toByte(), 0, '}'.code.toByte(), 0),
            byteArrayOf(0xC3.toByte(), 0x28, 0),
            nul("{\"x\":".repeat(33) + "0" + "}".repeat(33)),
        )
        for (bytes in bad) assertThrows(IOException::class.java) { decoder.decode(bytes) }
        assertTrue(decoder.decode(nul("{\"ready\":true}")).getBoolean("ready"))
    }

    @Test
    fun validFrameDecodesRealRowBoundariesBeforeDeliveringSamples() {
        val frames = ArrayList<FlirCamera.Frame>()
        val reader = RbProtocolReader(RbProtocolReader.Channel.FRAME) { frames.add(it.decodeCameraFrame()) }
        val packet = frame(nul("{\"shutterState\":\"OFF\",\"ffcState\":\"FFC_VALID_RAD\"}"))
        reader.feed(packet.copyOfRange(0, 13))
        assertTrue(frames.isEmpty())
        reader.feed(packet.copyOfRange(13, packet.size))
        val decoded = frames.single()
        assertEquals(0x1234, decoded.raw!![0])
        assertEquals(0x2345, decoded.raw!![4799])
        assertEquals(10332, decoded.thermalBytes)
        assertEquals("OFF", decoded.status!!.getString("shutterState"))
        assertEquals("FFC_VALID_RAD", decoded.status!!.getString("ffcState"))
    }

    @Test
    fun corruptHeadersAndMetadataCannotDeliverThermalSamples() {
        val frames = ArrayList<FlirCamera.Frame>()
        val reader = RbProtocolReader(RbProtocolReader.Channel.FRAME) { frames.add(it.decodeCameraFrame()) }
        val valid = frame(nul("{\"shutterState\":\"OFF\"}"))
        val badCrc = valid.clone().also { it[24] = (it[24].toInt() xor 1).toByte() }
        val missingNul = frame("{\"shutterState\":\"OFF\"}".toByteArray())
        val overflowHeader = valid.copyOf(28).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, -1)
            seal(it)
        }
        for (bad in listOf(badCrc, missingNul, overflowHeader)) {
            assertThrows(IOException::class.java) { reader.feed(bad + valid) }
            assertTrue(frames.isEmpty())
        }
        reader.feed(valid)
        assertEquals(0x1234, frames.single().raw!![0])
    }

    private fun nul(text: String) = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    private fun config(payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x1CC).putInt(1).putInt(payload.size).array()
        seal(header)
        return header + payload
    }

    private fun frame(metadata: ByteArray): ByteArray {
        val thermal = ByteArray(10332)
        thermal[4] = 0x34; thermal[5] = 0x12
        val last = 59 * 164 + 4 + 79 * 2
        thermal[last] = 0x45; thermal[last + 1] = 0x23
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xBEEF).putInt(1).putInt(thermal.size + metadata.size)
            .putInt(thermal.size).putInt(0).putInt(metadata.size).array()
        seal(header)
        return header + thermal + metadata
    }

    private fun seal(header: ByteArray) {
        val size = header.size - 4
        val crc = CRC32().apply { update(header, 0, size) }.value
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(size, crc.toInt())
    }
}
