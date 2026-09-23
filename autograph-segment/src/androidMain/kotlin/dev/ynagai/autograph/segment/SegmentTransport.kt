package dev.ynagai.autograph.segment

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.Transport
import dev.ynagai.autograph.asJsonObject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Delivers events through Segment's `analytics-kotlin` SDK.
 *
 * Stamping happens inside Segment's pipeline (a `Before` plugin), so sequence numbers
 * match the order events are enqueued — including events the Segment SDK generates
 * itself, such as `Application Opened`. The envelope's `event_id` replaces Segment's
 * `messageId`, which makes Segment's message deduplication work on Autograph ids.
 *
 * `event_id`/`messageId` stability across a Segment-level retry follows from *when*
 * stamping happens: [AutographPlugin] runs once, synchronously, while the event is still
 * being enqueued — before it is written to Segment's local retry queue. A later retry
 * resends that already-serialized payload; it does not re-enqueue the event or re-run
 * `Before` plugins, so there is nothing left in the retry path that could reassign
 * `messageId`. [AutographPlugin.execute] is additionally idempotent (see its KDoc) as a
 * defensive guarantee for this same property, independent of that architectural argument.
 */
public class SegmentTransport(
    private val analytics: Analytics,
) : Transport {

    private lateinit var envelopes: EnvelopeSource

    override val stampsInPipeline: Boolean get() = true

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
        analytics.add(AutographPlugin(envelopes))
    }

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        analytics.track(name, properties.asJsonObject())
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        analytics.screen(name, properties.asJsonObject())
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
        analytics.identify(userId, traits.asJsonObject())
    }

    override fun flush() {
        analytics.flush()
    }

    override fun reset() {
        // reset() must be ordered *within* Segment's pipeline. analytics.track() enqueues each event
        // onto the serial analyticsDispatcher and AutographPlugin stamps it there, later; rotating
        // the session synchronously here would let an event enqueued just before logout stamp against
        // the new session. Post the rotation onto that same dispatcher so it runs after the events
        // already enqueued. analytics.reset() (which clears the Segment-side user) likewise posts onto
        // the dispatcher, after this rotation.
        analytics.analyticsScope.launch(analytics.analyticsDispatcher) { envelopes.reset() }
        analytics.reset()
    }
}

/**
 * Stamps the Autograph envelope onto every event passing through Segment's pipeline.
 *
 * `event_timestamp` is the event's own [BaseEvent.timestamp], not the time this plugin runs.
 * analytics-kotlin sets that field in `Analytics.process()` on the caller's thread, before it hands
 * the event to its dispatcher — so for `track`/`screen`/`identify` it is the call time, exactly what
 * the core records for a transport it stamps for, and for an event the Segment SDK generates itself
 * it is that event's creation time. Stamping with `now` instead would record however long the event
 * waited in Segment's queue. Two limits this plugin cannot defend: an event fired before Segment has
 * loaded its settings is held by Segment's `StartupQueue` (which runs ahead of every other `Before`
 * plugin) and replayed through `Analytics.process()`, which overwrites the field with the replay
 * time — so for that cold-start window `event_timestamp` is the replay time, as is Segment's own
 * `timestamp`; and a `Before` plugin the app registers ahead of this one could rewrite the field.
 *
 * Idempotent: an event that already carries an `instrumentation` block (i.e. this plugin
 * already ran on it) is returned unchanged rather than stamped again, so `messageId` can
 * never be reassigned to a different `event_id` no matter how many times the same
 * [BaseEvent] instance is run through this plugin.
 */
internal class AutographPlugin(
    private val envelopes: EnvelopeSource,
) : Plugin {

    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    override fun execute(event: BaseEvent): BaseEvent {
        if (event.context.containsKey("instrumentation")) return event
        val envelope = event.createdAtMillis()?.let(envelopes::stamp) ?: envelopes.stamp()
        event.messageId = envelope.eventId
        event.context = JsonObject(event.context + ("instrumentation" to envelope.toJson()))
        return event
    }
}

/**
 * [BaseEvent.timestamp] as epoch millis, or null when it is unset or unparseable — in which case the
 * caller falls back to stamping with the current time rather than dropping the event.
 */
private fun BaseEvent.createdAtMillis(): Long? = try {
    Instant.parse(timestamp).toEpochMilliseconds()
} catch (_: UninitializedPropertyAccessException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
