package dev.ynagai.autograph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [SeqStore] that models a non-durable platform store (SharedPreferences.apply() /
 * NSUserDefaults): writes land in an in-memory buffer and only become durable on [flush].
 * [crash] discards everything not yet flushed, simulating an OOM/SIGKILL before the async
 * disk write. It also counts [putLong] calls so tests can assert write batching.
 */
private class NonDurableSeqStore : SeqStore {
    private val durable = mutableMapOf<String, Any>()
    private val pending = mutableMapOf<String, Any>()

    var putLongCount: Int = 0
        private set

    /** Reads (getLong + getString), counted so a test can assert construction touches the store at all. */
    var readCount: Int = 0
        private set

    override fun getLong(key: String): Long? {
        readCount++
        return (pending[key] ?: durable[key]) as Long?
    }

    override fun putLong(key: String, value: Long) {
        putLongCount++
        pending[key] = value
    }

    override fun getString(key: String): String? {
        readCount++
        return (pending[key] ?: durable[key]) as String?
    }

    override fun putString(key: String, value: String) {
        pending[key] = value
    }

    override fun remove(key: String) {
        pending.remove(key)
        durable.remove(key)
    }

    override fun flush() {
        durable.putAll(pending)
        pending.clear()
    }

    /** Simulates a hard process kill: writes not yet flushed to disk are lost. */
    fun crash() = pending.clear()
}

class SeqStoreDurabilityTest {

    private var now = 1_000_000L

    private fun stamper(store: SeqStore, persistence: SeqPersistence, mode: SequenceMode = SequenceMode.Both) =
        Stamper(
            idGen = EventId.UuidV7,
            mode = mode,
            persistence = persistence,
            sessionConfig = SessionConfig(),
            store = store,
            clock = { now },
        )

    @Test
    fun everyEventSurvivesCrashWithoutReusingSeq() {
        val store = NonDurableSeqStore()
        stamper(store, SeqPersistence.EveryEvent).apply { repeat(3) { stamp() } }

        // Hard kill: only writes made durable via flush() remain.
        store.crash()

        val resumed = stamper(store, SeqPersistence.EveryEvent).stamp()
        assertEquals(4L, resumed.seq, "EveryEvent must resume exactly after a crash, never reusing a number")
        assertEquals(4L, resumed.globalSeq)
    }

    @Test
    fun chunkedReservationSurvivesCrashWithoutReusingSeq() {
        val chunked = SeqPersistence.Chunked(10)
        val store = NonDurableSeqStore()
        stamper(store, chunked).apply { repeat(3) { stamp() } }

        store.crash()

        val resumed = stamper(store, chunked).stamp()
        assertTrue(resumed.globalSeq!! > 3L, "chunked reservation must be durable so a crash cannot re-hand-out numbers")
    }

    @Test
    fun constructionDoesNoStoreWritesUntilFirstOperation() {
        // #55: building a Stamper must not touch the store on the caller thread — the construction-time
        // load + chunk reservation are deferred to the first operation, which the tracker runs on its
        // serial dispatcher, off the (often main) thread. Fault injection: restoring the eager init{}
        // block (reservation at construction) makes putLongCount non-zero before any stamp.
        val store = NonDurableSeqStore()
        val s = stamper(store, SeqPersistence.Chunked(10))
        assertEquals(0, store.readCount, "construction must not read the store — the load is deferred (#55)")
        assertEquals(0, store.putLongCount, "construction must not persist to the store — I/O is deferred (#55)")

        s.stamp()
        assertTrue(store.readCount > 0 && store.putLongCount > 0, "the first operation performs the deferred load + reservation")
    }

    @Test
    fun chunkedSingleCounterModePersistsOnlyAtBoundaries() {
        val store = NonDurableSeqStore()
        val s = stamper(store, SeqPersistence.Chunked(5), mode = SequenceMode.PerSession)

        // The first stamp (seq 1) fires the deferred init writes; capture the count *after* it, since
        // construction itself no longer writes (#55). What this test guards is the batching *between*
        // boundaries: seq 2..4 must add nothing.
        s.stamp() // seq 1 — fires the deferred load + reservation
        val afterInit = store.putLongCount
        repeat(3) { s.stamp() } // seq 2..4 — no chunk boundary at 5
        assertEquals(afterInit, store.putLongCount, "Chunked+PerSession must not persist on non-boundary events")

        s.stamp() // seq 5 — chunk boundary
        assertTrue(store.putLongCount > afterInit, "the chunk-boundary event must persist the high-water mark")
    }

    @Test
    fun noneChunkedPersistsSessionActivityWithinChunkBound() {
        // SequenceMode.None advances no counter, so the seq-boundary durability checks never fire.
        // Session activity must still be persisted on the chunk event cadence; otherwise it stays
        // frozen at session start and a hard crash wrongly rotates a session whose real last
        // activity was well within the timeout. Sessions stay alive here (20min gaps < 30min default
        // timeout), so the only reason to rotate on restart is a stale persisted activity time.
        val store = NonDurableSeqStore()
        val s = stamper(store, SeqPersistence.Chunked(2), mode = SequenceMode.None)
        val first = s.stamp()                  // t0, session S
        now += 20 * 60 * 1000L; s.stamp()      // t0+20min — chunk boundary: persist+flush activity
        now += 20 * 60 * 1000L; s.stamp()      // t0+40min
        now += 20 * 60 * 1000L; s.stamp()      // t0+60min — chunk boundary: persist+flush activity

        store.crash()                          // hard kill: only flushed activity survives

        now += 10 * 60 * 1000L                 // restart at t0+70min; real activity was 10min ago
        val resumed = stamper(store, SeqPersistence.Chunked(2), mode = SequenceMode.None).stamp()
        assertEquals(
            first.session.id, resumed.session.id,
            "None+Chunked must persist recent activity so a within-timeout restart resumes the session",
        )
    }
}
