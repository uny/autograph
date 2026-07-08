package dev.ynagai.autograph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingTransport(
    override val stampsInPipeline: Boolean = false,
) : Transport {
    var envelopes: EnvelopeSource? = null
    val calls = mutableListOf<Triple<String, String, Envelope?>>()

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
    }

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("track", name, envelope)
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("screen", name, envelope)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        calls += Triple("identify", userId, envelope)
    }
}

class AutographTrackerTest {

    private fun tracker(transport: Transport): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
        // Run the off-main stamping dispatch synchronously so assertions can read the result
        // right after the call. Unconfined executes each launch to completion inline (the worker
        // body never suspends), preserving call order.
        dispatcher = Dispatchers.Unconfined
    }

    @Test
    fun coreStampsWhenTransportDoesNot() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track("Recipe Saved")
        tracker.screen("RecipeDetail")

        assertEquals(2, transport.calls.size)
        val (_, _, first) = transport.calls[0]
        val (_, _, second) = transport.calls[1]
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
    }

    @Test
    fun coreStampsOffTheCallerThreadInCallOrder() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
        }

        tracker.track("A")
        tracker.screen("B")
        tracker.identify("u")
        // Fire-and-forget: nothing is stamped/delivered on the calling thread.
        assertEquals(0, transport.calls.size, "delivery must be deferred to the dispatcher")

        advanceUntilIdle()

        // All delivered, and stamped in call order despite the async hand-off.
        assertEquals(listOf("track", "screen", "identify"), transport.calls.map { it.first })
        assertEquals(listOf(1L, 2L, 3L), transport.calls.map { it.third?.seq })
    }

    @Test
    fun eventEnqueuedBeforeResetKeepsPreResetSessionAndSequence() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
        }

        tracker.track("first")
        advanceUntilIdle()
        val session1 = transport.calls[0].third!!.session.id

        // Enqueue A (deferred to the dispatcher), then reset synchronously on the caller thread.
        tracker.track("A")
        tracker.reset()
        advanceUntilIdle()

        // A was enqueued before reset, so it must still belong to the pre-reset session and take
        // the next sequence number (2), not be mis-attributed to the new post-reset session (seq 1).
        val a = transport.calls[1].third!!
        assertEquals(session1, a.session.id, "pre-reset event was mis-attributed to the new session")
        assertEquals(2L, a.seq, "pre-reset event got a post-reset sequence number")
    }

    @Test
    fun coreDoesNotStampWhenTransportStampsInPipeline() {
        val transport = RecordingTransport(stampsInPipeline = true)
        val tracker = tracker(transport)

        tracker.track("Recipe Saved")

        assertNull(transport.calls.single().third)
        assertNotNull(transport.envelopes, "transport must receive the envelope source on connect")
        assertTrue(transport.envelopes!!.stamp().seq == 1L)
    }
}
