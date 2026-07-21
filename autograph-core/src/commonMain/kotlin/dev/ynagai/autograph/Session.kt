package dev.ynagai.autograph

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Session semantics: a session ends when the app has been inactive for longer than
 * [backgroundTimeout]. Session state is persisted, so a session survives process
 * restarts as long as activity resumes within the timeout.
 *
 * **This shape is frozen.** A data class constructor cannot gain a parameter without breaking ABI,
 * so further session knobs go on [AutographConfig] as properties rather than here — see
 * [ADR 0001](../../../../../../../docs/adr/0001-public-api-evolution.md) §2b.
 */
public data class SessionConfig(
    val backgroundTimeout: Duration = 30.minutes,
)

/**
 * A snapshot of the session an event belongs to.
 *
 * Produced by the library and only read by callers, so its constructor is `internal` for the same
 * reason [Envelope]'s is — see [ADR 0001](../../../../../../../docs/adr/0001-public-api-evolution.md)
 * §2a. Reached through [Envelope.session]; tests build one via `testEnvelope(...)` in
 * `autograph-test`.
 */
@ConsistentCopyVisibility
public data class SessionInfo internal constructor(
    val id: String,
    val startEpochMillis: Long,
)
