package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.context.ScopeHandle

/**
 * Automatically records a `Screen Viewed` event for every destination change of this
 * [NavController] (`androidx.navigation`, a.k.a. navigation-compose).
 *
 * ```kotlin
 * val navController = rememberNavController()
 * navController.TrackScreenViews()
 * NavHost(navController, startDestination = "home") { ... }
 * ```
 *
 * The current destination's name is also declared as the screen, so autocaptured taps anywhere in
 * the `NavHost` carry it — a frame in the ambient [ScopeStack], the same channel [TrackedScreen]
 * uses. A [TrackedScreen] *inside* a destination still wins: this frame is pushed once, here, and
 * the destination's is pushed later and nested under it, so it refines the route rather than
 * competing with it. Call this above the `NavHost`, as the example does; the frame's position — not
 * its name — is what settles that, and it is claimed the moment this composable enters.
 *
 * A destination [screenName] maps to `null` is not tracked **and declares no screen**: taps on it
 * carry none rather than the route the user came from.
 *
 * @param screenName maps a destination to a screen name; return null to skip tracking
 *   that destination. Defaults to the destination route.
 */
@Composable
public fun NavController.TrackScreenViews(
    screenName: (NavDestination) -> String? = { it.route },
) {
    val tracker = LocalTracker.current
    val history = currentScreenHistory
    val stack = LocalScopeStack.current
    val parentHolder = LocalScopeParent.current
    // A plain box, like MirrorAmbientFrame's: written by the effect below and read only from the
    // listener, so it must not invalidate the composition.
    val handle = remember(stack) { arrayOfNulls<ScopeHandle>(1) }
    // Keyed on `history` as well as the tracker: the listener captures it, and it now travels with
    // the ambient ScopeStack rather than the tracker, so a caller that swaps the stack alone would
    // otherwise leave this listener writing to the history nobody reads any more.
    DisposableEffect(this, tracker, history, stack) {
        // Pushed BEFORE the listener is added, and that ordering is load-bearing twice over: the
        // listener fires synchronously on registration when a destination is already current, and
        // the frame must exist for that first call to fill in; and the position claimed here is what
        // puts the route frame below everything the NavHost's content composes, so a TrackedScreen
        // inside a destination refines the route instead of losing to it.
        val pushed = stack.push(parent = parentHolder?.get(0))
        handle[0] = pushed
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: androidx.savedstate.SavedState?,
            ) {
                val name = screenName(destination)
                // Revised in place — never re-pushed, which would move the route above the
                // destination content it encloses. A null name clears the frame as well as skipping
                // the report: an untracked destination must not leave the previous route's name
                // attributing taps on it. The parent goes in on every call because `update` replaces
                // the frame's contents wholesale, link included.
                stack.update(pushed, screen = name, parent = parentHolder?.get(0))
                if (name == null) return
                val previous = history.record(name)
                tracker.screen(name, withPreviousScreen(EmptyJsonObject, previous))
            }
        }
        addOnDestinationChangedListener(listener)
        onDispose {
            removeOnDestinationChangedListener(listener)
            stack.remove(pushed)
            handle[0] = null
        }
    }
}
