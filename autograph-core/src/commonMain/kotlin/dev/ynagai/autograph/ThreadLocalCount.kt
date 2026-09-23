package dev.ynagai.autograph

/**
 * A counter with one value per thread, per instance. [AutographTracker] uses it to tell a pipeline call
 * admitted on the *calling* thread — which a reentrant [Tracker.close] can never wait for, since that
 * call is the frame it was invoked from — apart from calls still running on other threads.
 */
internal expect class ThreadLocalCount() {
    fun get(): Int
    fun set(value: Int)
}
