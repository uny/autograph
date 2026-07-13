package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * Provides instrumentation envelopes. Implemented by the library core; transports that
 * run their own event pipeline (e.g. Segment) call [stamp] from inside that pipeline so
 * that sequence numbers match the actual enqueue order — including events the transport
 * generates itself, such as lifecycle events.
 */
public interface EnvelopeSource {
    /**
     * Returns a new envelope, recording the current time as its `event_timestamp`. Thread-safe;
     * each call advances the sequence counters. Transports that stamp inside their own pipeline
     * call this, since the app's original call time isn't available to them.
     */
    public fun stamp(): Envelope

    /**
     * Returns a new envelope recording [eventTimestampMillis] as its `event_timestamp` — captured
     * at the `track`/`screen`/`identify` call site so the timestamp reflects when the app fired the
     * event, not when the serial dispatcher later drained and stamped it (which can lag under
     * backpressure). Thread-safe; each call advances the sequence counters. The default ignores the
     * timestamp and delegates to [stamp]; the library core overrides it to honor the value.
     */
    public fun stamp(eventTimestampMillis: Long): Envelope = stamp()

    /**
     * Rotates the session (e.g. on logout): starts a fresh session id and per-session sequence.
     * Device-lifetime counters are preserved. Thread-safe.
     *
     * A transport that stamps in its own pipeline ([Transport.stampsInPipeline]) is responsible
     * for calling this **from inside that pipeline**, ordered after any events already enqueued,
     * so a reset and the events preceding it keep their relative order — an event enqueued before
     * the reset must keep the pre-reset session, not be re-attributed to the new one. Transports
     * that let the core stamp never call this; the core drives their reset itself.
     */
    public fun reset()
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
