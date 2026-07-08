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

    override fun getLong(key: String): Long? = (pending[key] ?: durable[key]) as Long?

    override fun putLong(key: String, value: Long) {
        putLongCount++
        pending[key] = value
    }

    override fun getString(key: String): String? = (pending[key] ?: durable[key]) as String?

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
    fun chunkedSingleCounterModePersistsOnlyAtBoundaries() {
        val store = NonDurableSeqStore()
        val s = stamper(store, SeqPersistence.Chunked(5), mode = SequenceMode.PerSession)

        val afterInit = store.putLongCount
        repeat(4) { s.stamp() } // seq 1..4 — no chunk boundary at 5
        assertEquals(afterInit, store.putLongCount, "Chunked+PerSession must not persist on non-boundary events")

        s.stamp() // seq 5 — chunk boundary
        assertTrue(store.putLongCount > afterInit, "the chunk-boundary event must persist the high-water mark")
    }
}
