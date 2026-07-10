package dev.ynagai.autograph.segment

import com.segment.analytics.kotlin.core.TrackEvent
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.SessionInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutographPluginTest {

    private val envelope = Envelope(
        eventId = "evt-123",
        session = SessionInfo(id = "sess-1", startEpochMillis = 1000L),
        seq = 7L,
        globalSeq = null,
        sdk = "autograph/test",
        eventTimestamp = "2024-01-01T00:00:00Z",
    )

    private val source = object : EnvelopeSource {
        override fun stamp(): Envelope = envelope
        override fun reset() {}
    }

    @Test
    fun stampsMessageIdAndInstrumentationOntoEvent() {
        val plugin = AutographPlugin(source)
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "Recipe Saved").apply {
            messageId = "original-message-id"
            context = buildJsonObject { }
        }

        val result = plugin.execute(event)

        // event_id replaces Segment's messageId so downstream dedup keys on the Autograph id.
        assertEquals("evt-123", result.messageId)

        // The full envelope is merged under context.instrumentation.
        val instrumentation = result.context["instrumentation"]
        assertNotNull(instrumentation, "envelope must be merged under context.instrumentation")
        val json = instrumentation.toString()
        assertTrue(json.contains("\"event_id\":\"evt-123\""), json)
        assertTrue(json.contains("\"seq\":7"), json)
    }

    /**
     * `event_id`/`messageId` stability across a Segment retry rests on stamping happening
     * once, before the event reaches Segment's retry queue (see [AutographPlugin]'s KDoc) —
     * this repo can't drive a real network retry through analytics-kotlin's internals, but it
     * can pin down the concrete guarantee it does control: running the *same* [BaseEvent]
     * through the plugin more than once must never reassign it a different `messageId`.
     */
    @Test
    fun executeIsIdempotentSoMessageIdNeverChangesOnReprocessing() {
        var stampCount = 0
        val countingSource = object : EnvelopeSource {
            override fun stamp(): Envelope {
                stampCount++
                return envelope
            }
            override fun reset() {}
        }
        val plugin = AutographPlugin(countingSource)
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "Recipe Saved").apply {
            messageId = "original-message-id"
            context = buildJsonObject { }
        }

        val first = plugin.execute(event)
        val second = plugin.execute(first)

        assertEquals(1, stampCount, "an already-stamped event must not be stamped again")
        assertEquals("evt-123", second.messageId, "messageId must not change on reprocessing")
        assertEquals(first.context, second.context)
    }
}
