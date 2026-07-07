package dev.ynagai.autograph

/**
 * Controls which sequence numbers are stamped into the event envelope.
 *
 * Sequence numbers make event streams verifiable downstream:
 * - gaps in a sequence reveal event loss,
 * - the sequence restores in-session ordering without relying on client timestamps.
 */
public enum class SequenceMode {
    /** No sequence numbers. Smallest envelope. */
    None,

    /** A per-session counter starting at 1 (`seq`). Detects loss and restores order within a session. */
    PerSession,

    /** A device-lifetime counter (`global_seq`), persisted across sessions and restarts. */
    PerDevice,

    /** Both [PerSession] and [PerDevice]. */
    Both,
}

/** Controls how sequence counters are persisted. */
public sealed interface SeqPersistence {

    /**
     * Persist counters on every event. No false gaps after a crash;
     * the write cost is negligible at typical analytics event rates.
     */
    public data object EveryEvent : SeqPersistence

    /**
     * Persist a high-water mark every [chunk] events. On a cold start the counter skips
     * to the next chunk boundary: duplicates are impossible, but a crash can introduce
     * an artificial gap of at most [chunk]. Prefer [EveryEvent] unless events are
     * extremely frequent.
     */
    public data class Chunked(val chunk: Long) : SeqPersistence {
        init {
            require(chunk > 0) { "chunk must be positive, was $chunk" }
        }
    }
}
