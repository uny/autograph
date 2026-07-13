package dev.ynagai.autograph.test

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.InMemorySeqStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryTestTransportTest {

    private fun transportAndTracker(): Pair<InMemoryTestTransport, dev.ynagai.autograph.Tracker> {
        val transport = InMemoryTestTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            // Stamps synchronously so assertions can read the result right after the call.
            dispatcher = Dispatchers.Unconfined
        }
        return transport to tracker
    }

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
