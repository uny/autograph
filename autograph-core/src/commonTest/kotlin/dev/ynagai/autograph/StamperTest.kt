package dev.ynagai.autograph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class StamperTest {

    private var now = 1_000_000L
    private val store = InMemorySeqStore()

    private fun stamper(
        mode: SequenceMode = SequenceMode.Both,
        persistence: SeqPersistence = SeqPersistence.EveryEvent,
        timeout: kotlin.time.Duration = 30.minutes,
    ) = Stamper(
        idGen = EventId.UuidV7,
        mode = mode,
        persistence = persistence,
        sessionConfig = SessionConfig(backgroundTimeout = timeout),
        store = store,
        clock = { now },
    )

    @Test
    fun sequenceIsMonotonicFromOne() {
        val s = stamper()
        val envelopes = (1..5).map { s.stamp() }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), envelopes.map { it.seq })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), envelopes.map { it.globalSeq })
    }

    @Test
    fun sequenceModeNoneOmitsCounters() {
        val s = stamper(mode = SequenceMode.None)
        val envelope = s.stamp()
        assertNull(envelope.seq)
        assertNull(envelope.globalSeq)
    }

    @Test
    fun sessionRotatesAfterTimeoutAndResetsSessionSeq() {
        val s = stamper()
        val first = s.stamp()
        assertEquals(1L, first.seq)

        now += 31.minutes.inWholeMilliseconds
        val second = s.stamp()

        assertNotEquals(first.session.id, second.session.id)
        assertEquals(1L, second.seq, "per-session seq must restart in a new session")
        assertEquals(2L, second.globalSeq, "global seq must keep counting across sessions")
    }

    @Test
    fun sessionSurvivesWithinTimeout() {
        val s = stamper()
        val first = s.stamp()
        now += 5.minutes.inWholeMilliseconds
        val second = s.stamp()
        assertEquals(first.session.id, second.session.id)
        assertEquals(2L, second.seq)
    }

    @Test
    fun everyEventPersistenceResumesExactlyAcrossRestart() {
        stamper().apply { repeat(3) { stamp() } }

        // Simulate a process restart within the session timeout: same store, new Stamper.
        val resumed = stamper().stamp()
        assertEquals(4L, resumed.seq)
        assertEquals(4L, resumed.globalSeq)
    }

    @Test
    fun restartAfterTimeoutStartsNewSessionButKeepsGlobalSeq() {
        val first = stamper().stamp()

        now += 31.minutes.inWholeMilliseconds
        val resumed = stamper().stamp()

        assertNotEquals(first.session.id, resumed.session.id)
        assertEquals(1L, resumed.seq)
        assertEquals(2L, resumed.globalSeq)
    }

    @Test
    fun chunkedPersistenceNeverReusesNumbersAfterCrash() {
        val chunked = SeqPersistence.Chunked(10)
        val s = stamper(persistence = chunked)
        repeat(3) { s.stamp() } // persisted high-water mark is still at the last boundary

        // Simulate a crash + restart: counters skip to the next chunk boundary.
        val resumed = stamper(persistence = chunked).stamp()
        assertTrue(resumed.globalSeq!! > 3L, "resumed global seq must not collide with pre-crash values")
        assertEquals(11L, resumed.globalSeq)
    }

    @Test
    fun resetStartsNewSessionButKeepsGlobalSeq() {
        val s = stamper()
        val first = s.stamp()
        s.reset()
        val second = s.stamp()
        assertNotEquals(first.session.id, second.session.id)
        assertEquals(1L, second.seq)
        assertEquals(2L, second.globalSeq)
    }

    @Test
    fun envelopeJsonContainsExpectedFields() {
        val envelope = stamper().stamp()
        val json = envelope.toJson()
        assertEquals(envelope.eventId, json["event_id"]?.toString()?.trim('"'))
        assertEquals("1", json["seq"]?.toString())
        assertTrue(json.containsKey("session_id"))
        assertTrue(json.containsKey("session_start"))
        assertTrue(json.containsKey("sdk"))
    }

    @Test
    fun notifyBackgroundPersistsSessionForRestartWithinTimeout() {
        val s = stamper()
        val first = s.stamp()

        now += 5.minutes.inWholeMilliseconds
        s.notifyBackground()

        // Restart 10 minutes later — still within the 30-minute timeout of the
        // last activity persisted by notifyBackground: the session must resume.
        now += 10.minutes.inWholeMilliseconds
        val resumed = stamper().stamp()

        assertEquals(first.session.id, resumed.session.id, "backgrounded session must resume after restart within timeout")
        assertEquals(2L, resumed.seq, "per-session seq continues after a resumed restart")
    }

    @Test
    fun notifyForegroundRotatesExpiredSession() {
        val s = stamper()
        val first = s.stamp()

        now += 31.minutes.inWholeMilliseconds
        s.notifyForeground()
        val after = s.stamp()

        assertNotEquals(first.session.id, after.session.id, "foreground after timeout must start a new session")
        assertEquals(1L, after.seq, "new session restarts the per-session seq")
    }
}
