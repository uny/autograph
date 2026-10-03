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
 *
 * It is a [MetadataAwareTransport], so wrapping a pipeline transport does not hide that capability
 * from the tracker: an event's [EventMetadata] is logged and forwarded when [delegate] is one too.
 * When [delegate] is a pipeline transport that is not, the tracker looks through this wrapper and
 * treats the pair exactly as it treats [delegate] alone: the event is delivered without its metadata,
 * and [AutographConfig.logger] is told once.
 */
public class DebugTransport(
    private val delegate: Transport,
    private val log: (String) -> Unit = ::println,
) : Transport, MetadataAwareTransport {

    override val stampsInPipeline: Boolean get() = delegate.stampsInPipeline

    override fun connect(envelopes: EnvelopeSource) {
        delegate.connect(envelopes)
    }

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        val props = properties.asJsonObject()
        logEvent("track", name, props, envelope)
        delegate.track(name, props, envelope)
    }

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata) {
        val props = properties.asJsonObject()
        logEvent("track", name, props, envelope, metadata)
        if (delegate is MetadataAwareTransport) {
            delegate.track(name, props, envelope, metadata)
        } else {
            // The tracker does not route here for such a delegate (see [metadataReachesDelegate]); a direct
            // caller gets the same outcome it would: the event, without the metadata.
            delegate.track(name, props, envelope)
        }
    }

    /** Whether metadata handed to this wrapper reaches a transport that can use it, through any nesting. */
    internal val metadataReachesDelegate: Boolean
        get() = innermostDelegate is MetadataAwareTransport

    /** The first transport under this wrapper, through any nesting, that is not itself a [DebugTransport]. */
    internal val innermostDelegate: Transport
        get() = (delegate as? DebugTransport)?.innermostDelegate ?: delegate

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

    private fun logEvent(
        kind: String,
        name: String,
        properties: Map<String, JsonElement>,
        envelope: Envelope?,
        metadata: EventMetadata? = null,
    ) {
        val suffix = metadata?.let { " metadata=${it.toJson()}" }.orEmpty()
        log("Autograph [$kind] \"$name\" properties=$properties envelope=${envelope?.toJson()}$suffix")
    }
}
