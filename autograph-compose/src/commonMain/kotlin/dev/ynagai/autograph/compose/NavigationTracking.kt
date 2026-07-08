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
    val history = LocalScreenHistory.current
    DisposableEffect(this, tracker) {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: androidx.savedstate.SavedState?,
            ) {
                val name = screenName(destination) ?: return
                tracker.screen(name, withPreviousScreen(EmptyJsonObject, history.lastScreen))
                history.lastScreen = name
            }
        }
        addOnDestinationChangedListener(listener)
        onDispose { removeOnDestinationChangedListener(listener) }
    }
}
