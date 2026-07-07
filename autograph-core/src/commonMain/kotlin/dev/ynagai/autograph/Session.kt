package dev.ynagai.autograph

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Session semantics: a session ends when the app has been inactive for longer than
 * [backgroundTimeout]. Session state is persisted, so a session survives process
 * restarts as long as activity resumes within the timeout.
 */
public data class SessionConfig(
    val backgroundTimeout: Duration = 30.minutes,
)

/** A snapshot of the session an event belongs to. */
public data class SessionInfo(
    val id: String,
    val startEpochMillis: Long,
)
