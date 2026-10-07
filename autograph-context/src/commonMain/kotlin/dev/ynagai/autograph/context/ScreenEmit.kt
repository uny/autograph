package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.withEventMetadata
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
 * Callers push the screen frame first (see their call sites) and pass it as [origin]: this only records
 * and emits, so a throwing tracker leaves an already-removable frame behind.
 *
 * The event carries the **scope** resolved from [origin] — the surface's own lineage, read through the
 * origin-taking [ScopeStack.current] exactly as a native tap on that surface reads it (#238). A screen
 * view is an event *of that surface*, so a sibling surface's declaration must not reach it, which is
 * why this is not the ambient snapshot. That holds where the surface frames are boundaries (Android);
 * the iOS native screen frames are roots, so on that boundary-free stack this resolves the same
 * members as the ambient read (bar the inactive-frame exemptions [ScopeStack.current] documents) and
 * the scope ambiguity rule is what keeps sibling scopes apart — one scoped sibling still reaches the
 * event, two drop, exactly as for a native tap.
 *
 * The event carries the id of the visit it starts as its metadata ([AmbientContext.screenViewId]):
 * a new one for every call, so coming back to a screen is a new visit even under the same name.
 *
 * It is scope only, not [AmbientContext.enrich], because
 * `enrich` writes the reserved `screen` / `section` keys and for a screen event the name already *is*
 * the screen. The scope merges **under** `previous_screen`, a caller property that keeps winning a
 * key clash — the same shape as `autograph-compose`'s `mergeScope`.
 *
 * `@AutographInternalApi`: public only so Autograph's own native modules can share this across the module
 * boundary — Kotlin `internal` would not reach them. Not a supported API for library users.
 */
@AutographInternalApi
public fun ScopeStack.emitScreenView(tracker: Tracker, name: String, origin: ScopeHandle) {
    val previous = screenHistory.record(name)?.takeIf { it != name }
    // Started BEFORE the tracker is called, and handed to it directly (#242). The stack already
    // answers with the new visit while the event is in flight, so an event the tracker's own code
    // captures can join it; and the id is the one this frame now holds, which reading it back
    // through `current(origin)` would not guarantee — a later frame there can name the screen.
    // Not undone if the tracker rejects or throws: what the user sees is still this visit, so the
    // events captured during it carry an id no `Screen Viewed` row may have.
    val screenViewId = startVisit(origin)
    val scope = current(origin).scope
    val properties = withPreviousScreen(previous)
    val scoped = if (scope.isEmpty()) properties else JsonObject(scope - RESERVED_METADATA_KEY + properties)
    tracker.screen(name, if (screenViewId != null) scoped.withEventMetadata(kind = null, screenViewId = screenViewId) else scoped)
}

/**
 * Starts a new visit of [screen] on [handle]'s frame (#242) and returns its id, or null when the
 * generator fails or the frame is not on this stack — for an emit site outside this module that
 * builds its own `Screen Viewed`, as `autograph-compose`'s screen trackers do. Put the returned id on
 * that screen view directly rather than reading it back from the stack; [emitScreenView] is this plus
 * the emit.
 *
 * [screen] is the name the visit is of, the frame's own by default: pass it when the frame may not
 * have been revised to it yet. Main thread only.
 *
 * `@AutographInternalApi` for the same reason as [emitScreenView].
 */
@AutographInternalApi
public fun ScopeStack.startScreenVisit(handle: ScopeHandle, screen: String? = null): String? = startVisit(handle, screen)

/**
 * Ends the visit [handle]'s frame is in (#242), for a native screen capture whose surface stops being
 * viewed while its frame stays: the next view of it starts a new visit, and until then a tap resolved
 * from the frame must carry no visit id rather than the finished one's. A no-op when the frame holds
 * none. Main thread only.
 *
 * `@AutographInternalApi` for the same reason as [emitScreenView].
 */
@AutographInternalApi
public fun ScopeStack.endScreenVisit(handle: ScopeHandle) {
    endVisit(handle)
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
