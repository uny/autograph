package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource

private class RecordingTransport(
    override val stampsInPipeline: Boolean = false,
) : Transport {
    var envelopes: EnvelopeSource? = null
    val calls = mutableListOf<Triple<String, String, Envelope?>>()
    val trackedProperties = mutableListOf<JsonObject>()

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
    }

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("track", name, envelope)
        trackedProperties += properties
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("screen", name, envelope)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        calls += Triple("identify", userId, envelope)
    }
}

/**
 * A transport that stamps inside its own asynchronous, serial pipeline — like Segment on Android,
 * where `analytics.track()` enqueues an event and the envelope is stamped later, on the pipeline's
 * single dispatcher. Events (and resets) are stamped/applied in the order they were enqueued.
 */
private class FakePipelineTransport(
    private val scope: CoroutineScope,
) : Transport {
    override val stampsInPipeline: Boolean = true
    private lateinit var envelopes: EnvelopeSource

    /** Envelopes stamped inside the pipeline, in stamping order. */
    val stamped = mutableListOf<Pair<String, Envelope>>()

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
    }

    // The core passes envelope = null (stampsInPipeline); stamping is deferred into the pipeline.
    override fun track(name: String, properties: JsonObject, envelope: Envelope?) = enqueueStamp(name)
    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) = enqueueStamp(name)
    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) = enqueueStamp(userId)

    private fun enqueueStamp(name: String) {
        scope.launch { stamped += name to envelopes.stamp() }
    }

    override fun reset() {
        // Order the rotation within the pipeline, after any already-enqueued events.
        scope.launch { envelopes.reset() }
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
    fun eventTimestampIsCapturedAtCallTimeNotWhenTheDispatcherStamps() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        var now = 1_000L
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
            clock = { now }
        }

        // Fire at t=1000. Delivery is deferred to the dispatcher (not yet stamped).
        tracker.track("A")
        // Time advances while the event sits in the queue (e.g. disk I/O backpressure).
        now = 9_000L
        advanceUntilIdle()

        // event_timestamp must reflect the call time (1000ms), not the dequeue/stamp time (9000ms).
        val envelope = transport.calls.single().third!!
        assertEquals(
            Instant.fromEpochMilliseconds(1_000L).toString(),
            envelope.eventTimestamp,
            "event_timestamp lagged to the dispatcher's stamp time instead of the call time",
        )
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
    fun pipelineEventEnqueuedBeforeResetKeepsPreResetSession() = runTest {
        // Pipeline transports stamp asynchronously on their own dispatcher; drive that dispatcher
        // off the test scheduler so stamping order is observable and deterministic.
        val transport = FakePipelineTransport(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
        }

        tracker.track("first")
        advanceUntilIdle()
        val session1 = transport.stamped[0].second.session.id

        // Enqueue A onto the pipeline (not yet stamped), then reset. The core must NOT rotate the
        // session synchronously here: it must hand the reset to the transport, which orders it after
        // A within the pipeline.
        tracker.track("A")
        tracker.reset()
        advanceUntilIdle()

        // A was enqueued before the reset, so it keeps the pre-reset session and the next sequence
        // number (2) — it must not be re-attributed to the post-reset session (which would give seq 1).
        val a = transport.stamped[1].second
        assertEquals(session1, a.session.id, "pre-reset event was mis-attributed to the new session")
        assertEquals(2L, a.seq, "pre-reset event got a post-reset sequence number")

        // An event enqueued after the reset belongs to the new session, with its sequence restarted.
        tracker.track("B")
        advanceUntilIdle()
        val b = transport.stamped[2].second
        assertTrue(b.session.id != session1, "post-reset event must belong to a new session")
        assertEquals(1L, b.seq, "new session must restart the per-session sequence")
    }

    @Test
    fun targetIsMergedIntoPropertiesUnderTheReservedKey() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track("Recipe Saved", target = "share_button")

        val properties = transport.trackedProperties.single()
        assertEquals("share_button", properties["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun targetIsOmittedFromPropertiesWhenNull() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track("App Opened")

        assertTrue(!transport.trackedProperties.single().containsKey("target"))
    }

    @Test
    fun targetOverwritesAnExplicitTargetKeyAlreadyInProperties() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track(
            "Recipe Saved",
            properties = JsonObject(mapOf("target" to JsonPrimitive("caller-supplied"))),
            target = "share_button",
        )

        val properties = transport.trackedProperties.single()
        assertEquals("share_button", properties["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun invalidEventIsDroppedNotThrownWhenNotStrict() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            validator = EventValidator { name, _ -> if (name == "bad") "unknown event name" else null }
            strictValidation = false
        }

        tracker.track("bad")
        tracker.track("Recipe Saved")

        assertEquals(listOf("Recipe Saved"), transport.calls.map { it.second }, "the invalid event must be dropped, not delivered")
    }

    @Test
    fun aThrowingValidatorCannotCrashANonStrictBuild() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            validator = EventValidator { _, _ -> throw IllegalStateException("bug in the app's own validator") }
            strictValidation = false
        }

        tracker.track("anything")

        assertEquals(0, transport.calls.size, "a validator bug must drop the event, not deliver it")
    }

    @Test
    fun invalidEventThrowsWhenStrict() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            validator = EventValidator { name, _ -> if (name == "bad") "unknown event name" else null }
            strictValidation = true
        }

        assertFailsWith<IllegalArgumentException> { tracker.track("bad") }
        assertEquals(0, transport.calls.size, "the invalid event must never reach the transport")
    }

    @Test
    fun validatorAlsoAppliesToScreen() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            validator = EventValidator { _, properties -> if (!properties.containsKey("required")) "missing required property" else null }
        }

        tracker.screen("Home")

        assertEquals(0, transport.calls.size, "a screen event missing a required property must be dropped")
    }

    @Test
    fun noValidatorMeansEveryEventIsDelivered() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track("anything")

        assertEquals(1, transport.calls.size)
    }

    @Test
    fun droppedInvalidEventIsReportedThroughTheConfiguredLogger() {
        // #56: the "dropping invalid event" diagnostic must reach AutographConfig.logger, not println.
        val logs = mutableListOf<String>()
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            logger = AutographLogger { logs += it }
            validator = EventValidator { name, _ -> if (name == "bad") "unknown event name" else null }
            strictValidation = false
        }

        tracker.track("bad")

        assertEquals(0, transport.calls.size, "the invalid event must be dropped")
        assertEquals(1, logs.size, "the drop must be reported through the configured logger")
        assertTrue(logs.single().contains("bad") && logs.single().contains("unknown event name"), logs.single())
    }

    @Test
    fun deliveryFailureIsReportedThroughTheConfiguredLogger() {
        // #56: a transport throwing during delivery is swallowed (must not crash the app) but its
        // diagnostic must reach AutographConfig.logger rather than vanishing into println.
        val logs = mutableListOf<String>()
        val throwing = object : Transport {
            override fun connect(envelopes: EnvelopeSource) {}
            override fun track(name: String, properties: JsonObject, envelope: Envelope?): Unit =
                throw IllegalStateException("boom")
            override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {}
            override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {}
        }
        val tracker = Autograph {
            transport(throwing)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            logger = AutographLogger { logs += it }
        }

        tracker.track("A") // must not throw on the caller

        assertEquals(1, logs.size, "a delivery failure must reach the configured logger")
        assertTrue(logs.single().contains("event delivery failed") && logs.single().contains("boom"), logs.single())
    }

    @Test
    fun aThrowingLoggerCannotCrashDelivery() {
        // #56: a buggy logger must no more crash the app than a buggy validator can. The dropped-event
        // diagnostic runs on the caller thread, so a throwing logger there would otherwise propagate to
        // track(). Fault injection: dropping the try/catch in AutographTracker.report reddens this.
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            logger = AutographLogger { throw IllegalStateException("logger bug") }
            validator = EventValidator { name, _ -> if (name == "bad") "unknown event name" else null }
        }

        tracker.track("bad")  // dropped → logger throws → must be swallowed, not propagated
        tracker.track("good") // must still be delivered

        assertEquals(listOf("good"), transport.calls.map { it.second }, "a throwing logger must not stop delivery")
    }

    @Test
    fun closeCancelsScopeSoLaterEventsAreDropped() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
        }

        tracker.track("before close")
        advanceUntilIdle()
        assertEquals(1, transport.calls.size)

        tracker.close()
        tracker.track("after close")
        advanceUntilIdle()

        assertEquals(1, transport.calls.size, "an event enqueued after close() must never reach the transport")
    }

    @Test
    fun closeDoesNotAffectAPipelineTransportsOwnDelivery() {
        // stampsInPipeline transports are handed events synchronously, bypassing this tracker's
        // scope entirely, so close() (which only cancels that scope) must not stop them.
        val transport = RecordingTransport(stampsInPipeline = true)
        val tracker = tracker(transport)

        tracker.close()
        tracker.track("still delivered")

        assertEquals(1, transport.calls.size)
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

    // #52: close() used to be `scope.cancel()`, which dropped every enqueued-but-unstamped event —
    // a silent loss on a *normal* shutdown, in a library whose headline guarantee is that ordering
    // information is never silently lost.
    //
    // These two use the real, serial background dispatcher (the production default) rather than
    // Unconfined or StandardTestDispatcher, because the property under test only exists when events are
    // genuinely still queued at close(): Unconfined delivers inline, and a virtual-time scheduler needs
    // an advance that close()'s blocking drain cannot give it. SlowRecordingTransport spends a moment
    // per event so the burst below is certain to be backed up when close() is called — without it, a
    // fast enough dispatcher could drain everything first and the assertion would hold even for the old
    // cancelling implementation, i.e. prove nothing.

    @Test
    fun closeDeliversEventsThatWereStillQueued() {
        val transport = SlowRecordingTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            // No dispatcher override: the production one, off the caller's thread.
        }

        val n = 50
        repeat(n) { tracker.track("E$it") }
        assertTrue(
            transport.names.size < n,
            "precondition: events must still be queued when close() is called, or this proves nothing",
        )

        tracker.close()

        assertEquals(n, transport.names.size, "close() must deliver every event it accepted")
    }

    @Test
    fun closeIsIdempotentAndStopsAcceptingWork() {
        val transport = SlowRecordingTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
        }

        tracker.track("before")
        tracker.close()
        val deliveredAtClose = transport.names.size
        tracker.close() // must not throw, block again, or deliver anything new
        tracker.track("after")

        assertEquals(listOf("before"), transport.names)
        assertEquals(deliveredAtClose, transport.names.size, "a second close() delivers nothing new")
    }
}

/**
 * Records delivered event names, spending [perEventMillis] on each so that a burst of `track` calls is
 * genuinely still queued on the serial dispatcher by the time `close()` runs. Busy-waits rather than
 * sleeps because [Transport.track] is not a suspending function and `Thread.sleep` has no common
 * equivalent; the total (a few tens of milliseconds) is small enough not to matter to the suite.
 */
private class SlowRecordingTransport(private val perEventMillis: Int = 2) : Transport {
    val names = mutableListOf<String>()

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        val start = TimeSource.Monotonic.markNow()
        @Suppress("ControlFlowWithEmptyBody")
        while (start.elapsedNow().inWholeMilliseconds < perEventMillis) {
        }
        names += name
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        names += name
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        names += userId
    }
}
