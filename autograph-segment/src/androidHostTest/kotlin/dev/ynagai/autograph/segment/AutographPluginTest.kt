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
}
