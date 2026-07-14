package dev.ynagai.autograph.context

import dev.ynagai.autograph.EmptyJsonObject
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ambient scope + screen context of the currently visible UI, as a stack of [push]ed frames.
 *
 * This is the non-Compose home for what [AutographScope][dev.ynagai.autograph] and screen context
 * express inside Compose via `CompositionLocal`: a place that autocapture pipelines can read at the
 * moment a tap or screen view is observed, regardless of which UI framework produced it (Compose,
 * UIKit/SwiftUI, or the Android View system). Each surface pushes a frame when a screen or scope
 * comes into view and removes it when it leaves; the capture pipeline calls [current] and
 * [AmbientContext.enrich] to attribute the event.
 *
 * **Read this only from auto-capture code, not from explicit `track` calls.** The stack answers
 * "what was on screen when the user acted", which is the right question for an autocaptured tap but
 * the *wrong* one for an arbitrary `track(...)` — a background event (e.g. a network response that
 * completes after navigation) must not silently inherit whatever screen happens to be visible.
 * Explicit instrumentation keeps its own lexical scope; see the `autograph-compose` decorator.
 *
 * **Own an instance; don't share one globally.** Scope a [ScopeStack] to the tracker it feeds (as
 * `autograph-compose` scopes screen history to its provider) so context can't leak across trackers
 * or outlive a tracker swap on logout.
 *
 * **Threading.** [push] and [remove] must be called from the main thread (they mutate the frame
 * list). [current] is lock-free and safe from any thread: it returns an immutable snapshot that is
 * republished atomically on every mutation, so a background reader always sees a whole, consistent
 * context — never a half-applied one.
 */
public class ScopeStack {

    // Insertion-ordered; "innermost wins" for screen/section means later frames override earlier.
    // Mutated only from the main thread (see class kdoc), so a plain list needs no guard of its own.
    private val frames = ArrayList<ScopeFrame>()

    @Volatile
    private var snapshot: AmbientContext = AmbientContext.Empty

    /**
     * Pushes a frame contributing [scope] properties (low precedence — an explicit call-site
     * property always wins over them) and/or a [screen]/[section] (reserved keys, high precedence).
     * Returns a [ScopeHandle] to [remove] it with. A frame may carry scope only (an `AutographScope`
     * analogue), screen only (a `TrackedScreen` analogue), or both.
     */
    public fun push(
        scope: JsonObject = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
    ): ScopeHandle {
        val frame = ScopeFrame(scope, screen, section)
        frames.add(frame)
        snapshot = recompute()
        return ScopeHandle(frame)
    }

    /**
     * Removes the frame [handle] refers to, by identity and independent of position — screen
     * transitions (a Compose `Crossfade`, an iOS interactive-pop that the user cancels) do not
     * guarantee frames leave in push order, so a positional pop would remove the wrong one.
     * Removing a handle that was already removed, or one from another stack, is a no-op.
     */
    public fun remove(handle: ScopeHandle) {
        if (frames.remove(handle.frame)) {
            snapshot = recompute()
        }
    }

    /** The current merged ambient context. Lock-free; safe from any thread. */
    public fun current(): AmbientContext = snapshot

    private fun recompute(): AmbientContext {
        if (frames.isEmpty()) return AmbientContext.Empty
        var scope: JsonObject = EmptyJsonObject
        var screen: String? = null
        var section: String? = null
        for (frame in frames) {
            if (frame.scope.isNotEmpty()) {
                scope = if (scope.isEmpty()) frame.scope else JsonObject(scope + frame.scope)
            }
            // Screen and section track independently so an inner section marker can refine an outer
            // screen's context (screen from the outer frame, section from the inner one).
            if (frame.screen != null) screen = frame.screen
            if (frame.section != null) section = frame.section
        }
        return AmbientContext(scope, screen, section)
    }
}

/** One pushed contribution to a [ScopeStack]. Opaque to callers; they hold a [ScopeHandle]. */
internal class ScopeFrame(
    val scope: JsonObject,
    val screen: String?,
    val section: String?,
)

/** An opaque token identifying a pushed frame, for [ScopeStack.remove]. */
public class ScopeHandle internal constructor(internal val frame: ScopeFrame)

/**
 * An immutable snapshot of a [ScopeStack]: the accumulated [scope] defaults plus the effective
 * [screen] / [section]. Use [enrich] to apply it to an event's properties.
 */
public class AmbientContext internal constructor(
    public val scope: JsonObject,
    public val screen: String?,
    public val section: String?,
) {
    /**
     * Returns [properties] enriched with this context: [scope] merged underneath (so an explicit
     * property in [properties] wins over a scope default), then [screen] / [section] written on top
     * under those reserved keys (overwriting any same-named entry) — the same precedence the Compose
     * path applies via its scope decorator and `withScreenContext`.
     */
    public fun enrich(properties: JsonObject): JsonObject {
        var result = if (scope.isEmpty()) properties else JsonObject(scope + properties)
        if (screen != null) result = JsonObject(result + ("screen" to JsonPrimitive(screen)))
        if (section != null) result = JsonObject(result + ("section" to JsonPrimitive(section)))
        return result
    }

    internal companion object {
        val Empty: AmbientContext = AmbientContext(EmptyJsonObject, null, null)
    }
}
