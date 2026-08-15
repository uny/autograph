package dev.ynagai.autograph

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wraps [delegate], logging every event before delivering it — for eyeballing outgoing events on
 * a real device/build during manual QA, separate from the app's production transport. Not
 * connected to a warehouse or any assertion tooling (see the planned `autograph-test` module for
 * unit-test assertions instead).
 *
 * The default [log] (`println`) dumps every event's full `properties`/`traits` — do not wrap a
 * production transport with this in a release build, since that can leak PII into device/console
 * logs. Gate its use behind a debug-build check, or supply a [log] that redacts what it prints.
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

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val props = properties.asJsonObject()
        logEvent("track", name, props, envelope)
        delegate.track(name, props, envelope)
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val props = properties.asJsonObject()
        logEvent("screen", name, props, envelope)
        delegate.screen(name, props, envelope)
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
        val props = traits.asJsonObject()
        logEvent("identify", userId, props, envelope)
        delegate.identify(userId, props, envelope)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun reset() {
        delegate.reset()
    }

    private fun logEvent(kind: String, name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        log("Autograph [$kind] \"$name\" properties=$properties envelope=${envelope?.toJson()}")
    }
}
