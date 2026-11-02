package be.apexlab.kelvin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for [RbJson]: every fixture is NUL-terminated UTF-8 metadata exactly as
 * the protocol channels deliver it, and rejections are checked as tables of
 * malformed inputs rather than one assert per rule.
 */
class RbJsonTest {
    @Test
    fun decodesCameraStyleMetadataAndStaysUsableAfterARejection() {
        val helper = RbJson()
        val json = """
            {"mode":"thermal","width":80,"cal":{"sensorId":42,"offsets":[3,7,11]},
             "emissivity":0.95,"mirror":true,"serial":null,
             "ports":[1,"two",true,null,{},[]]}
        """.trimIndent()
        val obj = helper.decode(metadata(json))

        assertEquals("thermal", obj.getString("mode"))
        assertEquals(80, obj.getInt("width"))
        assertEquals(42, obj.getJSONObject("cal").getInt("sensorId"))
        assertEquals(7, obj.getJSONObject("cal").getJSONArray("offsets").getInt(1))
        assertEquals(0.95, obj.getDouble("emissivity"), 1e-9)
        assertTrue(obj.getBoolean("mirror"))
        assertTrue(obj.isNull("serial"))
        val ports = obj.getJSONArray("ports")
        assertEquals(6, ports.length())
        assertEquals(1, ports.getInt(0))
        assertEquals("two", ports.getString(1))
        assertTrue(ports.getBoolean(2))
        assertTrue(ports.isNull(3))
        assertEquals(0, ports.getJSONObject(4).length())
        assertEquals(0, ports.getJSONArray(5).length())

        assertThrows(IOException::class.java) { helper.decode(metadata("""{"a" 1}""")) }
        val again = helper.decode(metadata("""{"ok":[true]}"""))
        assertTrue(again.getJSONArray("ok").getBoolean(0))
    }

    @Test
    fun acceptsEveryLegalEscapeAndMultibyteText() {
        val escapedThermo = "\\" + "uD83C" + "\\" + "uDF21"
        val obj = RbJson().decode(metadata("""{"s":"A\/B\b\f\n\r\t\\\"Eé日🌡","p":"$escapedThermo"}"""))

        val expectedS = "A/B" + '\b' + 12.toChar() + '\n' + '\r' + '\t' + '\\' + '"' + "Eé日🌡"
        assertEquals(expectedS, obj.getString("s"))
        assertEquals(String(Character.toChars(0x1F321)), obj.getString("p"))
    }

    @Test
    fun acceptsPaddingWhitespaceAndEveryLegalNumberForm() {
        val obj = RbJson().decode(metadata("\t{\r\n\"n\":[0,-0,1.5,1e3,1E+3,-1.25e-3,1234567890]\n}\t "))
        val n = obj.getJSONArray("n")
        assertEquals(0, n.getInt(0))
        assertEquals(0.0, n.getDouble(1), 0.0)
        assertEquals(1.5, n.getDouble(2), 0.0)
        assertEquals(1000.0, n.getDouble(3), 0.0)
        assertEquals(1000.0, n.getDouble(4), 0.0)
        assertEquals(-0.00125, n.getDouble(5), 0.0)
        assertEquals(1234567890L, n.getLong(6))
    }

    @Test
    fun acceptsBracketsCommentMarksAndQuotesInsideStrings() {
        val obj = RbJson().decode(metadata("""{"a":"{[}]","b":"// or /* not comments */","c":"say \"hi\""}"""))
        assertEquals("{[}]", obj.getString("a"))
        assertEquals("// or /* not comments */", obj.getString("b"))
        assertEquals("say " + '"' + "hi" + '"', obj.getString("c"))
    }

    @Test
    fun requiresExactlyOneTrailingNul() {
        listOf(
            utf8("""{"a":1}"""),
            cat(metadata("""{"a":1}"""), byteArrayOf(0)),
            cat(byteArrayOf(0), metadata("""{"a":1}""")),
            cat(utf8("""{"a"""), byteArrayOf(0), metadata("""":1}""")),
            byteArrayOf(),
            byteArrayOf(0),
        ).forEach { input ->
            assertThrows(IOException::class.java) { RbJson().decode(input) }
        }
    }

    @Test
    fun rejectsInvalidUtf8Sequences() {
        listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xA0.toByte()),
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xC3.toByte()),
        ).forEach { bad ->
            assertThrows(IOException::class.java) {
                RbJson().decode(cat(utf8("""{"v":"""), bad, utf8(""""}"""), byteArrayOf(0)))
            }
        }
        assertThrows(IOException::class.java) {
            RbJson().decode(cat(utf8("{}"), byteArrayOf(0xFF.toByte()), byteArrayOf(0)))
        }
    }

    @Test
    fun rejectsNonObjectRootsAndTrailingData() {
        listOf(
            "[1,2]", "[]", "\"hello\"", "123", "-4.5", "true", "null",
            """{"a":1}}""",
            """{"a":1}{"b":2}""",
            """{"a":1}x""",
            """{"a":1} }{""",
        ).forEach { reject(it) }
    }

    @Test
    fun rejectsCommentsAndSingleQuotedTokens() {
        listOf(
            """{/*c*/"a":1}""",
            """{"a":1 /*t*/}""",
            "{//x\n\"a\":1}",
            """{'a':1}""",
            """{"a":'b'}""",
        ).forEach { reject(it) }
    }

    @Test
    fun rejectsUnquotedTokensAndNonJsonLiterals() {
        listOf(
            """{a:1}""",
            """{1:2}""",
            """{"a":b}""",
            """{"a":undefined}""",
            """{"a":NaN}""",
            """{"a":Infinity}""",
            """{"a":-Infinity}""",
            """{"a":nul}""",
            """{"a":tru}""",
            """{"a":truex}""",
            """{"a":+1}""",
            "NaN",
        ).forEach { reject(it) }
    }

    @Test
    fun rejectsMalformedNumbers() {
        listOf(
            """{"a":01}""",
            """{"a":01.5}""",
            """{"a":.5}""",
            """{"a":1.}""",
            """{"a":1e}""",
            """{"a":1e+}""",
            """{"a":-}""",
            """{"a":--1}""",
            """{"a":0x1F}""",
            """{"n":[1,2e-]}""",
        ).forEach { reject(it) }
    }

    @Test
    fun rejectsBadStringEscapesAndRawControlCharacters() {
        listOf(
            """{"a":"\x41"}""",
            """{"a":"\q"}""",
            """{"a":"x\y"}""",
            """{"a":"\"}""",
            """{"a":"abc""""",
            """{"a":"\u12"}""",
            """{"a":"\u12g4"}""",
            """{"a":"bad""" + "\\" + "udc00" + """tail"}""",
            """{"a":"lone """ + "\\" + "uD800" + """ end"}""",
            """{"a":"x""" + "\t" + """y"}""",
            """{"a":"x""" + "\n" + """y"}""",
        ).forEach { reject(it) }
        assertThrows(IOException::class.java) {
            RbJson().decode(cat(utf8("""{"a":"x"""), byteArrayOf(1), utf8("""y"}"""), byteArrayOf(0)))
        }
    }

    @Test
    fun rejectsElidedOrMisplacedElements() {
        listOf(
            """{"a":[1,]}""",
            """{"a":[,1]}""",
            """{"a":[,]}""",
            """{"a":1,}""",
            """{,"a":1}""",
            """{"a":1,,}""",
            """{"a":1 2}""",
            """{"a":[1 2]}""",
            """{"a"::1}""",
        ).forEach { reject(it) }
    }

    @Test
    fun enforcesNestingAndTokenLimitsBeforeConstruction() {
        val helper = RbJson()
        fun nested(levels: Int) = "{\"a\":".repeat(levels) + "1" + "}".repeat(levels)

        var obj = helper.decode(metadata(nested(RbJson.MAX_DEPTH)))
        repeat(RbJson.MAX_DEPTH - 1) { obj = obj.getJSONObject("a") }
        assertEquals(1, obj.getInt("a"))
        reject(nested(RbJson.MAX_DEPTH + 1))

        val values = RbJson.MAX_TOKENS - 3
        val atLimit = helper.decode(metadata("""{"a":[""" + "1,".repeat(values - 1) + "1]}"))
        assertEquals(values, atLimit.getJSONArray("a").length())
        reject("""{"a":[""" + "1,".repeat(values) + "1]}")
    }

    @Test
    fun acceptsAtTheByteLimitAndRejectsBeyondIt() {
        val atLimit = "x".repeat(RbJson.MAX_BYTES - 9)
        val obj = RbJson().decode(metadata("{\"a\":\"" + atLimit + "\"}"))
        assertEquals(atLimit.length, obj.getString("a").length)
        reject("{\"a\":\"" + "x".repeat(RbJson.MAX_BYTES - 8) + "\"}")
    }

    @Test
    fun rejectionsNeverExposeInputTextOrUnderlyingCause() {
        listOf(
            metadata("""{"kelvinsecret" 1}"""),
            cat(utf8("""{"kelvinsecret":"""), byteArrayOf(0xC3.toByte()), utf8("1}"), byteArrayOf(0)),
        ).forEach { input ->
            val failure = assertThrows(IOException::class.java) { RbJson().decode(input) }
            assertNull(failure.cause)
            val message = failure.message ?: ""
            assertFalse(message.contains("kelvinsecret"))
            assertFalse(message.contains("{") || message.contains("\""))
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    /** One metadata payload exactly as delivered on the wire: UTF-8 JSON plus its trailing NUL. */
    private fun metadata(json: String) = cat(utf8(json), byteArrayOf(0))

    private fun reject(json: String) {
        assertThrows(IOException::class.java) { RbJson().decode(metadata(json)) }
    }
}
