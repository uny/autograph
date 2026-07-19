package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import dev.ynagai.autograph.EmptyJsonObject

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
 * @param screenName maps a destination to a screen name; return null to skip tracking
 *   that destination. Defaults to the destination route.
 */
@Composable
public fun NavController.TrackScreenViews(
    screenName: (NavDestination) -> String? = { it.route },
) {
    val tracker = LocalTracker.current
    val history = currentScreenHistory
    // Keyed on `history` as well as the tracker: the listener captures it, and it now travels with
    // the ambient ScopeStack rather than the tracker, so a caller that swaps the stack alone would
    // otherwise leave this listener writing to the history nobody reads any more.
    DisposableEffect(this, tracker, history) {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: androidx.savedstate.SavedState?,
            ) {
                val name = screenName(destination) ?: return
                val previous = history.record(name)
                tracker.screen(name, withPreviousScreen(EmptyJsonObject, previous))
            }
        }
        addOnDestinationChangedListener(listener)
        onDispose { removeOnDestinationChangedListener(listener) }
    }
}
