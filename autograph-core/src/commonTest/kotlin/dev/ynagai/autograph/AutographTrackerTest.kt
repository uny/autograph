package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

private class RecordingTransport(
    override val stampsInPipeline: Boolean = false,
) : Transport {
    var envelopes: EnvelopeSource? = null
    val calls = mutableListOf<Triple<String, String, Envelope?>>()
    val trackedProperties = mutableListOf<JsonObject>()
    val screenedProperties = mutableListOf<JsonObject>()
    val identifiedTraits = mutableListOf<JsonObject>()

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
    }

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        calls += Triple("track", name, envelope)
        trackedProperties += properties.asJsonObject()
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        calls += Triple("screen", name, envelope)
        screenedProperties += properties.asJsonObject()
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
        calls += Triple("identify", userId, envelope)
        identifiedTraits += traits.asJsonObject()
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
    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) = enqueueStamp(name)
    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) = enqueueStamp(name)
    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) = enqueueStamp(userId)

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

    /**
     * [asJsonObject] is the single narrowing every entry point funnels through, and its wrapping
     * branch is the one that actually runs on the path #193 is about — every Kotlin caller passes a
     * real [JsonObject] and short-circuits, so nothing else in the suite reaches it with entries.
     */
    @Test
    fun asJsonObjectWrapsAForeignMapWithoutLosingEntriesAndPassesAJsonObjectThrough() {
        val foreign: Map<String, JsonElement> =
            mutableMapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive(2))

        val wrapped = foreign.asJsonObject()
        assertEquals("1", wrapped["a"]?.jsonPrimitive?.content)
        assertEquals("2", wrapped["b"]?.jsonPrimitive?.content)
        assertEquals(2, wrapped.size)
        assertEquals("""{"a":"1","b":2}""", wrapped.toString(), "must stringify as JSON, not as a map")

        // A JsonObject is handed back as-is — the check that keeps this free for Kotlin callers.
        val already = JsonObject(mapOf("a" to JsonPrimitive("1")))
        assertSame(already, already.asJsonObject())
    }

    /**
     * The properties/traits parameters are the `Map` *interface* (#193), so a caller may legally
     * hand over a map it still owns and keeps mutating. Every entry point must therefore snapshot
     * on the caller's thread at call time — not later, on the delivery dispatcher, which would
     * record values the app never fired and can tear the copy under a concurrent mutation.
     */
    @Test
    fun propertiesAreSnapshotAtCallTimeNotWhenTheDispatcherDelivers() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
        }

        val properties = mutableMapOf<String, JsonElement>("plan" to JsonPrimitive("free"))
        val traits = mutableMapOf<String, JsonElement>("plan" to JsonPrimitive("free"))
        tracker.track("Upgraded", properties)
        tracker.identify("u1", traits)
        // The caller reuses its own maps the instant the fire-and-forget calls return.
        properties["plan"] = JsonPrimitive("pro")
        traits["plan"] = JsonPrimitive("pro")

        advanceUntilIdle()

        assertEquals("free", transport.trackedProperties.single()["plan"]?.jsonPrimitive?.content)
        assertEquals("free", transport.identifiedTraits.single()["plan"]?.jsonPrimitive?.content)
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

    // ---- #253: DefaultProperties ----

    private fun trackerWithDefaults(
        transport: Transport,
        defaults: DefaultProperties,
        validator: EventValidator? = null,
    ): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
        dispatcher = Dispatchers.Unconfined
        defaultProperties = defaults
        this.validator = validator
    }

    @Test
    fun defaultPropertiesReachTrackAndScreen() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply { set("tenant", JsonPrimitive("acme")) }
        val tracker = trackerWithDefaults(transport, defaults)

        tracker.track("Recipe Saved")
        tracker.screen("Home")

        assertEquals("acme", transport.trackedProperties.single()["tenant"]?.jsonPrimitive?.content)
        assertEquals("acme", transport.screenedProperties.single()["tenant"]?.jsonPrimitive?.content)
    }

    /**
     * The point of #253: a tracking plan may require a key that only a default supplies. The control
     * arm is the same validator on a tracker without the default, which must drop both events — or a
     * validator that never looked at the key would pass the first half vacuously.
     */
    @Test
    fun aRequiredKeySuppliedOnlyByADefaultSatisfiesTheValidator() {
        val requireTenant = EventValidator { _, properties ->
            if (!properties.containsKey("tenant")) "missing required property tenant" else null
        }

        val control = RecordingTransport(stampsInPipeline = false)
        trackerWithDefaults(control, DefaultProperties(), requireTenant).apply {
            track("Recipe Saved")
            screen("Home")
        }
        assertEquals(0, control.calls.size, "without the default, the validator must drop both events")

        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply { set("tenant", JsonPrimitive("acme")) }
        trackerWithDefaults(transport, defaults, requireTenant).apply {
            track("Recipe Saved")
            screen("Home")
        }
        assertEquals(listOf("track", "screen"), transport.calls.map { it.first })
    }

    @Test
    fun aCallSitePropertyAndTheReservedTargetBothWinOverADefault() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply {
            set("tenant", JsonPrimitive("default"))
            set("target", JsonPrimitive("default"))
            set("install", JsonPrimitive("i-1"))
        }
        val tracker = trackerWithDefaults(transport, defaults)

        tracker.track(
            "Recipe Saved",
            properties = mapOf("tenant" to JsonPrimitive("call-site")),
            target = "share_button",
        )

        val properties = transport.trackedProperties.single()
        assertEquals("call-site", properties["tenant"]?.jsonPrimitive?.content)
        assertEquals("share_button", properties["target"]?.jsonPrimitive?.content)
        assertEquals("i-1", properties["install"]?.jsonPrimitive?.content, "a key nothing else sets keeps its default")

        tracker.screen("Home", properties = mapOf("tenant" to JsonPrimitive("call-site")))

        assertEquals("call-site", transport.screenedProperties.single()["tenant"]?.jsonPrimitive?.content)
    }

    /** Documented: `reset()` rotates the session and leaves the defaults alone. */
    @Test
    fun resetLeavesTheDefaultsInPlace() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply { set("tenant", JsonPrimitive("acme")) }
        val tracker = trackerWithDefaults(transport, defaults)

        tracker.reset()
        tracker.track("After Reset")

        assertEquals("acme", defaults.properties["tenant"]?.jsonPrimitive?.content)
        assertEquals("acme", transport.trackedProperties.single()["tenant"]?.jsonPrimitive?.content)
    }

    @Test
    fun identifyTraitsDoNotCarryDefaults() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply { set("tenant", JsonPrimitive("acme")) }
        val tracker = trackerWithDefaults(transport, defaults)

        tracker.identify("u1", mapOf("plan" to JsonPrimitive("pro")))

        assertEquals(setOf("plan"), transport.identifiedTraits.single().keys)
    }

    /** An event reflects the defaults at call time, not when the dispatcher later delivers it. */
    @Test
    fun defaultsAreSnapshotAtCallTimeNotWhenTheDispatcherDelivers() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply { set("variant", JsonPrimitive("a")) }
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = StandardTestDispatcher(testScheduler)
            defaultProperties = defaults
        }

        tracker.track("Checkout Started")
        defaults.set("variant", JsonPrimitive("b"))
        tracker.track("Checkout Completed")
        assertEquals(0, transport.calls.size, "precondition: nothing is delivered before the dispatcher runs")

        advanceUntilIdle()

        assertEquals(
            listOf("a", "b"),
            transport.trackedProperties.map { it["variant"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun aChangeToTheDefaultsReachesTheNextEvent() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val defaults = DefaultProperties()
        val tracker = trackerWithDefaults(transport, defaults)

        tracker.track("e0")
        defaults.set("tenant", JsonPrimitive("acme"))
        defaults.set("install", JsonPrimitive("i-1"))
        tracker.track("e1")
        defaults.remove("install")
        tracker.track("e2")
        defaults.replaceAll(mapOf("tenant" to JsonPrimitive("globex"), "ring" to JsonPrimitive("beta")))
        tracker.track("e3")
        defaults.clear()
        tracker.track("e4")

        assertEquals(
            listOf(
                emptyMap(),
                mapOf("tenant" to "acme", "install" to "i-1"),
                mapOf("tenant" to "acme"),
                mapOf("tenant" to "globex", "ring" to "beta"),
                emptyMap(),
            ),
            transport.trackedProperties.map { props -> props.mapValues { it.value.jsonPrimitive.content } },
        )
    }

    /** `replaceAll` takes the `Map` interface (#193), so it must copy a map the caller keeps mutating. */
    @Test
    fun replaceAllCopiesTheCallersMap() {
        val defaults = DefaultProperties()
        val source = mutableMapOf<String, JsonElement>("tenant" to JsonPrimitive("acme"))

        defaults.replaceAll(source)
        source["tenant"] = JsonPrimitive("globex")

        assertEquals("acme", defaults.properties["tenant"]?.jsonPrimitive?.content)
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
            override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?): Unit =
                throw IllegalStateException("boom")
            override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {}
            override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {}
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
    fun aThrowingPipelineTransportIsReportedAndDoesNotCrashDelivery() {
        // #56 / CodeRabbit: a stampsInPipeline transport is delivered to synchronously on the caller
        // thread, bypassing the scope's CoroutineExceptionHandler. A throwing one must still be
        // swallowed and reported through the logger, honoring the never-crash-delivery contract on the
        // pipeline path too. Fault injection: removing the try/catch in deliver()'s pipeline branch
        // makes track("A") throw and reddens this.
        val logs = mutableListOf<String>()
        val throwing = object : Transport {
            override val stampsInPipeline: Boolean = true
            override fun connect(envelopes: EnvelopeSource) {}
            override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?): Unit =
                throw IllegalStateException("pipeline boom")
            override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {}
            override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {}
        }
        val tracker = Autograph {
            transport(throwing)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
            logger = AutographLogger { logs += it }
        }

        tracker.track("A") // pipeline delivery throws synchronously — must not propagate to the caller

        assertEquals(1, logs.size, "the pipeline delivery failure must reach the configured logger")
        assertTrue(logs.single().contains("event delivery failed") && logs.single().contains("pipeline boom"), logs.single())
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

    // #254: close() used to stop admission only when the core stamps, so swapping in a pipeline
    // transport silently changed what close() meant for the app.
    @Test
    fun closeStopsAdmissionForAPipelineTransportToo() {
        val transport = RecordingTransport(stampsInPipeline = true)
        val tracker = tracker(transport)

        tracker.track("before close")
        tracker.close()
        tracker.track("after close")
        tracker.screen("after close")
        tracker.identify("after close")

        assertEquals(listOf("before close"), transport.calls.map { it.second })
    }

    // #254: the admission check and the launch were two unguarded steps, so a call could pass the
    // check, lose the race to close(), and launch after the drain had been fixed — then be cancelled
    // with the scope. The clock is read inside the admission step, which makes it the hook: it fires
    // close() on another thread and gives it [CloseRace.WINDOW_MILLIS] to finish. Under the old code
    // close() finished inside that window and the event was lost; now close() cannot fix its drain
    // until the admission is complete, so the event is drained instead.
    @Test
    fun aCoreStampedCallRacingCloseIsDrainedNotLost() = runTest {
        val transport = RecordingTransport(stampsInPipeline = false)
        val race = CloseRace()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            clock = {
                race.fireOnce()
                Clock.System.now().toEpochMilliseconds()
            }
        }
        race.target = tracker

        race.armed = true
        tracker.track("racer")
        race.closing!!.join()

        assertEquals(listOf("racer"), transport.calls.map { it.second })
    }

    // The pipeline-mode half of the race above: the call is already inside the transport when close()
    // runs. close()'s flush must wait for it; before #254 it flushed at once, overtaking the call.
    @Test
    fun closeWaitsForAPipelineCallStillInsideTheTransportBeforeFlushing() = runTest {
        val race = CloseRace()
        val transport = OrderRecordingTransport(onTrack = race::fireOnce)
        val tracker = tracker(transport)
        race.target = tracker

        race.armed = true
        tracker.track("racer")
        race.closing!!.join()

        assertEquals(listOf("track racer", "flush"), transport.log)
    }

    @Test
    fun flushAndResetAfterCloseAreDroppedInBothTransportModes() {
        for (stampsInPipeline in listOf(true, false)) {
            val transport = OrderRecordingTransport(stampsInPipeline = stampsInPipeline)
            val tracker = tracker(transport)

            tracker.close()
            tracker.flush()
            tracker.reset()

            assertEquals(listOf("flush"), transport.log, "only close()'s own flush (stampsInPipeline=$stampsInPipeline)")
        }
    }

    // Codex on #260: a pipeline transport calling close() from inside one of the tracker's own calls
    // made close() wait on that very call — the full timeout, a false "gave up", and no flush.
    @Test
    fun aPipelineTransportClosingFromInsideItsOwnCallDoesNotWaitOnItself() {
        val logs = mutableListOf<String>()
        lateinit var tracker: Tracker
        val transport = OrderRecordingTransport(onTrack = { tracker.close() })
        tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            logger = AutographLogger { logs += it }
        }

        tracker.track("closer")

        assertEquals(listOf("flush", "track closer"), transport.log)
        assertTrue(logs.none { "gave up" in it }, "nothing was left to wait for: $logs")
    }

    // A pipeline call stuck past the bound must not cost everything already in the transport's queue its
    // flush: before #254 close() flushed a pipeline transport unconditionally.
    @Test
    fun aPipelineDrainCutShortStillFlushes() {
        val logs = mutableListOf<String>()
        val stuck = StuckCall()
        val transport = OrderRecordingTransport(onTrack = stuck::hold)
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            logger = AutographLogger { logs += it }
            closeDrainTimeoutMillis = 50
        }
        val caller = CoroutineScope(Dispatchers.Default).launch { tracker.track("stuck") }
        stuck.awaitEntered()

        tracker.close()
        val atClose = transport.log.toList()
        // Released before asserting: a failed assertion must not leave a Default thread spinning.
        stuck.release()
        kotlinx.coroutines.runBlocking { caller.join() }

        assertEquals(listOf("flush"), atClose)
        assertTrue(logs.any { "gave up" in it }, "the cut-short drain is still reported: $logs")
    }

    @Test
    fun closeReportsADrainCutShortByItsTimeout() {
        val logs = mutableListOf<String>()
        val tracker = Autograph {
            transport(RecordingTransport(stampsInPipeline = false))
            store = InMemorySeqStore()
            // Accepts work and never runs it, so the drain cannot finish.
            dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {}
            }
            logger = AutographLogger { logs += it }
            closeDrainTimeoutMillis = 50
        }

        tracker.track("stuck")
        tracker.close()

        assertTrue(logs.any { "gave up" in it }, "a drain cut short must be reported, not silent: $logs")
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

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val start = TimeSource.Monotonic.markNow()
        @Suppress("ControlFlowWithEmptyBody")
        while (start.elapsedNow().inWholeMilliseconds < perEventMillis) {
        }
        names += name
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        names += name
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
        names += userId
    }
}

/**
 * Fires [Tracker.close] on another thread from inside an admission, then gives it [WINDOW_MILLIS] to
 * finish before letting the admission continue — long enough for an unguarded close() to complete
 * and lose the racing call, which is what the tests using it pin as fixed. Busy-waits for the same
 * reason [SlowRecordingTransport] does.
 */
private class CloseRace {
    lateinit var target: Tracker

    @Volatile
    var armed = false

    @Volatile
    private var closeReturned = false

    var closing: Job? = null

    fun fireOnce() {
        if (!armed) return
        armed = false
        closing = CoroutineScope(Dispatchers.Default).launch {
            target.close()
            closeReturned = true
        }
        val start = TimeSource.Monotonic.markNow()
        @Suppress("ControlFlowWithEmptyBody")
        while (!closeReturned && start.elapsedNow().inWholeMilliseconds < WINDOW_MILLIS) {
        }
    }

    companion object {
        const val WINDOW_MILLIS = 500
    }
}

/** Holds a call inside the transport until [release], busy-waiting for the same reason [CloseRace] does. */
private class StuckCall {
    @Volatile
    private var entered = false

    @Volatile
    private var released = false

    fun hold() {
        entered = true
        @Suppress("ControlFlowWithEmptyBody")
        while (!released) {
        }
    }

    fun awaitEntered() {
        @Suppress("ControlFlowWithEmptyBody")
        while (!entered) {
        }
    }

    fun release() {
        released = true
    }
}

/** Records track, flush and reset in the order they reach it; a pipeline transport unless told otherwise. */
private class OrderRecordingTransport(
    override val stampsInPipeline: Boolean = true,
    private val onTrack: () -> Unit = {},
) : Transport {
    val log = mutableListOf<String>()

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        onTrack()
        log += "track $name"
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {}

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {}

    override fun flush() {
        log += "flush"
    }

    override fun reset() {
        log += "reset"
    }
}
