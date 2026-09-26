package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * uses. A [TrackedScreen] *inside* a destination still wins: this frame is pushed once, when this
 * composable enters, and the destination's is pushed after it, so the later one resolves as the
 * screen (screen and section go by push order, not by nesting — the two frames are siblings). That
 * makes the call order load-bearing: **call this above the `NavHost`**, as the example does.
 *
 * A destination [screenName] maps to `null` is not tracked **and declares no screen**: taps on it
 * carry none rather than the route the user came from. [screenName] is read afresh on every
 * destination change, so replacing it takes effect from the next one — it is a mapping, not a
 * switch to flip mid-destination.
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
    // A plain box, written by the push effect below and read by the listener, so it must not
    // invalidate the composition.
    val handle = remember(stack) { arrayOfNulls<ScopeHandle>(1) }
    // The latest lambda, so the listener — which outlives a recomposition — does not keep calling
    // the one it captured when it was registered.
    val currentScreenName by rememberUpdatedState(screenName)
    // Keyed on the STACK ALONE, exactly as MirrorAmbientFrame's push is, and for the same reason: a
    // frame's position must not move while it is mounted. Folding this into the listener effect below
    // (which is keyed on the tracker) meant a tracker swap on logout removed and re-pushed the route
    // frame at the END of the list — above the still-mounted TrackedScreen of the destination on
    // display, whose own frame does not restart — and the route id then overrode the declared screen
    // and dropped its section. Pushed EMPTY; the listener fills it in.
    DisposableEffect(stack) {
        handle[0] = stack.push()
        onDispose {
            handle[0]?.let { stack.remove(it) }
            handle[0] = null
        }
    }
    // Keyed on `history` as well as the tracker: the listener captures it, and it now travels with
    // the ambient ScopeStack rather than the tracker, so a caller that swaps the stack alone would
    // otherwise leave this listener writing to the history nobody reads any more.
    DisposableEffect(this, tracker, history, stack) {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: androidx.savedstate.SavedState?,
            ) {
                val name = currentScreenName(destination)
                // Revised in place — never re-pushed, which would move the route above the
                // destination content it encloses. A null name clears the frame as well as skipping
                // the report: an untracked destination must not leave the previous route's name
                // attributing taps on it. The parent is re-read on every call: this listener is the
                // only place the route frame is ever linked.
                handle[0]?.let {
                    stack.reparent(it, parentHolder?.get(0))
                    stack.update(it, screen = name)
                }
                if (name == null) return
                val previous = history.record(name)
                tracker.screen(name, withPreviousScreen(EmptyJsonObject, previous))
            }
        }
        // The frame exists by now: the push effect above is declared first, and all DisposableEffects
        // of one apply phase run in composition order — which matters because this call fires the
        // listener SYNCHRONOUSLY when a destination is already current.
        addOnDestinationChangedListener(listener)
        onDispose { removeOnDestinationChangedListener(listener) }
    }
}
