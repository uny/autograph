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

/** Stamps the Autograph envelope onto every event passing through Segment's pipeline. */
internal class AutographPlugin(
    private val envelopes: EnvelopeSource,
) : Plugin {

    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    override fun execute(event: BaseEvent): BaseEvent {
        val envelope = envelopes.stamp()
        event.messageId = envelope.eventId
        event.context = JsonObject(event.context + ("instrumentation" to envelope.toJson()))
        return event
    }
}
