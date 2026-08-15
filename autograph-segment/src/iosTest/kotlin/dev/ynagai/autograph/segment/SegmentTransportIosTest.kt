package dev.ynagai.autograph.segment

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.SequenceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeSegmentBridge : SegmentBridge {
    // `payloadJson` is recorded because the bridge's contract is that it is *JSON*: the transport
    // takes the `Map` interface, and only a `JsonObject` stringifies to JSON. Dropping this
    // parameter is what let a non-JSON payload ship unnoticed once already (#193 follow-up).
    data class Call(val payloadJson: String, val messageId: String, val instrumentationJson: String)

    val tracks = mutableListOf<Call>()
    val screens = mutableListOf<Call>()
    val identifies = mutableListOf<Call>()

    override fun track(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {
        tracks += Call(propertiesJson, messageId, instrumentationJson)
    }

    override fun screen(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {
        screens += Call(propertiesJson, messageId, instrumentationJson)
    }

    override fun identify(userId: String, traitsJson: String, messageId: String, instrumentationJson: String) {
        identifies += Call(traitsJson, messageId, instrumentationJson)
    }

    override fun flush() {}
    override fun reset() {}
}

class SegmentTransportIosTest {

    @Test
    fun identifyForwardsEnvelopeAndLeavesNoSequenceGap() {
        val bridge = FakeSegmentBridge()
        val tracker = Autograph {
            transport(SegmentTransport(bridge))
            store = InMemorySeqStore()
            sequence = SequenceMode.PerSession
            dispatcher = Dispatchers.Unconfined // stamp synchronously so assertions can read results
        }

        tracker.track("A")
        tracker.identify("user-1")
        tracker.track("B")

        // identify carries the stamped envelope (event_id + instrumentation), like track/screen.
        assertEquals(1, bridge.identifies.size)
        val identify = bridge.identifies.single()
        assertTrue(identify.messageId.isNotEmpty(), "iOS identify must carry the event_id as messageId")
        assertTrue(
            identify.instrumentationJson.contains("\"seq\":2"),
            "identify must emit the sequence number it consumed (2), leaving no phantom gap: ${identify.instrumentationJson}",
        )

        // The surrounding track events keep 1 and 3 — with identify emitting 2, the stream is contiguous.
        assertTrue(bridge.tracks[0].instrumentationJson.contains("\"seq\":1"))
        assertTrue(bridge.tracks[1].instrumentationJson.contains("\"seq\":3"))
    }

    /**
     * The transport is called directly, with a plain map — the shape its `Map<String, JsonElement>`
     * parameter now admits and a Swift caller of this public class produces. Going through
     * [Autograph] instead would narrow the map before the transport ever saw it, which is exactly
     * why the suite was blind to this: `Map.toString()` emits `{k="v"}`, not JSON, and the Swift
     * adapter's `JSONSerialization` decode of that returns nil, dropping every property silently.
     */
    @Test
    fun payloadsAreJsonEvenWhenTheCallerPassesAPlainMap() {
        val bridge = FakeSegmentBridge()
        val transport = SegmentTransport(bridge)
        val properties = mapOf<String, JsonElement>("plan" to JsonPrimitive("pro"))

        transport.track("Upgraded", properties, null)
        transport.screen("Checkout", properties, null)
        transport.identify("user-1", properties, null)

        val expected = """{"plan":"pro"}"""
        assertEquals(expected, bridge.tracks.single().payloadJson)
        assertEquals(expected, bridge.screens.single().payloadJson)
        assertEquals(expected, bridge.identifies.single().payloadJson)
    }
}
