package dev.ynagai.autograph.context

import kotlin.concurrent.Volatile

/**
 * The most recently viewed screen, so the next screen view can be enriched with `previous_screen`
 * and an autocaptured event can fall back to a screen when no [ScopeStack] frame supplies one.
 *
 * This lives beside [ScopeStack] rather than inside a UI framework for the same reason [ScopeStack]
 * does: `previous_screen` has to stay continuous across a Compose↔native transition, and it cannot be
 * if only one of the two pipelines can reach the history. Every surface that emits a screen view
 * [record]s it here, whichever framework produced the screen.
 *
 * **This is history, not current state.** [lastScreen] deliberately outlives the screen it names —
 * that is the whole point of `previous_screen`. Ask [ScopeStack.current] for what is on screen *now*;
 * ask this for what came before.
 *
 * **Threading.** [record] is expected on the main thread, alongside [ScopeStack]'s mutators. Reads are
 * safe from any thread: [lastScreen] is a single volatile reference, so a reader sees either the old
 * name or the new one, never a torn value.
 */
public class ScreenHistory {

    /**
     * The last screen [record]ed, or null before any screen has been viewed.
     *
     * There is deliberately no way to clear this. History is owned by the [ScopeStack] that carries
     * it, so the way to drop it — on logout, say — is to replace that stack, which drops the ambient
     * frames with it. Resetting one but not the other would leave a screen attributed to a context
     * that no longer exists.
     */
    @Volatile
    public var lastScreen: String? = null
        private set

    /**
     * Records [name] as the most recently viewed screen, returning the screen it replaces — i.e.
     * exactly the `previous_screen` of the view being recorded, or null if this is the first.
     *
     * Returning the displaced value rather than `Unit` makes the one correct usage the shortest one.
     * Every emit site needs the same three steps — read the previous screen, emit with it, record the
     * new one — and doing them in the wrong order silently attributes a screen view to *itself* as its
     * own `previous_screen`. That is bad data with no error to notice, so the API hands the caller the
     * right value instead of trusting the ordering:
     *
     * ```kotlin
     * val previous = history.record(name)
     * tracker.screen(name, withPreviousScreen(properties, previous))
     * ```
     */
    public fun record(name: String): String? {
        val previous = lastScreen
        lastScreen = name
        return previous
    }
}
