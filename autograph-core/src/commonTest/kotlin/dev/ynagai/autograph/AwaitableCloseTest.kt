package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

// #261: closeAndAwait(). The tests that need events genuinely queued use the real serial dispatcher
// (the production default) for the reason AutographTrackerTest's #52 tests do, and block with
// runBlocking: runTest's virtual clock would skip a timeout past work running on another thread.
class AwaitableCloseTest {

    private fun tracker(
        transport: Transport,
        logs: MutableList<String> = mutableListOf(),
        configure: AutographConfig.() -> Unit = {},
    ): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
        logger = AutographLogger { logs += it }
        configure()
    }

    @Test
    fun aCoreStampedShutdownThatFinishesReportsACompleteResult() = runBlocking {
        val transport = CountingTransport(perEventMillis = 2, stampsInPipeline = false)
        val tracker = tracker(transport)
        repeat(20) { tracker.track("e$it") }

        val result = tracker.closeAndAwait()

        assertTrue(result.cutoffReached)
        assertTrue(result.waitCompleted)
        assertFalse(result.timedOut)
        assertFalse(result.isUnsupported)
        assertEquals(0, result.failedHandOffs)
        assertTrue(result.flushRequested)
        assertFalse(result.flushFailed)
        assertEquals(20, transport.tracked, "every accepted event reached the transport before the result")
        assertEquals(1, transport.flushes)
    }

    @Test
    fun aPipelineShutdownWaitsForCallsStillEnteringTheTransport() = runBlocking {
        val transport = CountingTransport(perEventMillis = 100, stampsInPipeline = true)
        val tracker = tracker(transport)
        val caller = async(Dispatchers.Default) { tracker.track("slow") }
        transport.awaitEntered()

        val result = tracker.closeAndAwait()
        caller.await()

        assertTrue(result.waitCompleted)
        assertEquals(1, transport.tracked, "the call already inside the transport finished before the result")
        assertTrue(result.flushRequested)
        assertEquals(1, transport.flushes)
    }

    @Test
    fun aWaitThatTimesOutSaysSoAndDoesNotRequestAFlushWhenTheCoreStamps() = runBlocking {
        val logs = mutableListOf<String>()
        val transport = CountingTransport(stampsInPipeline = false)
        val tracker = tracker(transport, logs) {
            // Accepts work and never runs it, so the drain cannot finish.
            dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {}
            }
            closeDrainTimeoutMillis = 100
        }
        tracker.track("stuck")

        val first = tracker.closeAndAwait(timeoutMillis = 10)

        assertTrue(first.cutoffReached)
        assertTrue(first.timedOut, "the caller's own, shorter, deadline passed: $first")
        assertFalse(first.waitCompleted)

        // The shutdown outlived that caller and hit its own bound; a later call reports the final state.
        val final = tracker.closeAndAwait(timeoutMillis = 5_000)
        assertTrue(final.timedOut)
        assertFalse(final.flushRequested, "nothing safe to flush after a cut-short core drain: $final")
        assertEquals(0, transport.flushes)
        assertTrue(logs.any { "gave up" in it }, "a drain cut short is still reported: $logs")
    }

    @Test
    fun aPipelineDrainCutShortStillRequestsItsFlush() = runBlocking {
        val transport = CountingTransport(perEventMillis = 400, stampsInPipeline = true)
        val tracker = tracker(transport) { closeDrainTimeoutMillis = 50 }
        val caller = async(Dispatchers.Default) { tracker.track("stuck") }
        transport.awaitEntered()

        // Waits longer than the shutdown's own 50 ms bound, so the result is the shutdown's and not a snapshot
        // of a caller whose identical deadline fired first.
        val result = tracker.closeAndAwait(timeoutMillis = 5_000)
        caller.await()

        assertTrue(result.timedOut)
        assertTrue(result.flushRequested)
        assertEquals(1, transport.flushes)
    }

    @Test
    fun aTrackerWithoutTheCapabilityIsReportedUnsupportedAndNotClosed() = runBlocking {
        val delegate = tracker(CountingTransport(stampsInPipeline = false))
        val view = NonOwningView(delegate)

        val result = view.closeAndAwait()
        val explicit = view.closeAndAwait(timeoutMillis = 1_000)

        assertTrue(result.isUnsupported)
        assertTrue(explicit.isUnsupported)
        assertFalse(result.cutoffReached)
        assertFalse(result.timedOut)
        assertEquals(0, view.closeCalls, "the helper must not fall back to close()")
        // And the root is demonstrably still open.
        assertTrue(delegate.closeAndAwait().waitCompleted)
    }

    @Test
    fun concurrentCallersWaitOnTheSameShutdown() = runBlocking {
        val transport = CountingTransport(perEventMillis = 5, stampsInPipeline = false)
        val tracker = tracker(transport)
        repeat(20) { tracker.track("e$it") }

        val results = List(8) { async(Dispatchers.Default) { tracker.closeAndAwait() } }.awaitAll()

        assertTrue(results.all { it.waitCompleted }, "$results")
        assertTrue(results.all { it.flushRequested })
        assertEquals(20, transport.tracked)
        assertEquals(1, transport.flushes, "one shutdown, so one flush however many callers waited")
    }

    @Test
    fun aRepeatedCallAfterCompletionReturnsTheFinalResultEvenWithNoTimeToWait() = runBlocking {
        val tracker = tracker(CountingTransport(stampsInPipeline = false))
        tracker.track("a")

        val first = tracker.closeAndAwait()
        val again = tracker.closeAndAwait(timeoutMillis = 0)

        assertSame(first, again)
        assertTrue(again.waitCompleted)
    }

    @Test
    fun closeAndAwaitWaitsOnAShutdownStartedByClose() = runBlocking {
        val transport = CountingTransport(perEventMillis = 5, stampsInPipeline = false)
        val tracker = tracker(transport)
        repeat(10) { tracker.track("e$it") }

        tracker.close()
        val result = tracker.closeAndAwait(timeoutMillis = 0)

        assertTrue(result.waitCompleted, "close() returns only once it has finished, so the result is already final")
        assertEquals(10, transport.tracked)
        assertEquals(1, transport.flushes)
    }

    @Test
    fun aCloseAndAwaitFirstMakesALaterCloseReturnAtOnce() = runBlocking {
        val transport = CountingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)
        tracker.track("a")

        tracker.closeAndAwait()
        tracker.close()

        assertEquals(1, transport.flushes, "close() after closeAndAwait() does not shut down twice")
    }

    @Test
    fun aHandOffThatThrewIsCountedEvenThoughTheWaitCompleted() = runBlocking {
        for (stampsInPipeline in listOf(false, true)) {
            val logs = mutableListOf<String>()
            val transport = CountingTransport(stampsInPipeline = stampsInPipeline, failTrackOf = setOf("bad1", "bad2"))
            val tracker = tracker(transport, logs)

            tracker.track("ok")
            tracker.track("bad1")
            tracker.track("bad2")
            val result = tracker.closeAndAwait()

            assertTrue(result.waitCompleted, "joinAll completing is not evidence of success (pipeline=$stampsInPipeline)")
            assertEquals(2, result.failedHandOffs, "pipeline=$stampsInPipeline")
            assertEquals(1, transport.tracked, "pipeline=$stampsInPipeline")
            assertEquals(2, logs.count { "event delivery failed" in it }, "still logged: $logs")
        }
    }

    @Test
    fun aFlushThatThrewIsReportedAndDoesNotEscape() = runBlocking {
        for (stampsInPipeline in listOf(false, true)) {
            val logs = mutableListOf<String>()
            val transport = CountingTransport(stampsInPipeline = stampsInPipeline, failFlush = true)
            val tracker = tracker(transport, logs)

            val result = tracker.closeAndAwait()

            assertTrue(result.waitCompleted)
            assertTrue(result.flushRequested)
            assertTrue(result.flushFailed, "pipeline=$stampsInPipeline")
            assertTrue(logs.any { "could not flush" in it }, "$logs")
        }
    }

    @Test
    fun closeNoLongerThrowsWhenTheTransportsFlushDoes() {
        val transport = CountingTransport(stampsInPipeline = false, failFlush = true)
        val tracker = tracker(transport)
        tracker.track("a")

        tracker.close() // used to propagate the flush's exception and skip releasing the scope

        tracker.track("after")
        assertEquals(1, transport.tracked, "the event after close() is dropped")
    }

    @Test
    fun aNegativeTimeoutIsNoWaitRatherThanAnError() = runBlocking {
        val tracker = tracker(CountingTransport(stampsInPipeline = false))
        val awaitable = tracker as AwaitableCloseTracker

        // It must not throw: from Swift an exception escaping this suspend function would abort the process.
        val result = awaitable.closeAndAwait(-1)

        assertTrue(result.cutoffReached, "the shutdown still started")
        // And the shutdown it started finishes regardless.
        assertTrue(tracker.closeAndAwait().waitCompleted)
    }

    @Test
    fun theConfiguredBoundIsThePublicDefaultWait() = runBlocking {
        val tracker = tracker(CountingTransport(stampsInPipeline = false)) { closeDrainTimeoutMillis = 1_234 }

        assertEquals(1_234, (tracker as AwaitableCloseTracker).closeTimeoutMillis)
    }
}

/** A view of a tracker that does not own it: forwards everything and has no [AwaitableCloseTracker]. */
private class NonOwningView(private val delegate: Tracker) : Tracker by delegate {
    var closeCalls = 0

    override fun close() {
        closeCalls++
    }
}

/**
 * Counts what reaches it. [perEventMillis] busy-waits inside `track` (see AutographTrackerTest's
 * SlowRecordingTransport for why not a sleep); [failTrackOf] names events whose hand-off throws.
 */
private class CountingTransport(
    private val perEventMillis: Int = 0,
    override val stampsInPipeline: Boolean,
    private val failTrackOf: Set<String> = emptySet(),
    private val failFlush: Boolean = false,
) : Transport {
    @Volatile
    var tracked = 0

    @Volatile
    var flushes = 0

    @Volatile
    private var entered = false

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        entered = true
        val start = TimeSource.Monotonic.markNow()
        @Suppress("ControlFlowWithEmptyBody")
        while (start.elapsedNow().inWholeMilliseconds < perEventMillis) {
        }
        if (name in failTrackOf) throw IllegalStateException("boom $name")
        tracked++
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {}

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {}

    override fun flush() {
        flushes++
        if (failFlush) throw IllegalStateException("flush boom")
    }

    fun awaitEntered() {
        @Suppress("ControlFlowWithEmptyBody")
        while (!entered) {
        }
    }
}
