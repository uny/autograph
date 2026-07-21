package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The instrumentation envelope stamped onto every event.
 *
 * Transports place it under `context.instrumentation` (or equivalent) and may reuse
 * [eventId] as their message id for deduplication.
 *
 * **Produced by the library, never constructed by callers.** The constructor (and with it `copy`)
 * is `internal` so that new envelope fields stay binary-compatible additions forever — see
 * [ADR 0001](../../../../../../../docs/adr/0001-public-api-evolution.md) §2a. A transport that
 * needs an envelope calls [EnvelopeSource.stamp]; building one by hand would bypass event-id
 * uniqueness, session rotation, and sequence monotonicity, which is the whole value of the type.
 * Tests construct envelopes with `testEnvelope(...)` from `autograph-test`.
 *
 * New properties are appended at the end. That is binary-compatible but not *behavior*-compatible:
 * it changes what `equals`/`hashCode`/`toString` mean, so a caller keying a map on an envelope sees
 * a change with no compile error.
 */
@ConsistentCopyVisibility
public data class Envelope internal constructor(
    /** Unique event id, from the configured [EventIdGenerator]. */
    val eventId: String,
    /** The session this event belongs to. */
    val session: SessionInfo,
    /** Per-session sequence number starting at 1, or null when disabled. */
    val seq: Long?,
    /** Device-lifetime sequence number, or null when disabled. */
    val globalSeq: Long?,
    /** Name and version of this library, e.g. `autograph/0.1.0`. */
    val sdk: String,
    /**
     * When the app fired this event, as an ISO-8601 UTC instant. Captured at the
     * `track`/`screen`/`identify` call site (on the caller's thread), so it is independent both of
     * the serial dispatcher's own stamping lag and of whatever event-time field the transport ends
     * up stamping — either of which can lag behind by however long they batch/enqueue before
     * sending. (Transports that stamp inside their own pipeline record their processing time here
     * instead, since the app's call time isn't available to them.) Precision matches the platform
     * clock backing [kotlin.time.Clock.System] (millisecond resolution on the JVM and Android; may
     * be finer on other targets), not a guaranteed nanosecond timestamp.
     */
    val eventTimestamp: String,
    /**
     * The consumer's own event-schema version, from [AutographConfig.schemaVersion], or null
     * when unset. Distinct from [sdk]: this versions the adopter's tracking plan/contract, not
     * the library.
     */
    val schemaVersion: String? = null,
) {
    /** Serializes this envelope as a JSON object for embedding into event context. */
    public fun toJson(): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("session_id", session.id)
        put("session_start", session.startEpochMillis)
        seq?.let { put("seq", it) }
        globalSeq?.let { put("global_seq", it) }
        put("sdk", sdk)
        put("event_timestamp", eventTimestamp)
        schemaVersion?.let { put("schema_version", it) }
    }
}

/**
 * Builds an [Envelope] with caller-chosen field values, bypassing the stamper.
 *
 * Exists only so `autograph-test` can offer `testEnvelope(...)` across the module boundary that
 * `internal` does not cross. Use that instead: this signature carries no stability guarantee and
 * gains a parameter whenever [Envelope] gains a property.
 */
@AutographInternalApi
public fun createEnvelope(
    eventId: String,
    sessionId: String,
    sessionStartEpochMillis: Long,
    seq: Long?,
    globalSeq: Long?,
    sdk: String,
    eventTimestamp: String,
    schemaVersion: String? = null,
): Envelope = Envelope(
    eventId = eventId,
    session = SessionInfo(id = sessionId, startEpochMillis = sessionStartEpochMillis),
    seq = seq,
    globalSeq = globalSeq,
    sdk = sdk,
    eventTimestamp = eventTimestamp,
    schemaVersion = schemaVersion,
)
