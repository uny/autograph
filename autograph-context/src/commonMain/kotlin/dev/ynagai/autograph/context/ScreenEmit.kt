package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Records [name] as the current screen and emits a `Screen Viewed` for it, carrying the screen it
 * replaced as `previous_screen`. The one place that couples recording with emitting, so every native
 * screen-capture caller — the iOS UIKit swizzle, the iOS explicit SwiftUI path, and the Android
 * Activity/Fragment lifecycle capture — cannot drift on the order or on the self-previous guard.
 *
 * `previous == name` when this same screen is re-entered with nothing capturable recorded in between —
 * leaving a screen for an excluded one (a SwiftUI tab, a system/Compose modal, a SwiftUI screen with no
 * `.autographScreen`, or on Android a Compose-hosted or excluded Activity) and coming back. The
 * intermediate screen is genuinely unknown, so the honest `previous_screen` is none, not the screen
 * naming itself — hence the `takeIf`. The Compose path never hits this (its `LaunchedEffect` is keyed on
 * the name, so it cannot re-record an unchanged one); the native callers are the first that can, so the
 * guard lives here rather than in [ScreenHistory.record].
 *
 * Callers push the screen frame first (see their call sites): this only records and emits, so a throwing
 * tracker leaves an already-removable frame behind.
 *
 * `@AutographInternalApi`: public only so Autograph's own native modules can share this across the module
 * boundary — Kotlin `internal` would not reach them. Not a supported API for library users.
 */
@AutographInternalApi
public fun ScopeStack.emitScreenView(tracker: Tracker, name: String) {
    val previous = screenHistory.record(name)?.takeIf { it != name }
    tracker.screen(name, withPreviousScreen(previous))
}

/**
 * A native `Screen Viewed`'s `previous_screen`, or nothing when this is the first screen. Native screen
 * views carry no base properties, so there is no caller-supplied `previous_screen` to preserve — this is
 * the properties-free counterpart of `autograph-compose`'s `withPreviousScreen`.
 */
private fun withPreviousScreen(previous: String?): JsonObject =
    if (previous != null) {
        JsonObject(mapOf("previous_screen" to JsonPrimitive(previous)))
    } else {
        EmptyJsonObject
    }
