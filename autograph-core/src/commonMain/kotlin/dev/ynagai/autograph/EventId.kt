package dev.ynagai.autograph

import kotlin.time.Clock
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

    private val uuidV7 = UuidV7Generator { Clock.System.now().toEpochMilliseconds() }

    /**
     * UUIDv7 (RFC 9562): time-ordered, database-index friendly, and monotonic within
     * the lifetime of the process. The recommended default.
     */
    public val UuidV7: EventIdGenerator = EventIdGenerator { uuidV7.next().toString() }

    /** Random UUIDv4 — matches the default behavior of the Segment SDKs. */
    public val UuidV4: EventIdGenerator = EventIdGenerator { Uuid.random().toString() }
}
