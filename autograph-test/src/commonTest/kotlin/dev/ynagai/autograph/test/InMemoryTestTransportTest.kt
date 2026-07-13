package dev.ynagai.autograph.test

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.SequenceMode
import dev.ynagai.autograph.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryTestTransportTest {

    private fun transportAndTracker(sequence: SequenceMode = SequenceMode.PerSession): Pair<InMemoryTestTransport, dev.ynagai.autograph.Tracker> {
        val transport = InMemoryTestTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            this.sequence = sequence
            // Stamps synchronously so assertions can read the result right after the call.
            dispatcher = Dispatchers.Unconfined
        }
        return transport to tracker
    }

    private fun envelope(seq: Long?, globalSeq: Long?, sessionId: String = "session-1") = Envelope(
        eventId = "event-id",
        session = SessionInfo(id = sessionId, startEpochMillis = 0),
        seq = seq,
        globalSeq = globalSeq,
        sdk = "autograph/test",
        eventTimestamp = "2026-01-01T00:00:00.000Z",
    )

    @Test
    fun assertEventFiredMatchesByContainment() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("Recipe Saved", target = "share_button")

        transport.assertEventFired("Recipe Saved", properties = mapOf("target" to "share_button"))
    }

    @Test
    fun assertEventFiredExactRejectsExtraProperties() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("Recipe Saved", target = "share_button")

        assertFailsWith<EventAssertionError> {
            transport.assertEventFired("Recipe Saved", properties = emptyMap(), exact = true)
        }
        transport.assertEventFired("Recipe Saved", properties = mapOf("target" to "share_button"), exact = true)
    }

    @Test
    fun assertEventFiredThrowsWhenNameNeverFired() {
        val (transport, _) = transportAndTracker()

        assertFailsWith<EventAssertionError> {
            transport.assertEventFired("Nonexistent")
        }
    }

    @Test
    fun assertEventFiredThrowsWhenPropertiesDoNotMatch() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("Recipe Saved", target = "share_button")

        assertFailsWith<EventAssertionError> {
            transport.assertEventFired("Recipe Saved", properties = mapOf("target" to "other_button"))
        }
    }

    @Test
    fun assertEventNotFiredPassesWhenAbsentAndFailsWhenPresent() {
        val (transport, tracker) = transportAndTracker()

        transport.assertEventNotFired("Recipe Saved")

        tracker.track("Recipe Saved")

        assertFailsWith<EventAssertionError> {
            transport.assertEventNotFired("Recipe Saved")
        }
    }

    @Test
    fun assertScreenAndIdentifyFired() {
        val (transport, tracker) = transportAndTracker()

        tracker.screen("RecipeDetail")
        tracker.identify("user-42", traits = JsonObject(mapOf("plan" to JsonPrimitive("pro"))))

        transport.assertScreenFired("RecipeDetail")
        transport.assertIdentifyFired("user-42", traits = mapOf("plan" to "pro"))
    }

    @Test
    fun assertOrderChecksEventSequence() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("A")
        tracker.screen("B")
        tracker.identify("C")

        transport.assertOrder("A", "B", "C")

        assertFailsWith<EventAssertionError> {
            transport.assertOrder("A", "C", "B")
        }
    }

    @Test
    fun assertNoSeqGapsPassesForContiguousEvents() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("A")
        tracker.track("B")
        tracker.track("C")

        transport.assertNoSeqGaps()
        assertEquals(listOf(1L, 2L, 3L), transport.events.map { it.envelope?.seq })
    }

    @Test
    fun assertNoSeqGapsDoesNotFlagASessionRotationAsAGap() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("A")
        tracker.reset()
        tracker.track("B")

        // seq restarts at 1 for the new session — not a gap.
        transport.assertNoSeqGaps()
        assertFailsWith<EventAssertionError> { transport.assertSingleSession() }
    }

    @Test
    fun assertSingleSessionPassesWithinOneSessionAndReturnsItsId() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("A")
        tracker.track("B")

        val sessionId = transport.assertSingleSession()
        assertTrue(sessionId.isNotBlank())
    }

    @Test
    fun assertSingleSessionThrowsWhenNoEventCarriesAnEnvelope() {
        val transport = InMemoryTestTransport()

        assertFailsWith<EventAssertionError> {
            transport.assertSingleSession()
        }
    }

    @Test
    fun assertNoSeqGapsThrowsOnAnActualPerSessionGap() {
        val transport = InMemoryTestTransport()

        transport.track("A", JsonObject(emptyMap()), envelope(seq = 1L, globalSeq = null))
        transport.track("B", JsonObject(emptyMap()), envelope(seq = 3L, globalSeq = null))

        assertFailsWith<EventAssertionError> {
            transport.assertNoSeqGaps()
        }
    }

    @Test
    fun assertNoSeqGapsThrowsOnAGlobalSeqGap() {
        val (transport, tracker) = transportAndTracker(sequence = SequenceMode.PerDevice)

        tracker.track("A")
        tracker.track("B")
        // Fabricate a device-lifetime gap: a third event whose global_seq skipped a value.
        transport.track("C", JsonObject(emptyMap()), envelope(seq = null, globalSeq = 4L))

        assertFailsWith<EventAssertionError> {
            transport.assertNoSeqGaps()
        }
    }

    @Test
    fun assertNoSeqGapsSkipsEverythingUnderSequenceModeNone() {
        val (transport, tracker) = transportAndTracker(sequence = SequenceMode.None)

        tracker.track("A")
        tracker.track("B")

        assertEquals(listOf(null, null), transport.events.map { it.envelope?.seq })
        transport.assertNoSeqGaps() // no seq/global_seq stamped at all — nothing to check, must not throw
    }

    @Test
    fun assertEventFiredMatchesNestedMapAndListPropertiesByContainmentAndExact() {
        val (transport, tracker) = transportAndTracker()
        val properties = JsonObject(
            mapOf(
                "meta" to JsonObject(mapOf("nested" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))),
            ),
        )

        tracker.track("Recipe Saved", properties)

        transport.assertEventFired(
            "Recipe Saved",
            properties = mapOf("meta" to mapOf("nested" to listOf(1, 2))),
        )
        transport.assertEventFired(
            "Recipe Saved",
            properties = mapOf("meta" to mapOf("nested" to listOf(1, 2))),
            exact = true,
        )
        assertFailsWith<EventAssertionError> {
            transport.assertEventFired(
                "Recipe Saved",
                properties = mapOf("meta" to mapOf("nested" to listOf(1, 3))),
            )
        }
    }

    @Test
    fun assertEventFiredMatchesNullBooleanAndRawJsonElementProperties() {
        val (transport, tracker) = transportAndTracker()
        val properties = JsonObject(
            mapOf(
                "note" to kotlinx.serialization.json.JsonNull,
                "enabled" to JsonPrimitive(true),
                "raw" to JsonPrimitive(1),
            ),
        )

        tracker.track("Recipe Saved", properties)

        transport.assertEventFired(
            "Recipe Saved",
            properties = mapOf("note" to null, "enabled" to true, "raw" to JsonPrimitive(1)),
            exact = true,
        )
    }

    @Test
    fun clearDiscardsRecordedEvents() {
        val (transport, tracker) = transportAndTracker()

        tracker.track("A")
        assertEquals(1, transport.events.size)

        transport.clear()

        assertEquals(0, transport.events.size)
    }

    @Test
    fun flushAndResetAreCounted() {
        val (transport, tracker) = transportAndTracker()

        tracker.flush()
        tracker.reset()
        tracker.reset()

        assertEquals(1, transport.flushCount)
        assertEquals(2, transport.resetCount)
    }
}
