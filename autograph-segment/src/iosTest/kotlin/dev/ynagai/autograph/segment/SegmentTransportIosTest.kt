package dev.ynagai.autograph.segment

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.SequenceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeSegmentBridge : SegmentBridge {
    data class Call(val messageId: String, val instrumentationJson: String)

    val tracks = mutableListOf<Call>()
    val identifies = mutableListOf<Call>()

    override fun track(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {
        tracks += Call(messageId, instrumentationJson)
    }

    override fun screen(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {}

    override fun identify(userId: String, traitsJson: String, messageId: String, instrumentationJson: String) {
        identifies += Call(messageId, instrumentationJson)
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
}
