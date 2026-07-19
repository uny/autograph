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

    /** The last screen [record]ed, or null before any screen has been viewed. */
    @Volatile
    public var lastScreen: String? = null
        private set

    /** Records [name] as the most recently viewed screen. */
    public fun record(name: String) {
        lastScreen = name
    }
}
