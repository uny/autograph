package dev.ynagai.autograph

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** #302: `Transport.intercept`, the library-owned replacement for a hand-written transport wrapper. */
class TransportInterceptTest {

    private class Delivered(val name: String, val properties: JsonObject, val envelope: Envelope?, val metadata: EventMetadata?)

    private open class PlainTransport(override val stampsInPipeline: Boolean) : Transport {
        val tracked = mutableListOf<Delivered>()
        val screened = mutableListOf<Delivered>()
        val identified = mutableListOf<Delivered>()
        var connected: EnvelopeSource? = null
        var flushed = 0
        var resets = 0

        override fun connect(envelopes: EnvelopeSource) {
            connected = envelopes
        }

        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            tracked += Delivered(name, properties.asJsonObject(), envelope, null)
        }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            screened += Delivered(name, properties.asJsonObject(), envelope, null)
        }

        override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
            identified += Delivered(userId, traits.asJsonObject(), envelope, null)
        }

        override fun flush() {
            flushed++
        }

        override fun reset() {
            resets++
        }
    }

    private class CapableTransport(stampsInPipeline: Boolean) : PlainTransport(stampsInPipeline), MetadataAwareTransport {
        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
            tracked += Delivered(name, properties.asJsonObject(), envelope, metadata)
        }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
            screened += Delivered(name, properties.asJsonObject(), envelope, metadata)
        }
    }

    private fun tracker(transport: Transport, logs: MutableList<String> = mutableListOf()): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
        dispatcher = Dispatchers.Unconfined
        logger = AutographLogger { logs += it }
    }

    private fun props(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

    private val email = "email" to JsonPrimitive("a@example.com")
    private val click = RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }
    private val visit = RESERVED_METADATA_KEY to buildJsonObject { put("screen_view_id", "visit-1") }
    private val redact: (TransportEvent) -> TransportEvent = { it.withProperties(it.properties - "email") }

    @Test
    fun forwardsEveryMemberThatIsNotAnEvent() {
        for (pipeline in listOf(false, true)) {
            val delegate = PlainTransport(stampsInPipeline = pipeline)
            val transport = delegate.intercept { it }
            val envelopes = object : EnvelopeSource {
                override fun stamp(): Envelope = error("unused")
                override fun reset() {}
            }

            transport.connect(envelopes)
            transport.flush()
            transport.reset()

            assertEquals(pipeline, transport.stampsInPipeline)
            assertSame(envelopes, delegate.connected)
            assertEquals(1, delegate.flushed)
            assertEquals(1, delegate.resets)
        }
    }

    @Test
    fun eachCallBecomesOneEventOfItsTypeAndOnlyThePropertiesCanChange() {
        val delegate = PlainTransport(stampsInPipeline = false)
        val seen = mutableListOf<String>()
        val tracker = tracker(
            delegate.intercept { event ->
                seen += "${event.type}:${event.name}"
                event.withProperties(event.properties + ("added" to JsonPrimitive(event.type)))
            },
        )

        tracker.track("Recipe Saved", props(email))
        tracker.screen("Home", props(email))
        tracker.identify("user-1", props(email))

        assertEquals(listOf("track:Recipe Saved", "screen:Home", "identify:user-1"), seen)
        assertEquals(props(email, "added" to JsonPrimitive("track")), delegate.tracked.single().properties)
        assertEquals(props(email, "added" to JsonPrimitive("screen")), delegate.screened.single().properties)
        assertEquals("user-1", delegate.identified.single().name, "identify's user id is read-only")
        assertEquals(props(email, "added" to JsonPrimitive("identify")), delegate.identified.single().properties)
        delegate.tracked.single().envelope.let { assertTrue(it != null && it.eventId.isNotEmpty(), "the envelope passes through") }
    }

    @Test
    fun aReturnedEventsTypeAndNameAreIgnored() {
        val delegate = PlainTransport(stampsInPipeline = false)
        val other = TransportEvent(TransportEventTypes.SCREEN, "Elsewhere", props("p" to JsonPrimitive(1)))

        tracker(delegate.intercept { other }).track("Recipe Saved")

        assertEquals("Recipe Saved", delegate.tracked.single().name)
        assertEquals(props("p" to JsonPrimitive(1)), delegate.tracked.single().properties)
        assertEquals(emptyList(), delegate.screened)
    }

    @Test
    fun returningNullDropsTheEventOfEveryType() {
        val delegate = CapableTransport(stampsInPipeline = true)
        val tracker = tracker(delegate.intercept { null })

        tracker.track("Recipe Saved")
        tracker.track("Button Tapped", props(click))
        tracker.screen("Home", props(visit))
        tracker.identify("user-1")

        assertEquals(emptyList(), delegate.tracked)
        assertEquals(emptyList(), delegate.screened)
        assertEquals(emptyList(), delegate.identified)
    }

    @Test
    fun overACapablePipelineTransportBothRoutesAreTransformedOnceAndKeepTheMetadata() {
        val delegate = CapableTransport(stampsInPipeline = true)
        var calls = 0
        val logs = mutableListOf<String>()
        val tracker = tracker(delegate.intercept { calls++; redact(it) }, logs)

        tracker.track("Recipe Saved", props(email))
        tracker.track("Button Tapped", props(email, click))
        tracker.screen("Settings", props(email))
        tracker.screen("Home", props(email, visit))

        assertEquals(4, calls, "one transform per event, whichever overload delivers it")
        assertEquals(listOf(null, "click"), delegate.tracked.map { it.metadata?.kind })
        assertEquals(listOf(null, "visit-1"), delegate.screened.map { it.metadata?.screenViewId })
        (delegate.tracked + delegate.screened).forEach { assertFalse("email" in it.properties, "redacted on every route") }
        assertEquals(emptyList(), logs)
    }

    @Test
    fun overACoreStampedTransportTheMetadataStaysOnTheEnvelope() {
        val delegate = PlainTransport(stampsInPipeline = false)

        tracker(delegate.intercept(redact)).track("Button Tapped", props(email, click))

        val event = delegate.tracked.single()
        assertEquals("click", event.envelope?.metadata?.kind)
        assertFalse("email" in event.properties)
    }

    @Test
    fun declaresTheMetadataCapabilityExactlyWhenTheTransportUnderneathHasIt() {
        assertIs<MetadataAwareTransport>(CapableTransport(true).intercept { it })
        assertIsNot<MetadataAwareTransport>(PlainTransport(true).intercept { it })
        // Looking through the library's own wrappers, which declare the capability unconditionally.
        assertIsNot<MetadataAwareTransport>(DebugTransport(PlainTransport(true)) {}.intercept { it })
        assertIs<MetadataAwareTransport>(DebugTransport(CapableTransport(true)) {}.intercept { it }.intercept { it })
        assertIsNot<MetadataAwareTransport>(PlainTransport(true).intercept { it }.intercept { it })
    }

    @Test
    fun overAnIncapablePipelineTransportTheTrackerWarnsOnceAndNamesThatTransport() {
        for (wrap in listOf<(Transport) -> Transport>(
            { it.intercept(redact) },
            { DebugTransport(it.intercept(redact)) {} },
            { DebugTransport(it) {}.intercept(redact) },
        )) {
            val delegate = PlainTransport(stampsInPipeline = true)
            val logs = mutableListOf<String>()
            val tracker = tracker(wrap(delegate), logs)

            tracker.track("Button Tapped", props(email, click))
            tracker.screen("Home", props(email, visit))

            assertEquals(1, delegate.tracked.size)
            assertEquals(1, delegate.screened.size)
            (delegate.tracked + delegate.screened).forEach {
                assertEquals(JsonObject(emptyMap()), it.properties, "delivered, redacted, without the reserved key")
            }
            assertEquals(1, logs.size, logs.toString())
            assertTrue(logs.single().startsWith("Autograph: PlainTransport "), logs.single())
        }
    }

    @Test
    fun debugTransportAroundAnInterceptedCapableTransportForwardsTheMetadata() {
        val delegate = CapableTransport(stampsInPipeline = true)
        val lines = mutableListOf<String>()

        tracker(DebugTransport(delegate.intercept(redact)) { lines += it }).track("Button Tapped", props(email, click))

        assertEquals("click", delegate.tracked.single().metadata?.kind)
        assertFalse("email" in delegate.tracked.single().properties)
        assertTrue(lines.single().contains("email"), "DebugTransport logs what reached it, before the transform: ${lines.single()}")
    }

    @Test
    fun aThrowingTransformDropsTheEventAndIsReportedLikeAFailingTransport() {
        for (pipeline in listOf(false, true)) {
            val delegate = PlainTransport(stampsInPipeline = pipeline)
            val logs = mutableListOf<String>()
            val tracker = tracker(delegate.intercept { error("boom") }, logs)

            tracker.track("Recipe Saved")

            assertEquals(emptyList(), delegate.tracked)
            assertTrue(logs.any { "boom" in it }, logs.toString())
        }
    }

    @Test
    fun aReservedMetadataKeyTheTransformAddsIsRemoved() {
        val delegate = PlainTransport(stampsInPipeline = false)

        tracker(delegate.intercept { it.withProperties(it.properties + click) }).track("Recipe Saved")

        assertFalse(RESERVED_METADATA_KEY in delegate.tracked.single().properties)
    }

    @Test
    fun aTrackEventsPropertiesIncludeTheReservedTarget() {
        val delegate = PlainTransport(stampsInPipeline = false)
        var seen: Map<String, JsonElement>? = null

        tracker(delegate.intercept { seen = it.properties; it }).track("Button Tapped", props(), target = "save")

        assertEquals(JsonPrimitive("save"), seen?.get("target"))
    }

    @Test
    fun aKotlinDelegateStillReceivesNestedValuesAsObjects() {
        val delegate = PlainTransport(stampsInPipeline = false)
        val nested = buildJsonObject { put("b", 1) }

        tracker(delegate.intercept { it }).track("Recipe Saved", props("o" to nested))

        assertEquals(nested, delegate.tracked.single().properties["o"])
    }

    @Test
    fun aDroppedEventStillConsumedASequenceNumberOnACoreStampedTransport() {
        // Pinned because it is documented on `intercept`: the envelope is stamped before the transform runs.
        val delegate = PlainTransport(stampsInPipeline = false)
        var drop = false
        val tracker = tracker(delegate.intercept { if (drop) null else it })

        tracker.track("First")
        drop = true
        tracker.track("Sampled Out")
        drop = false
        tracker.track("Third")

        val (first, third) = delegate.tracked
        assertEquals(2L, third.envelope!!.seq!! - first.envelope!!.seq!!)
        assertNull(delegate.tracked.firstOrNull { it.name == "Sampled Out" })
    }
}
