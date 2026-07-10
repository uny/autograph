package dev.ynagai.autograph.segment

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.Transport
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        analytics.track(name, properties)
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        analytics.screen(name, properties)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        analytics.identify(userId, traits)
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
        val envelope = envelopes.stamp()
        event.messageId = envelope.eventId
        event.context = JsonObject(event.context + ("instrumentation" to envelope.toJson()))
        return event
    }
}
