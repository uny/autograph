package dev.ynagai.autograph

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end durability of [Stamper] over the real [FileSeqStore] on the platform filesystem.
 *
 * [SeqStoreDurabilityTest] covers the same guarantees against an in-memory store *simulation*;
 * this exercises the actual atomic-file store across simulated process restarts — a fresh
 * [Stamper] + [FileSeqStore] re-reading the persisted file — so "never reuse a sequence number"
 * and session survival are verified against real disk I/O, not just the simulation. A restart is
 * modelled by constructing a new pair over the same directory; the earlier run's unflushed state
 * is exactly what never reached the file.
 */
class FileSeqStoreDurabilityTest {

    private val dirPath = Path(SystemTemporaryDirectory, "autograph-fileseqstore-durability-test")
    private val dir = dirPath.toString()

    @BeforeTest
    fun clean() = cleanup()

    @AfterTest
    fun tearDown() = cleanup()

    private fun cleanup() {
        listOf("autograph-seq.json", "autograph-seq.json.tmp").forEach {
            val p = Path(dir, it)
            if (SystemFileSystem.exists(p)) SystemFileSystem.delete(p)
        }
        if (SystemFileSystem.exists(dirPath)) SystemFileSystem.delete(dirPath)
    }

    private var now = 1_000_000L

    // A fresh Stamper reading the same on-disk file models a process restart.
    private fun stamper(persistence: SeqPersistence, mode: SequenceMode = SequenceMode.Both) =
        Stamper(
            idGen = EventId.UuidV7,
            mode = mode,
            persistence = persistence,
            sessionConfig = SessionConfig(),
            store = FileSeqStore(dir),
            clock = { now },
        )

    @Test
    fun everyEventResumesAcrossRealRestartWithoutReusingSeq() {
        stamper(SeqPersistence.EveryEvent).apply { repeat(3) { stamp() } }

        val resumed = stamper(SeqPersistence.EveryEvent).stamp()

        assertEquals(4L, resumed.seq, "EveryEvent must resume from the high-water mark persisted on disk")
        assertEquals(4L, resumed.globalSeq)
    }

    @Test
    fun sessionSurvivesRestartWithinTimeout() {
        val first = stamper(SeqPersistence.EveryEvent).stamp()

        now += 60_000L // 1 min, well under the 30 min default timeout
        val resumed = stamper(SeqPersistence.EveryEvent).stamp()

        assertEquals(first.session.id, resumed.session.id, "a within-timeout restart must resume the same session")
        assertEquals(2L, resumed.seq, "the resumed session must continue the sequence, not restart it")
    }

    @Test
    fun sessionRotatesAfterTimeoutAcrossRestart() {
        val first = stamper(SeqPersistence.EveryEvent).stamp()

        now += 31 * 60_000L // past the 30 min default timeout
        val resumed = stamper(SeqPersistence.EveryEvent).stamp()

        assertNotEquals(first.session.id, resumed.session.id, "a restart past the timeout must start a new session")
        assertEquals(1L, resumed.seq, "the new session restarts the per-session sequence")
    }

    @Test
    fun chunkedReservationOnRealFileNeverRehandsOutNumbers() {
        val chunked = SeqPersistence.Chunked(10)
        // Chunk [1..10] is reserved and flushed to disk on the first stamp (the reservation is deferred
        // off construction — #55); the three events advance the in-memory counter to 3 but do not
        // persist again (no boundary crossed).
        stamper(chunked).apply { repeat(3) { stamp() } }

        // Restarting before the boundary models a crash mid-chunk: the next run must skip past every
        // number this run may have handed out, resuming at the next reserved boundary.
        val resumed = stamper(chunked).stamp()

        assertTrue(resumed.globalSeq!! > 3L, "the durable reservation must stop numbers being re-handed-out after a restart")
        assertEquals(11L, resumed.globalSeq, "resumes at the next chunk boundary (10) then advances")
    }
}
