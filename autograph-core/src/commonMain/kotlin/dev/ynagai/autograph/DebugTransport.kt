package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * Wraps [delegate], logging every event before delivering it — for eyeballing outgoing events on
 * a real device/build during manual QA, separate from the app's production transport. Not
 * connected to a warehouse or any assertion tooling (see the planned `autograph-test` module for
 * unit-test assertions instead).
 *
 * ```kotlin
 * val tracker = Autograph {
 *     transport(DebugTransport(SegmentTransport(analytics)))
 * }
 * ```
 *
 * When [delegate] stamps in its own pipeline ([Transport.stampsInPipeline]), the envelope isn't
 * known yet at the point this wrapper sees the event (the delegate stamps it later, inside its
 * own pipeline) — the logged line shows `envelope=null` in that case, matching what [delegate]
 * itself receives.
 */
public class DebugTransport(
    private val delegate: Transport,
    private val log: (String) -> Unit = ::println,
) : Transport {

    override val stampsInPipeline: Boolean get() = delegate.stampsInPipeline

    override fun connect(envelopes: EnvelopeSource) {
        delegate.connect(envelopes)
    }

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        logEvent("track", name, properties, envelope)
        delegate.track(name, properties, envelope)
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        logEvent("screen", name, properties, envelope)
        delegate.screen(name, properties, envelope)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        logEvent("identify", userId, traits, envelope)
        delegate.identify(userId, traits, envelope)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun reset() {
        delegate.reset()
    }

    private fun logEvent(kind: String, name: String, properties: JsonObject, envelope: Envelope?) {
        log("Autograph [$kind] \"$name\" properties=$properties envelope=${envelope?.toJson()}")
    }
}
