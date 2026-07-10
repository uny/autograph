package dev.ynagai.autograph

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.Uuid

internal const val SDK_ID: String = "autograph/0.1.0"

private const val KEY_SESSION_ID = "autograph.session.id"
private const val KEY_SESSION_START = "autograph.session.start"
private const val KEY_LAST_ACTIVITY = "autograph.session.last_activity"
private const val KEY_SEQ_SESSION = "autograph.seq.session"
private const val KEY_SEQ_GLOBAL = "autograph.seq.global"

/**
 * Thread-safe envelope factory: generates event ids, advances sequence counters, and
 * rotates sessions after inactivity. All state transitions happen under a single lock,
 * so the order of assigned sequence numbers is the order of [stamp] calls.
 */
internal class Stamper(
    private val idGen: EventIdGenerator,
    private val mode: SequenceMode,
    persistence: SeqPersistence,
    private val sessionConfig: SessionConfig,
    private val store: SeqStore,
    private val clock: () -> Long,
    private val schemaVersion: String? = null,
) : EnvelopeSource {

    private val lock = SynchronizedObject()
    private val chunk: Long = when (persistence) {
        is SeqPersistence.EveryEvent -> 1
        is SeqPersistence.Chunked -> persistence.chunk
    }
    private val tracksSession: Boolean = mode == SequenceMode.PerSession || mode == SequenceMode.Both
    private val tracksGlobal: Boolean = mode == SequenceMode.PerDevice || mode == SequenceMode.Both

    private var sessionId: String = ""
    private var sessionStart: Long = 0
    private var lastActivity: Long = 0
    private var sessionSeq: Long = 0
    private var globalSeq: Long = 0
    private var eventsSincePersist: Long = 0

    init {
        val now = clock()
        // A crash may have lost up to (chunk - 1) increments past the persisted
        // high-water marks, so resuming *at* the mark could reuse numbers.
        // Skipping to the next chunk boundary guarantees uniqueness; with
        // EveryEvent (chunk = 1) this resumes exactly where we left off.
        globalSeq = store.getLong(KEY_SEQ_GLOBAL)?.let { nextBoundary(it) } ?: 0

        val storedId = store.getString(KEY_SESSION_ID)
        val storedStart = store.getLong(KEY_SESSION_START)
        val storedActivity = store.getLong(KEY_LAST_ACTIVITY)
        if (storedId != null && storedStart != null && storedActivity != null &&
            now - storedActivity <= sessionConfig.backgroundTimeout.inWholeMilliseconds
        ) {
            sessionId = storedId
            sessionStart = storedStart
            lastActivity = storedActivity
            sessionSeq = store.getLong(KEY_SEQ_SESSION)?.let { nextBoundary(it) } ?: 0
        } else {
            startNewSession(now)
        }

        // With chunked persistence, reserve the current block right away: if we crash
        // before the first boundary write, the next restore still skips past any
        // sequence numbers this run may have handed out. The reservation must be durable —
        // a lost async write would recompute the *same* boundary on restart and re-hand-out
        // numbers, breaking the "duplicates impossible" guarantee.
        if (chunk > 1) {
            store.putLong(KEY_SEQ_GLOBAL, globalSeq)
            store.putLong(KEY_SEQ_SESSION, sessionSeq)
            store.flush()
        }
    }

    override fun stamp(): Envelope = synchronized(lock) {
        val now = clock()
        rotateIfExpired(now)
        lastActivity = now

        val seq = if (tracksSession) ++sessionSeq else null
        val global = if (tracksGlobal) ++globalSeq else null
        // Make the high-water mark durable before the envelope (and its sequence number)
        // escapes this call, so a crash cannot leave a persisted mark behind the emitted seq.
        if (persistCounters()) store.flush()

        Envelope(
            eventId = idGen.next(),
            session = SessionInfo(sessionId, sessionStart),
            seq = seq,
            globalSeq = global,
            sdk = SDK_ID,
            schemaVersion = schemaVersion,
        )
    }

    fun notifyForeground(): Unit = synchronized(lock) {
        rotateIfExpired(clock())
    }

    fun notifyBackground(): Unit = synchronized(lock) {
        lastActivity = clock()
        persistSession()
    }

    /** Starts a fresh session, e.g. on logout. Device-lifetime counters are preserved. */
    override fun reset(): Unit = synchronized(lock) {
        startNewSession(clock())
    }

    private fun rotateIfExpired(now: Long) {
        if (now - lastActivity > sessionConfig.backgroundTimeout.inWholeMilliseconds) {
            startNewSession(now)
        }
    }

    private fun startNewSession(now: Long) {
        sessionId = Uuid.generateV7().toString()
        sessionStart = now
        lastActivity = now
        sessionSeq = 0
        eventsSincePersist = 0
        store.putString(KEY_SESSION_ID, sessionId)
        store.putLong(KEY_SESSION_START, sessionStart)
        store.putLong(KEY_LAST_ACTIVITY, lastActivity)
        store.putLong(KEY_SEQ_SESSION, 0)
        store.flush()
    }

    private fun nextBoundary(persisted: Long): Long =
        if (chunk == 1L) persisted else (persisted / chunk + 1) * chunk

    /**
     * Persists the counter high-water marks when a durability point is reached, returning
     * whether it wrote (the caller then [SeqStore.flush]es). Under EveryEvent (chunk == 1) that
     * is every event; under Chunked it is when an *active* counter crosses a chunk boundary, or
     * every [chunk] events when no counter is active. Testing an inactive counter (which stays 0,
     * so `0 % chunk == 0` is always true) would fire on every event and silently defeat Chunked's
     * write batching for single-counter modes — hence the [tracksSession]/[tracksGlobal] guards.
     *
     * [eventsSincePersist] keeps session activity on the documented "stale by at most one chunk"
     * cadence in [SequenceMode.None], where no counter advances so the seq-boundary checks never
     * fire and the persisted activity time would otherwise stay stuck at session start.
     */
    private fun persistCounters(): Boolean {
        eventsSincePersist++
        val atDurabilityPoint = chunk == 1L ||
            (tracksSession && sessionSeq % chunk == 0L) ||
            (tracksGlobal && globalSeq % chunk == 0L) ||
            eventsSincePersist >= chunk
        if (atDurabilityPoint) {
            store.putLong(KEY_SEQ_SESSION, sessionSeq)
            store.putLong(KEY_SEQ_GLOBAL, globalSeq)
            store.putLong(KEY_LAST_ACTIVITY, lastActivity)
            eventsSincePersist = 0
        }
        return atDurabilityPoint
    }

    private fun persistSession() {
        store.putString(KEY_SESSION_ID, sessionId)
        store.putLong(KEY_SESSION_START, sessionStart)
        store.putLong(KEY_LAST_ACTIVITY, lastActivity)
        store.flush()
    }
}
