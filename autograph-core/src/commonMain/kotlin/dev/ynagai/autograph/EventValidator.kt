package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * Checks a `track`/`screen` event against an app-defined tracking-plan contract — a fixed set of
 * event types, required properties, naming conventions — before it reaches the transport.
 *
 * Autograph does not ship any rules of its own (every adopter's schema differs); this is the
 * extension point for plugging app-defined ones in via [AutographConfig.validator]. Whether an
 * invalid event throws or is dropped-and-logged is controlled separately by
 * [AutographConfig.strictValidation], not by this interface, so the same [EventValidator] works
 * for both debug (fail fast) and release (never crash) builds. A [validate] that itself throws is
 * treated as a rejection too, so a validator bug can't crash a non-strict build.
 *
 * `strictValidation`'s throw is synchronous, on the caller's thread — including composables that
 * call `track`/`screen` from inside a `LaunchedEffect` (e.g. `TrackScreenView`), where it
 * propagates as an uncaught exception in that effect's own coroutine, not through any Autograph-
 * owned handler. Reserve strict mode for debug builds, where crashing on a tracking-plan
 * violation is the intended behavior.
 */
public fun interface EventValidator {
    /** Returns null when [name]/[properties] are valid, or a human-readable reason otherwise. */
    public fun validate(name: String, properties: JsonObject): String?
}
