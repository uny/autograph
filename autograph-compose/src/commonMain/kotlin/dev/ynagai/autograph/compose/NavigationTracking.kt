package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    DisposableEffect(this, tracker) {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: androidx.savedstate.SavedState?,
            ) {
                val name = screenName(destination) ?: return
                val previous = ScreenLog.lastScreen
                val properties: JsonObject = if (previous != null) {
                    JsonObject(mapOf("previous_screen" to JsonPrimitive(previous)))
                } else {
                    EmptyJsonObject
                }
                tracker.screen(name, properties)
                ScreenLog.lastScreen = name
            }
        }
        addOnDestinationChangedListener(listener)
        onDispose { removeOnDestinationChangedListener(listener) }
    }
}
