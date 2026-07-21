package dev.ynagai.autograph

/**
 * Runs [block] to completion on the calling thread, giving up after [timeoutMillis].
 *
 * Exists because [Tracker.close] is a plain, non-suspending function — the shape `Closeable`-style
 * shutdown is expected to have, and the one it already had before it learned to drain — yet the drain
 * it now performs is suspending work. `runBlocking` is the right tool but is not declared in the common
 * source set (it exists only for JVM and Native), so it is reached through this `expect`/`actual` pair,
 * the same shape [SeqStore] uses.
 *
 * **[timeoutMillis] is a bound, not a promise.** Returning early on timeout is deliberate: a shutdown
 * path that can hang forever is worse than one that gives up and reports fewer events, and the caller
 * is frequently an app being torn down.
 *
 * **Do not configure `AutographConfig.dispatcher` with the dispatcher of the thread that calls
 * [Tracker.close].** Blocking a single-threaded dispatcher from inside itself starves the very
 * coroutines being awaited; the timeout still fires (the blocking event loop keeps running), so this
 * degrades to "drained nothing" rather than deadlocking, but nothing is drained.
 */
internal expect fun drainBlocking(timeoutMillis: Long, block: suspend () -> Unit)
