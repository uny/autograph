package dev.ynagai.autograph.segment

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.InMemoryStorageProvider
import com.segment.analytics.kotlin.core.utilities.EncodeDefaultsJson
import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.AutographLogger
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.test.testEnvelope
import dev.ynagai.autograph.test.testEventMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** #287: an event's metadata reaches Segment's `context.instrumentation`, never its `properties`. */
class SegmentMetadataTest {

    private val envelope = testEnvelope(eventId = "evt-123", sessionId = "sess-1", seq = 7L)

    private val source = object : EnvelopeSource {
        override fun stamp(): Envelope = envelope
        override fun reset() {}
    }

    private fun event() = TrackEvent(properties = JsonObject(emptyMap()), event = "Hero Seen").apply {
        messageId = "original-message-id"
        context = buildJsonObject { }
    }

    @Test
    fun metadataExtendsTheStampedInstrumentationBlock() {
        val stamped = AutographPlugin(source).execute(event())

        val result = stamped.withMetadata(testEventMetadata(kind = "impression", impressionMinFractionVisible = 0.5))

        val instrumentation = result.context["instrumentation"]!!.jsonObject
        assertEquals("evt-123", instrumentation["event_id"]?.jsonPrimitive?.content, "the envelope is kept")
        assertEquals("impression", instrumentation["kind"]?.jsonPrimitive?.content)
        assertEquals("0.5", instrumentation["impression"]!!.jsonObject["min_fraction_visible"]?.jsonPrimitive?.content)
    }

    /**
     * [AutographPlugin] reads any `instrumentation` block as "already stamped". If the metadata created
     * one on an event the plugin had not reached, the plugin would skip it and the envelope would be lost.
     */
    @Test
    fun metadataNeverCreatesTheBlockOnAnUnstampedEvent() {
        val unstamped = event().withMetadata(testEventMetadata(kind = "click"))
        assertNull(unstamped.context["instrumentation"])

        val stamped = AutographPlugin(source).execute(unstamped)
        assertEquals("evt-123", stamped.messageId)
    }

    /** A real `Analytics` that talks to nothing, with every event it delivers collected into [captured]. */
    private fun analytics(captured: MutableList<BaseEvent>): Analytics {
        val analytics = Analytics(
            Configuration(
                writeKey = "autograph-test",
                application = Any(),
                storageProvider = InMemoryStorageProvider(),
                autoAddSegmentDestination = false,
                // Nothing listens here: the settings fetch fails at once and Segment starts on its defaults.
                cdnHost = "127.0.0.1:9/v1",
            ),
        )
        analytics.add(
            object : Plugin {
                override val type = Plugin.Type.After
                override lateinit var analytics: Analytics
                override fun execute(event: BaseEvent): BaseEvent = event.also { captured += it }
            },
        )
        return analytics
    }

    /**
     * End to end through a real `Analytics`: the enrichment closure must run after [AutographPlugin]
     * (Segment documents and implements Before → Enrichment → closure → Destination), and the reserved
     * key must be gone from the serialized `properties`.
     */
    @Test
    fun throughARealAnalyticsTheKindLandsInInstrumentationAndNotInProperties() {
        val captured = CopyOnWriteArrayList<BaseEvent>()
        val analytics = analytics(captured)
        val logs = CopyOnWriteArrayList<String>()
        val tracker = Autograph {
            transport(SegmentTransport(analytics))
            store = InMemorySeqStore()
            logger = AutographLogger { logs += it }
        }

        tracker.track(
            "Hero Seen",
            buildJsonObject {
                put("slot", "top")
                putJsonObject(RESERVED_METADATA_KEY) {
                    put("kind", "impression")
                    putJsonObject("impression") { put("min_duration_ms", 500) }
                }
            },
        )
        tracker.track("Recipe Saved")

        val deadline = System.currentTimeMillis() + 15_000
        while (captured.count { it is TrackEvent } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // By name, not position: on a JVM host Segment can deliver these out of call order with or
        // without an enrichment closure, which is its behavior, not this test's subject.
        val tracks = captured.filterIsInstance<TrackEvent>().associateBy { it.event }
        assertEquals(setOf("Hero Seen", "Recipe Saved"), tracks.keys, "both events reach the After plugin")
        val hero = tracks.getValue("Hero Seen")
        val plain = tracks.getValue("Recipe Saved")

        val serialized = EncodeDefaultsJson.encodeToJsonElement(TrackEvent.serializer(), hero).jsonObject
        assertEquals(JsonObject(mapOf("slot" to JsonPrimitive("top"))), serialized["properties"])
        val instrumentation = serialized["context"]!!.jsonObject["instrumentation"]!!.jsonObject
        assertEquals("impression", instrumentation["kind"]?.jsonPrimitive?.content)
        assertEquals("500", instrumentation["impression"]!!.jsonObject["min_duration_ms"]?.jsonPrimitive?.content)
        assertEquals(hero.messageId, instrumentation["event_id"]?.jsonPrimitive?.content, "stamped before the closure ran")

        val plainInstrumentation = plain.context["instrumentation"]!!.jsonObject
        assertFalse("kind" in plainInstrumentation, "a plain track carries no kind")
        assertEquals(emptyList(), logs.toList(), "SegmentTransport is capable, so nothing is dropped")
    }

    /** #242: a screen view's id reaches `context.instrumentation` through `screen`'s enrichment closure. */
    @Test
    fun throughARealAnalyticsAScreenViewIdLandsInInstrumentationAndNotInProperties() {
        val captured = CopyOnWriteArrayList<BaseEvent>()
        val logs = CopyOnWriteArrayList<String>()
        val tracker = Autograph {
            transport(SegmentTransport(analytics(captured)))
            store = InMemorySeqStore()
            logger = AutographLogger { logs += it }
        }

        tracker.screen(
            "Home",
            buildJsonObject {
                put("tab", "recipes")
                put(RESERVED_METADATA_KEY, """{"screen_view_id":"visit-1"}""")
            },
        )
        tracker.screen("Settings")

        val deadline = System.currentTimeMillis() + 15_000
        while (captured.count { it is ScreenEvent } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val screens = captured.filterIsInstance<ScreenEvent>().associateBy { it.name }
        assertEquals(setOf("Home", "Settings"), screens.keys, "both screen views reach the After plugin")
        val home = screens.getValue("Home")

        val serialized = EncodeDefaultsJson.encodeToJsonElement(ScreenEvent.serializer(), home).jsonObject
        assertEquals(JsonObject(mapOf("tab" to JsonPrimitive("recipes"))), serialized["properties"])
        val instrumentation = serialized["context"]!!.jsonObject["instrumentation"]!!.jsonObject
        assertEquals("visit-1", instrumentation["screen_view_id"]?.jsonPrimitive?.content)
        assertEquals(home.messageId, instrumentation["event_id"]?.jsonPrimitive?.content, "stamped before the closure ran")

        assertFalse("screen_view_id" in screens.getValue("Settings").context["instrumentation"]!!.jsonObject)
        assertEquals(emptyList(), logs.toList())
    }
}
