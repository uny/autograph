package dev.ynagai.autograph

import kotlin.uuid.Uuid

/**
 * Generates a unique identifier for each event.
 *
 * The generated id is stamped into the event envelope and — depending on the transport —
 * used as the transport-level message id (e.g. Segment's `messageId`), so downstream
 * systems can deduplicate on it.
 */
public fun interface EventIdGenerator {
    /** Returns a new unique id. Must be safe to call from any thread. */
    public fun next(): String
}

/** Built-in [EventIdGenerator] strategies. */
public object EventId {

    /**
     * UUIDv7 (RFC 9562): time-ordered, database-index friendly, and monotonic within
     * the lifetime of the process. The recommended default.
     *
     * Backed by the stdlib's [Uuid.generateV7], whose process-wide generator orders ids minted
     * inside one millisecond by a dedicated counter (RFC 9562 §6.2) and treats a wall clock that
     * steps backwards as "not ticking" — it keeps the last timestamp and counts on — so the order
     * survives an NTP correction too (read off the 2.3.21 source, `UuidV7Generator.generate`).
     */
    public val UuidV7: EventIdGenerator = EventIdGenerator { Uuid.generateV7().toString() }

    /** Random UUIDv4 — matches the default behavior of the Segment SDKs. */
    public val UuidV4: EventIdGenerator = EventIdGenerator { Uuid.random().toString() }
}
