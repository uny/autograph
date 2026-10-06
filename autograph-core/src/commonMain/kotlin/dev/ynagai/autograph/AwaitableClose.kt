package dev.ynagai.autograph

/**
 * An optional capability of a [Tracker]: it can shut down and report *how* the shutdown went. The
 * tracker [Autograph] returns has it; a view of that tracker that does not own it (such as the one
 * `AutographScope` hands out) does not.
 *
 * **Call [closeAndAwait] (the `Tracker` extension) rather than this interface.** It probes for the
 * capability with `is` and returns [CloseResult.isUnsupported], without calling [Tracker.close], when it
 * is absent — so code that holds a plain [Tracker] cannot shut down a tracker it does not own.
 *
 * This is a separate interface, and not a member of [Tracker], because Kotlin/Native exports every
 * interface member to Swift as `@required`: a new `Tracker` member breaks every Swift conformer (ADR
 * 0001 §2d, #283). Its member set is frozen for the major version, like any other interface a caller
 * could implement.
 */
public interface AwaitableCloseTracker {

    /**
     * The tracker's configured shutdown bound, [AutographConfig.closeDrainTimeoutMillis]. It is the
     * default wait of the no-argument [closeAndAwait].
     */
    public val closeTimeoutMillis: Long

    /**
     * Stops accepting work, waits up to [timeoutMillis] for work already accepted to be handed to the
     * transport, and reports what happened. See [CloseResult] for exactly what each part of the result
     * does and does not promise.
     *
     * Unlike [Tracker.close], **every call waits on the same shutdown**: the first call starts it,
     * concurrent calls wait on it, and a call made after it finished returns its final result at once.
     * [Tracker.close] and this function share that one shutdown too, so a `close()` already in progress
     * on another thread is waited for, not repeated.
     *
     * [timeoutMillis] bounds *this caller's wait*, not the shutdown: a caller that times out (or is
     * cancelled) leaves the shutdown running, bounded by [AutographConfig.closeDrainTimeoutMillis], and a
     * later call reports its outcome. The wait is not a hard real-time limit either — a synchronous
     * [Transport.flush] cannot be interrupted, so a transport whose flush blocks holds the shutdown, and
     * therefore any wait that outlasts the bound, for as long as it blocks. The timer runs on real time
     * even when called from a virtual-time test dispatcher.
     *
     * A [timeoutMillis] of zero or less does not wait: it starts (or joins) the shutdown and reports its
     * state at once. It is not an error, so that a bad value can never become a crash on a path that runs
     * while an app is shutting down.
     */
    public suspend fun closeAndAwait(timeoutMillis: Long): CloseResult
}

/**
 * What [closeAndAwait] can tell the caller about a shutdown. A final class the library alone produces
 * (ADR 0001 §2a): properties may be added in a minor version, and the set of outcomes is not an enum
 * that could grow under a caller's `when`.
 *
 * **Network delivery is out of scope.** Nothing here says an event reached a server, or even that the
 * transport sent it: only that the tracker handed it over. What the transport then does with it — its
 * queue, its retries — is its business, and the one thing the tracker asks of it is [Transport.flush].
 *
 * What "handed over" means depends on who stamps:
 *
 * - **The core stamps** (the default): the tracker waits until every accepted event has been stamped and
 *   its call into the transport has returned.
 * - **The transport stamps in its own pipeline** (Segment on Android): the tracker waits for the calls
 *   *into* the transport to return. It does not wait for the pipeline's own queue or stamping, and cannot;
 *   the flush it requests afterwards is how that queue is nudged.
 *
 * The result separates four facts, because a single "success" flag would hide one of them:
 *
 * 1. [cutoffReached]: the tracker stopped accepting work. False only when the tracker cannot do that
 *    through this path, in which case nothing else was done ([isUnsupported]).
 * 2. [waitCompleted]: everything accepted before the cutoff was handed over within the time allowed.
 *    False is [timedOut]: some accepted work may not have reached the transport.
 * 3. [failedHandOffs]: how many calls into the transport (or the stamping in front of it) threw. The
 *    tracker swallows these so analytics never crashes the app, and logs them; a completed wait does
 *    **not** mean they all succeeded, which is why this count exists.
 * 4. [flushRequested] and [flushFailed]: whether the tracker asked the transport to flush, and whether
 *    that call threw. A flush that returned means it was *requested*, not that anything was sent.
 *
 * Everything here is a snapshot at the moment the result was produced. A call that timed out reports
 * the state at its deadline, while the shutdown may still be running; a later `closeAndAwait` reports
 * the final state. [failedHandOffs] counts failures over the tracker's whole life up to that moment,
 * not only those of work accepted just before the close — nothing is accepted after the cutoff, so
 * once [waitCompleted] it is the complete count.
 */
public class CloseResult internal constructor(
    /** Whether the tracker stopped accepting work. False means the tracker does not support this. */
    public val cutoffReached: Boolean,
    /** Whether all work accepted before the cutoff was handed to the transport within the time allowed. */
    public val waitCompleted: Boolean,
    /** How many calls into the transport (or the stamping in front of it) threw. See the class docs. */
    public val failedHandOffs: Int,
    /** Whether [Transport.flush] was called. Not called when a core-stamping drain was cut short. */
    public val flushRequested: Boolean,
    /** Whether that [Transport.flush] threw. */
    public val flushFailed: Boolean,
) {
    /**
     * True when the tracker does not offer an awaitable close — for example a scoped view of a tracker —
     * so nothing was closed and nothing else in this result means anything.
     */
    public val isUnsupported: Boolean get() = !cutoffReached

    /** True when the shutdown ran but the wait was cut short: the tracker closed, but not everything accepted is known to be handed over. */
    public val timedOut: Boolean get() = cutoffReached && !waitCompleted

    override fun toString(): String =
        "CloseResult(cutoffReached=$cutoffReached, waitCompleted=$waitCompleted, failedHandOffs=$failedHandOffs, " +
            "flushRequested=$flushRequested, flushFailed=$flushFailed)"

    internal companion object {
        val Unsupported = CloseResult(
            cutoffReached = false,
            waitCompleted = false,
            failedHandOffs = 0,
            flushRequested = false,
            flushFailed = false,
        )
    }
}

/**
 * Closes this tracker like [Tracker.close], but suspends instead of blocking and reports what happened.
 * Waits up to [AwaitableCloseTracker.closeTimeoutMillis] (the tracker's
 * [AutographConfig.closeDrainTimeoutMillis]).
 *
 * Returns a result with [CloseResult.isUnsupported] **without closing anything** when this tracker has
 * no [AwaitableCloseTracker] capability — a scoped view does not own the root tracker and must not be
 * able to shut it down. Close the tracker you created, not a view of it. See [AwaitableCloseTracker.closeAndAwait]
 * for how concurrent and repeated calls behave and what the timeout bounds.
 */
public suspend fun Tracker.closeAndAwait(): CloseResult {
    val awaitable = this as? AwaitableCloseTracker ?: return CloseResult.Unsupported
    return awaitable.closeAndAwait(awaitable.closeTimeoutMillis)
}

/** [closeAndAwait] with an explicit per-call [timeoutMillis] for the wait. */
public suspend fun Tracker.closeAndAwait(timeoutMillis: Long): CloseResult {
    val awaitable = this as? AwaitableCloseTracker ?: return CloseResult.Unsupported
    return awaitable.closeAndAwait(timeoutMillis)
}
