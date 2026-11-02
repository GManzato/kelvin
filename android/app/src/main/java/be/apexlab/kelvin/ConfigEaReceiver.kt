package be.apexlab.kelvin

import org.json.JSONObject
import java.io.IOException

/** Only the negotiated link session and logical config EA session may feed the config parser. */
internal class ConfigEaReceiver(
    private val linkSession: Int,
    private val eaSession: Int,
    onEvent: (JSONObject) -> Unit,
    onProtocolNotice: () -> Unit,
) {
    private val reader = RbProtocolReader(RbProtocolReader.Channel.CONFIG) { packet ->
        val event = packet.json
        if (event == null) onProtocolNotice() else onEvent(event)
    }

    fun accept(packets: List<Pair<Int, ByteArray>>) {
        for ((session, payload) in packets) {
            if (session != linkSession) continue
            if (payload.size < 2) {
                reader.clear()
                throw IOException("Truncated config EA session header")
            }
            val logicalSession = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            if (logicalSession != eaSession) continue
            reader.feed(payload, payload.size - 2, 2)
        }
    }
}
