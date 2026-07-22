package dev.ynagai.autograph

/**
 * Sink for the library's own diagnostic messages — a delivery that failed, or an event dropped
 * because it did not validate. Set one on [AutographConfig.logger] to route these into your app's
 * logging framework (Logcat, `os_log`, Timber, …) or to silence them; the default prints to the
 * console, matching the library's historical behavior.
 *
 * These are **operational diagnostics about Autograph itself**, not analytics events — they never
 * reach a transport. A message is a single human-readable line, already prefixed with `Autograph:`.
 * Keep an implementation cheap and thread-safe: it is called both on the caller's thread (when an
 * event is dropped for failing validation) and on the stamping/delivery dispatcher (when a delivery
 * fails). It need not be non-throwing — an exception it raises is swallowed, so a buggy logger can no
 * more crash the app than a buggy [EventValidator] can — but the diagnostic is then simply lost.
 *
 * This is a caller-implemented SPI (ADR 0001 §2c): a future capability that needs more than a line
 * of text — a severity level, a structured payload — is added as a *separate* optional interface the
 * core probes with `is`, never as a new method here, so an existing lambda implementor keeps working.
 */
public fun interface AutographLogger {
    /** Records a single diagnostic [message] (already prefixed with `Autograph:`). */
    public fun log(message: String)
}

/** The default [AutographLogger]: prints each diagnostic to the console, as the library always did. */
internal val DefaultAutographLogger: AutographLogger = AutographLogger { println(it) }
