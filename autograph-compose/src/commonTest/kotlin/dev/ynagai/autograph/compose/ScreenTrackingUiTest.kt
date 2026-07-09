package dev.ynagai.autograph.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RecordingTracker : Tracker {
    val screens = mutableListOf<Pair<String, JsonObject>>()
    override fun track(name: String, properties: JsonObject) {}
    override fun screen(name: String, properties: JsonObject) {
        screens += name to properties
    }
    override fun identify(userId: String, traits: JsonObject) {}
}

private fun JsonObject.previousScreen(): String? = this["previous_screen"]?.jsonPrimitive?.content

@OptIn(ExperimentalTestApi::class)
class ScreenTrackingUiTest {

    @Test
    fun trackScreenViewFiresOnceOnEntry() = runComposeUiTest {
        val tracker = RecordingTracker()
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScreenHistory provides ScreenHistory(),
            ) {
                TrackScreenView("Home")
            }
        }
        waitForIdle()

        assertEquals(listOf("Home"), tracker.screens.map { it.first })
        assertNull(tracker.screens[0].second.previousScreen(), "the first screen has no previous")
    }

    @Test
    fun trackScreenViewRefiresWithPreviousScreenOnNameChange() = runComposeUiTest {
        val tracker = RecordingTracker()
        var name by mutableStateOf("Home")
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScreenHistory provides ScreenHistory(),
            ) {
                TrackScreenView(name)
            }
        }
        waitForIdle()

        name = "Detail"
        waitForIdle()

        // The screen re-fires when its name changes, and the second view carries the first as
        // previous_screen — the propagation the pure withPreviousScreen test could not exercise.
        assertEquals(listOf("Home", "Detail"), tracker.screens.map { it.first })
        assertNull(tracker.screens[0].second.previousScreen())
        assertEquals("Home", tracker.screens[1].second.previousScreen())
    }

    @Test
    fun trackedScreenProvidesScreenContextAndFiresView() = runComposeUiTest {
        val tracker = RecordingTracker()
        var captured: ScreenContext? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScreenHistory provides ScreenHistory(),
            ) {
                TrackedScreen("Cart") {
                    captured = LocalScreenContext.current
                }
            }
        }
        waitForIdle()

        assertEquals(ScreenContext("Cart"), captured, "nested content sees the ambient screen")
        assertEquals(listOf("Cart"), tracker.screens.map { it.first })
    }

    @Test
    fun navTrackScreenViewsFiresPerDestinationWithPreviousScreen() = runComposeUiTest {
        val tracker = RecordingTracker()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScreenHistory provides ScreenHistory(),
            ) {
                navController.TrackScreenViews()
                NavHost(navController, startDestination = "home") {
                    composable("home") {}
                    composable("detail") {}
                }
            }
        }
        waitForIdle()

        runOnUiThread { navController.navigate("detail") }
        waitForIdle()

        // Both the start destination and the navigated-to destination are tracked, and the second
        // carries the first as previous_screen — automatic screen-to-screen propagation over nav.
        assertEquals(listOf("home", "detail"), tracker.screens.map { it.first })
        assertNull(tracker.screens[0].second.previousScreen())
        assertEquals("home", tracker.screens[1].second.previousScreen())
    }

    @Test
    fun autographProviderRoutesEventsToTheProvidedTracker() = runComposeUiTest {
        val tracker = RecordingTracker()
        setContent {
            AutographProvider(tracker) {
                TrackScreenView("Home")
            }
        }
        waitForIdle()

        assertEquals(listOf("Home"), tracker.screens.map { it.first })
    }

    @Test
    fun autographProviderResetsPreviousScreenWhenTrackerReplaced() = runComposeUiTest {
        val before = RecordingTracker()
        val after = RecordingTracker()
        // Drives the screen name and the active tracker together so each change re-fires the view.
        var step by mutableStateOf(0)
        setContent {
            val (tracker, screen) = when (step) {
                0 -> before to "Home"
                1 -> before to "Detail"
                else -> after to "Login"
            }
            AutographProvider(tracker) {
                TrackScreenView(screen)
            }
        }
        waitForIdle()

        step = 1
        waitForIdle()
        step = 2
        waitForIdle()

        // Within one tracker, previous_screen propagates (Detail follows Home).
        assertEquals(listOf("Home", "Detail"), before.screens.map { it.first })
        assertEquals("Home", before.screens[1].second.previousScreen())

        // Replacing the tracker (e.g. after logout) gives a fresh history, so the first screen on
        // the new tracker must NOT inherit "Detail" as its previous_screen.
        assertEquals(listOf("Login"), after.screens.map { it.first })
        assertNull(after.screens[0].second.previousScreen(), "previous_screen leaked across trackers")
    }
}
