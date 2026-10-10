package dev.ynagai.autograph

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #287: the reserved `__autograph` key moves an event's metadata out of `properties` and into the envelope. */
class EventMetadataTest {

    private class Delivered(val name: String, val properties: JsonObject, val envelope: Envelope?, val metadata: EventMetadata?)

    /** A plain transport: every event arrives through [Transport.track] / [Transport.screen]. */
    private open class PlainTransport(override val stampsInPipeline: Boolean) : Transport {
        val tracked = mutableListOf<Delivered>()
        val screened = mutableListOf<Delivered>()

        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            tracked += Delivered(name, properties.asJsonObject(), envelope, null)
        }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            screened += Delivered(name, properties.asJsonObject(), envelope, null)
        }

        override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {}
    }

    private class CapableTransport(stampsInPipeline: Boolean) : PlainTransport(stampsInPipeline), MetadataAwareTransport {
        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
            tracked += Delivered(name, properties.asJsonObject(), envelope, metadata)
        }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
            screened += Delivered(name, properties.asJsonObject(), envelope, metadata)
        }
    }

    private fun tracker(
        transport: Transport,
        logs: MutableList<String> = mutableListOf(),
        configure: AutographConfig.() -> Unit = {},
    ): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
        dispatcher = Dispatchers.Unconfined
        logger = AutographLogger { logs += it }
        configure()
    }

    private val impressionMetadata = buildJsonObject {
        put("kind", "impression")
        putJsonObject("impression") {
            put("min_duration_ms", 500)
            put("min_fraction_visible", 0.5)
        }
    }

    private val screenViewMetadata = buildJsonObject { put("screen_view_id", "visit-1") }

    private fun props(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

    // ---- core-stamped transports: the metadata rides on the envelope ----

    @Test
    fun metadataMovesFromPropertiesIntoTheEnvelope() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track(
            "Hero Seen",
            props("slot" to JsonPrimitive("top"), RESERVED_METADATA_KEY to impressionMetadata),
        )

        val event = transport.tracked.single()
        assertEquals(props("slot" to JsonPrimitive("top")), event.properties)
        assertEquals(EventMetadata("impression", 500L, 0.5, null), event.envelope?.metadata)
        val json = event.envelope!!.toJson()
        assertEquals("impression", json["kind"]?.jsonPrimitive?.content)
        assertEquals(
            buildJsonObject {
                put("min_duration_ms", 500)
                put("min_fraction_visible", 0.5)
            },
            json["impression"],
        )
    }

    @Test
    fun anEventWithoutMetadataSerializesExactlyAsBefore() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track("Recipe Saved")

        val envelope = transport.tracked.single().envelope!!
        assertNull(envelope.metadata)
        assertEquals(
            setOf("event_id", "session_id", "session_start", "seq", "sdk", "event_timestamp"),
            envelope.toJson().keys,
        )
    }

    @Test
    fun theValidatorSeesThePropertiesWithoutTheReservedKey() {
        val seen = mutableListOf<JsonObject>()
        val transport = PlainTransport(stampsInPipeline = false)
        val tracker = tracker(transport) {
            validator = EventValidator { _, properties ->
                seen.add(properties.asJsonObject())
                if (RESERVED_METADATA_KEY in properties) "reserved key leaked" else null
            }
            strictValidation = true
        }

        tracker.track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))
        tracker.screen("Home", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals(listOf(JsonObject(emptyMap()), JsonObject(emptyMap())), seen)
        assertEquals("click", transport.tracked.single().envelope?.metadata?.kind)
    }

    // #242: a screen view carries its visit's id, so the reserved key is read on `screen` as on `track`.
    @Test
    fun aScreenViewCarriesItsMetadataOnTheEnvelope() {
        val transport = PlainTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.screen("Home", props("tab" to JsonPrimitive("recipes"), RESERVED_METADATA_KEY to screenViewMetadata))
        tracker.screen("Settings")

        val (home, settings) = transport.screened
        assertEquals(props("tab" to JsonPrimitive("recipes")), home.properties)
        assertEquals(EventMetadata(null, null, null, "visit-1"), home.envelope?.metadata)
        assertEquals("visit-1", home.envelope!!.toJson()["screen_view_id"]?.jsonPrimitive?.content)
        assertNull(settings.envelope?.metadata, "a plain screen call carries none")
    }

    @Test
    fun aScreenViewIdAloneIsMetadata() {
        fun parse(value: JsonElement) = extractMetadata(props(RESERVED_METADATA_KEY to value)).second

        assertEquals(EventMetadata(null, null, null, "visit-1"), parse(screenViewMetadata))
        assertEquals(EventMetadata("click", null, null, "visit-1"), parse(buildJsonObject { put("kind", "click"); put("screen_view_id", "visit-1") }))
        // An empty or non-string id is dropped on its own.
        assertNull(parse(buildJsonObject { put("screen_view_id", "") }))
        assertNull(parse(buildJsonObject { put("screen_view_id", 1) }))
        assertEquals(EventMetadata("click", null, null, null), parse(buildJsonObject { put("kind", "click"); put("screen_view_id", 1) }))
    }

    @Test
    fun aMalformedValueIsStillRemoved() {
        val malformed = listOf(
            JsonPrimitive("click"),
            JsonArray(listOf(JsonPrimitive("click"))),
            kotlinx.serialization.json.JsonNull,
            buildJsonObject { put("kind", 1) },
            buildJsonObject { put("unknown", "x") },
        )
        val transport = PlainTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        malformed.forEach { tracker.track("Event", props(RESERVED_METADATA_KEY to it)) }

        assertEquals(malformed.size, transport.tracked.size)
        transport.tracked.forEach {
            assertEquals(JsonObject(emptyMap()), it.properties)
            assertNull(it.envelope?.metadata)
        }
    }

    @Test
    fun eachFieldIsCheckedOnItsOwn() {
        fun parse(value: JsonElement) = extractMetadata(props(RESERVED_METADATA_KEY to value)).second

        // A bad threshold drops that threshold only.
        assertEquals(
            EventMetadata("impression", null, 0.5, null),
            parse(
                buildJsonObject {
                    put("kind", "impression")
                    putJsonObject("impression") {
                        put("min_duration_ms", -1)
                        put("min_fraction_visible", 0.5)
                    }
                },
            ),
        )
        // Out of range, a string where a number belongs, and an empty kind are all dropped.
        assertNull(
            parse(
                buildJsonObject {
                    put("kind", "")
                    putJsonObject("impression") {
                        put("min_duration_ms", "500")
                        put("min_fraction_visible", 1.5)
                    }
                },
            ),
        )
        // A non-integer duration is not truncated into one.
        assertNull(parse(buildJsonObject { putJsonObject("impression") { put("min_duration_ms", 1.5) } }))
        // A bad kind, a bad fraction, or a non-object impression drops only itself.
        assertEquals(
            EventMetadata(null, 500L, null, null),
            parse(
                buildJsonObject {
                    put("kind", 1)
                    putJsonObject("impression") {
                        put("min_duration_ms", 500)
                        put("min_fraction_visible", -0.1)
                    }
                },
            ),
        )
        assertEquals(
            EventMetadata("click", null, null, null),
            parse(buildJsonObject { put("kind", "click"); put("impression", "500") }),
        )
    }

    @Test
    fun theRangeBoundsAreInclusive() {
        fun parse(duration: Long, fraction: Double) = extractMetadata(
            props(
                RESERVED_METADATA_KEY to buildJsonObject {
                    putJsonObject("impression") {
                        put("min_duration_ms", duration)
                        put("min_fraction_visible", fraction)
                    }
                },
            ),
        ).second

        assertEquals(EventMetadata(null, 0L, 0.0, null), parse(0L, 0.0))
        assertEquals(EventMetadata(null, 0L, 1.0, null), parse(0L, 1.0))
    }

    @Test
    fun theMetadataCannotOverwriteTheEnvelope() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track(
            "Event",
            props(
                RESERVED_METADATA_KEY to buildJsonObject {
                    put("kind", "click")
                    put("event_id", "forged")
                    put("seq", 99)
                    put("session_id", "forged")
                },
            ),
        )

        val json = transport.tracked.single().envelope!!.toJson()
        assertNotEquals("forged", json["event_id"]?.jsonPrimitive?.content)
        assertEquals(1L, json["seq"]?.jsonPrimitive?.content?.toLong())
        assertNotEquals("forged", json["session_id"]?.jsonPrimitive?.content)
        assertEquals("click", json["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun aDefaultUnderTheReservedKeyIsRemovedAndNotReadAsMetadata() {
        val transport = PlainTransport(stampsInPipeline = false)
        val defaults = DefaultProperties().apply {
            set(RESERVED_METADATA_KEY, buildJsonObject { put("kind", "click") })
            set("tenant", JsonPrimitive("acme"))
        }
        val tracker = tracker(transport) { defaultProperties = defaults }

        tracker.track("Recipe Saved")
        tracker.track("Hero Seen", props(RESERVED_METADATA_KEY to impressionMetadata))
        tracker.screen("Home")

        val (plain, withMetadata) = transport.tracked
        assertEquals(props("tenant" to JsonPrimitive("acme")), plain.properties)
        assertNull(plain.envelope?.metadata, "a default must not supply metadata")
        assertEquals(props("tenant" to JsonPrimitive("acme")), withMetadata.properties)
        assertEquals("impression", withMetadata.envelope?.metadata?.kind, "the call site's metadata is still read")
        assertEquals(props("tenant" to JsonPrimitive("acme")), transport.screened.single().properties)
    }

    @Test
    fun theReservedTargetStillWins() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track(
            "Button Tapped",
            props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }),
            target = "save",
        )

        assertEquals(props("target" to JsonPrimitive("save")), transport.tracked.single().properties)
    }

    // ---- pipeline transports: the metadata is handed over through MetadataAwareTransport ----

    @Test
    fun aCapablePipelineTransportReceivesTheMetadataAlongsideTheEvent() {
        val transport = CapableTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(transport, logs)

        tracker.track("Hero Seen", props("slot" to JsonPrimitive("top"), RESERVED_METADATA_KEY to impressionMetadata))
        tracker.track("Recipe Saved")

        val (withMetadata, plain) = transport.tracked
        assertEquals(props("slot" to JsonPrimitive("top")), withMetadata.properties)
        assertEquals(EventMetadata("impression", 500L, 0.5, null), withMetadata.metadata)
        assertNull(withMetadata.envelope, "a pipeline transport stamps for itself")
        assertNull(plain.metadata, "an event without metadata goes through Transport.track")
        assertEquals(emptyList(), logs)
    }

    @Test
    fun aCapablePipelineTransportReceivesAScreenViewsMetadata() {
        val transport = CapableTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(transport, logs)

        tracker.screen("Home", props(RESERVED_METADATA_KEY to screenViewMetadata))
        tracker.screen("Settings")

        val (home, settings) = transport.screened
        assertEquals(props(), home.properties)
        assertEquals(EventMetadata(null, null, null, "visit-1"), home.metadata)
        assertNull(settings.metadata, "a screen view without metadata goes through Transport.screen")
        assertEquals(emptyList(), logs)
    }

    @Test
    fun anIncapablePipelineTransportGetsTheScreenViewWithoutMetadataAndTheSameSingleWarning() {
        val transport = PlainTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(transport, logs)

        tracker.screen("Home", props(RESERVED_METADATA_KEY to screenViewMetadata))
        tracker.track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals(props(), transport.screened.single().properties)
        assertEquals(1, transport.tracked.size)
        assertEquals(1, logs.size, "one warning for the tracker, whichever event type lost metadata first: $logs")
    }

    @Test
    fun anIncapablePipelineTransportGetsTheEventWithoutMetadataAndOneWarning() {
        val transport = PlainTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(transport, logs)

        tracker.track("Recipe Saved")
        assertEquals(emptyList(), logs, "an event without metadata loses nothing, so it must not warn")

        tracker.track("Hero Seen", props(RESERVED_METADATA_KEY to impressionMetadata))
        tracker.track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals(3, transport.tracked.size, "every event is still delivered")
        transport.tracked.forEach { assertFalse(RESERVED_METADATA_KEY in it.properties) }
        assertEquals(1, logs.size, "the warning is per tracker, not per event: $logs")
        assertTrue(logs.single().contains("MetadataAwareTransport"), logs.single())
        assertTrue(logs.single().contains("Wrapping a transport"), "points a wrapper's author at the README: ${logs.single()}")
    }

    // ---- caller-written wrappers (#302): the README's "Wrapping a transport" contract ----

    /** The README's `RedactingTransport`. */
    private class RedactingTransport(private val delegate: Transport) : Transport, MetadataAwareTransport {
        override val stampsInPipeline: Boolean get() = delegate.stampsInPipeline
        override fun connect(envelopes: EnvelopeSource) = delegate.connect(envelopes)
        override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) =
            delegate.identify(userId, redact(traits), envelope)
        override fun flush() = delegate.flush()
        override fun reset() = delegate.reset()

        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) =
            delegate.track(name, redact(properties), envelope)

        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) =
            if (delegate is MetadataAwareTransport) {
                delegate.track(name, redact(properties), envelope, metadata)
            } else {
                delegate.track(name, redact(properties), envelope)
            }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) =
            delegate.screen(name, redact(properties), envelope)

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) =
            if (delegate is MetadataAwareTransport) {
                delegate.screen(name, redact(properties), envelope, metadata)
            } else {
                delegate.screen(name, redact(properties), envelope)
            }

        private fun redact(properties: Map<String, JsonElement>) = properties - "email"
    }

    @Test
    fun aReadmeWrapperOverAPipelineTransportRedactsBothRoutesAndKeepsTheMetadata() {
        val delegate = CapableTransport(stampsInPipeline = true)
        val tracker = tracker(RedactingTransport(delegate))
        val email = "email" to JsonPrimitive("a@example.com")

        tracker.track("Recipe Saved", props(email))
        tracker.track("Button Tapped", props(email, RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))
        tracker.screen("Settings", props(email))
        tracker.screen("Home", props(email, RESERVED_METADATA_KEY to screenViewMetadata))

        assertEquals(listOf(null, "click"), delegate.tracked.map { it.metadata?.kind })
        assertEquals(listOf(null, "visit-1"), delegate.screened.map { it.metadata?.screenViewId })
        (delegate.tracked + delegate.screened).forEach { assertFalse("email" in it.properties, "redacted on every route") }
    }

    @Test
    fun aReadmeWrapperOverACoreStampedTransportKeepsTheMetadataOnTheEnvelope() {
        val delegate = PlainTransport(stampsInPipeline = false)

        tracker(RedactingTransport(delegate)).track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals("click", delegate.tracked.single().envelope?.metadata?.kind)
    }

    @Test
    fun aWrapperThatDeclaresTheCapabilityOverAnIncapableDelegateIsTrustedSoTheTrackerDoesNotWarn() {
        // The README tells a wrapper's author to report the loss themselves; this is why.
        val delegate = PlainTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()

        tracker(RedactingTransport(delegate), logs).track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals("Button Tapped", delegate.tracked.single().name)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun aCapableCoreStampedTransportStillGetsTheMetadataOnTheEnvelope() {
        // The capability is only the pipeline route; a transport the core stamps for reads the envelope.
        val transport = CapableTransport(stampsInPipeline = false)

        tracker(transport).track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        val event = transport.tracked.single()
        assertNull(event.metadata)
        assertEquals("click", event.envelope?.metadata?.kind)
    }

    // ---- DebugTransport must not hide the capability ----

    @Test
    fun debugTransportForwardsTheMetadataToACapableDelegate() {
        val delegate = CapableTransport(stampsInPipeline = true)
        val lines = mutableListOf<String>()
        val logs = mutableListOf<String>()

        tracker(DebugTransport(delegate) { lines += it }, logs)
            .track("Hero Seen", props(RESERVED_METADATA_KEY to impressionMetadata))

        assertEquals(EventMetadata("impression", 500L, 0.5, null), delegate.tracked.single().metadata)
        assertTrue(lines.single().contains("\"kind\":\"impression\""), lines.single())
        assertEquals(emptyList(), logs)
    }

    @Test
    fun debugTransportForwardsAScreenViewsMetadataToACapableDelegate() {
        val delegate = CapableTransport(stampsInPipeline = true)
        val lines = mutableListOf<String>()

        tracker(DebugTransport(delegate) { lines += it }).screen("Home", props(RESERVED_METADATA_KEY to screenViewMetadata))

        assertEquals(EventMetadata(null, null, null, "visit-1"), delegate.screened.single().metadata)
        assertTrue(lines.single().contains("\"screen_view_id\":\"visit-1\""), lines.single())
    }

    @Test
    fun debugTransportCalledDirectlyWithAScreenViewOverAnIncapableDelegateStillDeliversIt() {
        val delegate = PlainTransport(stampsInPipeline = true)

        DebugTransport(delegate) {}.screen("Home", JsonObject(emptyMap()), null, EventMetadata(null, null, null, "visit-1"))

        assertEquals("Home", delegate.screened.single().name)
    }

    @Test
    fun debugTransportOverAnIncapableDelegateWarnsOnceThroughTheLoggerLikeTheDelegateAlone() {
        val delegate = PlainTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(DebugTransport(DebugTransport(delegate) {}) {}, logs)

        tracker.track("Hero Seen", props(RESERVED_METADATA_KEY to impressionMetadata))
        tracker.track("Button Tapped", props(RESERVED_METADATA_KEY to buildJsonObject { put("kind", "click") }))

        assertEquals(2, delegate.tracked.size)
        delegate.tracked.forEach { assertEquals(JsonObject(emptyMap()), it.properties) }
        assertEquals(1, logs.size, logs.toString())
        assertTrue(logs.single().contains("MetadataAwareTransport"), logs.single())
        // The warning names the transport that drops the metadata, not the wrapper, which implements the capability.
        assertTrue(logs.single().startsWith("Autograph: PlainTransport "), logs.single())
    }

    @Test
    fun debugTransportCalledDirectlyOverAnIncapableDelegateStillDeliversTheEvent() {
        val delegate = PlainTransport(stampsInPipeline = true)

        DebugTransport(delegate) {}.track("Hero Seen", JsonObject(emptyMap()), null, EventMetadata("impression", 500L, 0.5, null))

        assertEquals("Hero Seen", delegate.tracked.single().name)
    }

    @Test
    fun aClosedTrackerDoesNotWarnAboutAnEventItRefused() {
        val transport = PlainTransport(stampsInPipeline = true)
        val logs = mutableListOf<String>()
        val tracker = tracker(transport, logs)
        tracker.close()

        tracker.track("Hero Seen", props(RESERVED_METADATA_KEY to impressionMetadata))

        assertEquals(emptyList(), transport.tracked)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun envelopeJsonNestsTheImpressionThresholds() {
        val json = EventMetadata(kind = null, impressionMinDurationMs = 250L, impressionMinFractionVisible = null, screenViewId = null).toJson()

        assertEquals(setOf("impression"), json.keys)
        assertEquals(setOf("min_duration_ms"), json["impression"]!!.jsonObject.keys)
    }

    @Test
    fun aKindWithoutThresholdsHasNoImpressionBlock() {
        val json = EventMetadata(kind = EventKinds.CLICK, impressionMinDurationMs = null, impressionMinFractionVisible = null, screenViewId = null).toJson()

        assertEquals(setOf("kind"), json.keys)
    }

    // ---- withEventMetadata: the builder the emit sites use ----

    @OptIn(AutographInternalApi::class)
    @Test
    fun withEventMetadataRoundTripsThroughTheTrackerIntoTheEnvelope() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track(
            "Hero Seen",
            props("slot" to JsonPrimitive("top"))
                .withEventMetadata(EventKinds.IMPRESSION, impressionMinDurationMs = 750, impressionMinFractionVisible = 0.3),
        )

        val event = transport.tracked.single()
        assertEquals(props("slot" to JsonPrimitive("top")), event.properties)
        assertEquals(
            EventMetadata(kind = EventKinds.IMPRESSION, impressionMinDurationMs = 750, impressionMinFractionVisible = 0.3, screenViewId = null),
            event.envelope?.metadata,
        )
    }

    @OptIn(AutographInternalApi::class)
    @Test
    fun withEventMetadataCarriesAScreenViewIdWithoutAKind() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).screen("Home", props().withEventMetadata(kind = null, screenViewId = "visit-1"))

        assertEquals(props(), transport.screened.single().properties)
        assertEquals(EventMetadata(null, null, null, "visit-1"), transport.screened.single().envelope?.metadata)
    }

    @OptIn(AutographInternalApi::class)
    @Test
    fun withEventMetadataReplacesWhateverWasUnderTheKey() {
        val properties = props(RESERVED_METADATA_KEY to impressionMetadata).withEventMetadata(EventKinds.CLICK)

        assertEquals(JsonPrimitive("""{"kind":"click"}"""), properties[RESERVED_METADATA_KEY])
    }

    // The emit sites write the value as a string so it survives the Kotlin/Native bridge into a Swift
    // Tracker; the tracker must read that string as it reads the object.
    @Test
    fun metadataEncodedAsAStringIsReadLikeTheObject() {
        val transport = PlainTransport(stampsInPipeline = false)

        tracker(transport).track("Hero Seen", props(RESERVED_METADATA_KEY to JsonPrimitive(impressionMetadata.toString())))

        val event = transport.tracked.single()
        assertEquals(props(), event.properties)
        assertEquals(
            EventMetadata(kind = EventKinds.IMPRESSION, impressionMinDurationMs = 500, impressionMinFractionVisible = 0.5, screenViewId = null),
            event.envelope?.metadata,
        )
    }

    @Test
    fun aStringThatIsNotAJsonObjectIsRemovedAndCarriesNothing() {
        val tooDeep = """{"kind":"click","x":""" + "[".repeat(10_000) + "]".repeat(10_000) + "}"
        val tooLong = """{"kind":"click","x":"""" + "a".repeat(2_000) + "\"}"
        // Each of these three parses to a valid `click` without its bound, so they fail if a bound goes.
        val tooManyBrackets = """{"kind":"click","x":[[[[[[[[]]]]]]]]}"""
        for (raw in listOf("click", "{not json", "[1,2]", "\"click\"", "", tooDeep, tooLong, tooManyBrackets)) {
            val transport = PlainTransport(stampsInPipeline = false)

            tracker(transport).track("Hero Seen", props(RESERVED_METADATA_KEY to JsonPrimitive(raw)))

            val event = transport.tracked.single()
            assertEquals(props(), event.properties, "for ${raw.take(40)}")
            assertNull(event.envelope?.metadata, "for ${raw.take(40)}")
        }
    }
}
