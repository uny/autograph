package dev.ynagai.autograph.segment

import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.utilities.SegmentInstant
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.test.testEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AutographPluginTest {

    private val envelope = testEnvelope(
        eventId = "evt-123",
        sessionId = "sess-1",
        sessionStartEpochMillis = 1000L,
        seq = 7L,
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

    /** Records the timestamp each stamp was asked for; null for the no-argument `stamp()`. */
    private class TimestampRecordingSource(private val envelope: Envelope) : EnvelopeSource {
        val requested = mutableListOf<Long?>()
        override fun stamp(): Envelope = envelope.also { requested += null }
        override fun stamp(eventTimestampMillis: Long): Envelope = envelope.also { requested += eventTimestampMillis }
        override fun reset() {}
    }

    // #254: the plugin used to call the no-argument stamp(), so event_timestamp recorded when Segment's
    // dispatcher reached the event rather than when the app fired it — a different observation point
    // from the core-stamped path, in a field the envelope contract calls stable.
    @Test
    fun stampsWithTheEventsOwnCreationTimeNotThePluginsRunTime() {
        val source = TimestampRecordingSource(envelope)
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "Recipe Saved").apply {
            messageId = "original-message-id"
            context = buildJsonObject { }
            timestamp = "2026-07-11T09:12:03.456Z"
        }

        AutographPlugin(source).execute(event)

        assertEquals(listOf<Long?>(Instant.parse("2026-07-11T09:12:03.456Z").toEpochMilliseconds()), source.requested)
    }

    // The format is Segment's, not ours: pin that what analytics-kotlin actually writes into
    // BaseEvent.timestamp parses, or every event would silently fall back to the plugin's run time.
    @Test
    fun segmentsOwnTimestampFormatParses() {
        val source = TimestampRecordingSource(envelope)
        val before = System.currentTimeMillis()
        val event = TrackEvent(properties = JsonObject(emptyMap()), event = "Recipe Saved").apply {
            messageId = "original-message-id"
            context = buildJsonObject { }
            timestamp = SegmentInstant.now()
        }

        AutographPlugin(source).execute(event)

        val requested = assertNotNull(source.requested.single(), "SegmentInstant.now() must parse")
        assertTrue(requested in before..System.currentTimeMillis(), "$requested outside [$before, now]")
    }

    @Test
    fun fallsBackToNowWhenTheEventHasNoUsableTimestamp() {
        val source = TimestampRecordingSource(envelope)
        val unset = TrackEvent(properties = JsonObject(emptyMap()), event = "Unset").apply {
            messageId = "m1"
            context = buildJsonObject { }
        }
        val garbage = TrackEvent(properties = JsonObject(emptyMap()), event = "Garbage").apply {
            messageId = "m2"
            context = buildJsonObject { }
            timestamp = "not a timestamp"
        }

        AutographPlugin(source).execute(unset)
        AutographPlugin(source).execute(garbage)

        assertEquals(listOf<Long?>(null, null), source.requested)
    }
}
