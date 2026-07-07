package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The instrumentation envelope stamped onto every event.
 *
 * Transports place it under `context.instrumentation` (or equivalent) and may reuse
 * [eventId] as their message id for deduplication.
 */
public data class Envelope(
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
) {
    /** Serializes this envelope as a JSON object for embedding into event context. */
    public fun toJson(): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("session_id", session.id)
        put("session_start", session.startEpochMillis)
        seq?.let { put("seq", it) }
        globalSeq?.let { put("global_seq", it) }
        put("sdk", sdk)
    }
}
