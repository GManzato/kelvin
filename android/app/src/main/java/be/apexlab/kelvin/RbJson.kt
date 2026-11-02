package be.apexlab.kelvin

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Hardened metadata decoder: strict UTF-8 and a bounded lexical precheck run
 * before org.json builds any object, so the lenient JSONTokener never decides
 * what untrusted bytes mean. One instance belongs to one thread; failures are
 * generic IOExceptions that never echo the input or wrap a JSONException cause.
 */
internal class RbJson {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private val slots = IntArray(MAX_DEPTH)

    /** Parses UTF-8 JSON text ending in exactly one NUL byte into an object. */
    fun decode(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty()) throw IOException("JSON metadata is empty")
        if (bytes.size > MAX_BYTES) throw IOException("JSON metadata exceeds $MAX_BYTES bytes")
        val text = try {
            decoder.reset()
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("JSON metadata is not valid UTF-8")
        }
        if (text.last() != NUL || text.indexOf(NUL) != text.length - 1) {
            throw IOException("JSON metadata must end in exactly one NUL byte")
        }
        checkSyntax(text, text.length - 1)
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue()
        } catch (e: JSONException) {
            throw IOException("JSON metadata is malformed")
        }
        if (value !is JSONObject) throw IOException("JSON metadata must be a single object")
        if (tokener.nextClean() != NUL) throw IOException("JSON metadata has trailing data")
        return value
    }

    /** Bounded grammar validation; org.json constructs objects only after this strict pass. */
    private fun checkSyntax(text: String, end: Int) {
        var depth = 0
        var tokens = 0
        var rootSeen = false
        var i = 0
        while (i < end) {
            val c = text[i]
            if (isWhitespace(c)) { i++; continue }
            when (c) {
                '}', ']' -> {
                    if (depth == 0) throw IOException("JSON metadata has unbalanced brackets")
                    val state = slots[depth - 1]
                    val valid = if (c == '}') state == OBJECT_START || state == OBJECT_END
                        else state == ARRAY_START || state == ARRAY_END
                    if (!valid) throw IOException("JSON metadata has an incomplete container")
                    depth--
                    i++
                    continue
                }
                ':' -> {
                    if (depth == 0 || slots[depth - 1] != OBJECT_COLON) throw IOException("JSON metadata has a misplaced colon")
                    slots[depth - 1] = OBJECT_VALUE
                    i++
                    continue
                }
                ',' -> {
                    if (depth == 0) throw IOException("JSON metadata has a misplaced separator")
                    slots[depth - 1] = when (slots[depth - 1]) {
                        OBJECT_END -> OBJECT_KEY
                        ARRAY_END -> ARRAY_VALUE
                        else -> throw IOException("JSON metadata has a missing or misplaced element")
                    }
                    i++
                    continue
                }
            }
            if (depth == 0) {
                if (rootSeen || c != '{') throw IOException("JSON metadata must be a single object")
                rootSeen = true
            } else {
                val slot = depth - 1
                slots[slot] = when (slots[slot]) {
                    OBJECT_START, OBJECT_KEY -> {
                        if (c != '"') throw IOException("JSON metadata object keys must be quoted")
                        OBJECT_COLON
                    }
                    OBJECT_VALUE -> OBJECT_END
                    ARRAY_START, ARRAY_VALUE -> ARRAY_END
                    else -> throw IOException("JSON metadata has a missing separator or value")
                }
            }
            when (c) {
                '{', '[' -> {
                    if (depth == MAX_DEPTH) throw IOException("JSON metadata nesting exceeds $MAX_DEPTH levels")
                    slots[depth++] = if (c == '{') OBJECT_START else ARRAY_START
                    i++
                }
                '"' -> i = scanString(text, end, i)
                't', 'f', 'n' -> {
                    val word = if (c == 't') "true" else if (c == 'f') "false" else "null"
                    if (!text.startsWith(word, i) || (i + word.length < end && isTokenChar(text[i + word.length]))) {
                        throw IOException("JSON metadata contains an invalid literal")
                    }
                    i += word.length
                }
                '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> i = scanNumber(text, end, i)
                else -> throw IOException("JSON metadata contains an unexpected character")
            }
            tokens = countToken(tokens)
        }
        if (depth != 0 || !rootSeen) throw IOException("JSON metadata has an incomplete object")
    }

    private fun countToken(tokens: Int): Int {
        if (tokens + 1 > MAX_TOKENS) throw IOException("JSON metadata exceeds $MAX_TOKENS tokens")
        return tokens + 1
    }

    /** Returns the index just past the closing quote. */
    private fun scanString(text: String, end: Int, start: Int): Int {
        var i = start + 1
        while (i < end) {
            val c = text[i]
            if (c == '"') return i + 1
            if (c == '\\') {
                i++
                if (i >= end) break
                val e = text[i]
                if (e == 'u') {
                    i = scanUnicodeEscape(text, end, i)
                } else if (e == '"' || e == '\\' || e == '/' || e == 'b' || e == 'f' || e == 'n' || e == 'r' || e == 't') {
                    i++
                } else {
                    throw IOException("JSON metadata has an unknown escape")
                }
            } else {
                if (c < ' ') throw IOException("JSON metadata contains a raw control character")
                i++
            }
        }
        throw IOException("JSON metadata has an unterminated string")
    }

    /**
     * Validates the backslash-u escape whose 'u' sits at [u] and returns the
     * index just past it. Astral code points must arrive as a complete high
     * plus low surrogate escape pair; a stray half is rejected outright.
     */
    private fun scanUnicodeEscape(text: String, end: Int, u: Int): Int {
        if (u + 4 >= end) throw IOException("JSON metadata has a truncated escape")
        val code = hexCode(text, u + 1)
        if (code in 0xDC00..0xDFFF) throw IOException("JSON metadata contains an unpaired surrogate")
        var i = u + 5
        if (code in 0xD800..0xDBFF) {
            if (i + 5 >= end || text[i] != '\\' || text[i + 1] != 'u') {
                throw IOException("JSON metadata contains an unpaired surrogate")
            }
            if (hexCode(text, i + 2) !in 0xDC00..0xDFFF) {
                throw IOException("JSON metadata contains an unpaired surrogate")
            }
            i += 6
        }
        return i
    }

    /** Returns the index just past a complete RFC 8259 number. */
    private fun scanNumber(text: String, end: Int, start: Int): Int {
        var i = start
        if (text[i] == '-') i++
        if (i >= end) throw IOException("JSON metadata has a malformed number")
        when (text[i]) {
            '0' -> i++
            in '1'..'9' -> while (i < end && text[i] in '0'..'9') i++
            else -> throw IOException("JSON metadata has a malformed number")
        }
        if (i < end && text[i] == '.') {
            i++
            if (i >= end || text[i] !in '0'..'9') throw IOException("JSON metadata has a malformed number")
            while (i < end && text[i] in '0'..'9') i++
        }
        if (i < end && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < end && (text[i] == '+' || text[i] == '-')) i++
            if (i >= end || text[i] !in '0'..'9') throw IOException("JSON metadata has a malformed number")
            while (i < end && text[i] in '0'..'9') i++
        }
        if (i < end && (isTokenChar(text[i]) || text[i] == '.' || text[i] == '+' || text[i] == '-')) {
            throw IOException("JSON metadata has a malformed number")
        }
        return i
    }

    private fun isWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isTokenChar(c: Char) =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    private fun hexCode(text: String, start: Int): Int {
        var value = 0
        for (i in start until start + 4) {
            val digit = when (val c = text[i]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> throw IOException("JSON metadata has an invalid escape")
            }
            value = (value shl 4) or digit
        }
        return value
    }

    companion object {
        const val MAX_BYTES = 1048576
        const val MAX_DEPTH = 32
        const val MAX_TOKENS = 1024
        private const val OBJECT_START = 1
        private const val OBJECT_KEY = 2
        private const val OBJECT_COLON = 3
        private const val OBJECT_VALUE = 4
        private const val OBJECT_END = 5
        private const val ARRAY_START = 6
        private const val ARRAY_VALUE = 7
        private const val ARRAY_END = 8
        private val NUL: Char = 0.toChar()
    }
}
