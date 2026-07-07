package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * Provides instrumentation envelopes. Implemented by the library core; transports that
 * run their own event pipeline (e.g. Segment) call [stamp] from inside that pipeline so
 * that sequence numbers match the actual enqueue order — including events the transport
 * generates itself, such as lifecycle events.
 */
public interface EnvelopeSource {
    /** Returns a new envelope. Thread-safe; each call advances the sequence counters. */
    public fun stamp(): Envelope
}

/**
 * SPI implemented by transport adapters (e.g. `autograph-segment`).
 *
 * Autograph deliberately owns no queueing, batching, or retry logic — those concerns
 * belong to the transport underneath.
 */
public interface Transport {

    /**
     * True when this transport calls [EnvelopeSource.stamp] inside its own pipeline.
     * In that case the core passes `envelope = null` to the event methods and the
     * transport is responsible for stamping every event exactly once.
     */
    public val stampsInPipeline: Boolean get() = false

    /** Called once during setup, before any events are sent. */
    public fun connect(envelopes: EnvelopeSource) {}

    public fun track(name: String, properties: JsonObject, envelope: Envelope?)

    public fun screen(name: String, properties: JsonObject, envelope: Envelope?)

    public fun identify(userId: String, traits: JsonObject, envelope: Envelope?)

    public fun flush() {}

    public fun reset() {}
}
