package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
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
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RecordingTracker : Tracker {
    val screens = mutableListOf<Pair<String, JsonObject>>()
    val names: List<String> get() = screens.map { it.first }
    override fun track(name: String, properties: JsonObject, target: String?) {}
    override fun screen(name: String, properties: JsonObject) {
        screens += name to properties
    }
    override fun identify(userId: String, traits: JsonObject) {}
}

private fun JsonObject.previousScreen(): String? = this["previous_screen"]?.jsonPrimitive?.content

/**
 * Provides [tracker] plus a fresh [ScopeStack] to [content] — the ambient wiring the screen-tracking
 * composables read. Kept independent of [AutographProvider] (whose own tests exercise that path) so
 * these cases stay decoupled from its lifecycle side effects.
 *
 * The stack is what carries screen history (see [ScopeStack.screenHistory]), and it must be a fresh
 * one per test: the fallback stack these composables would otherwise read is a shared global, so
 * `previous_screen` would leak from one test into the next.
 */
@Composable
private fun WithTracker(tracker: Tracker, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTracker provides tracker,
        LocalScopeStack provides ScopeStack(),
        content = content,
    )
}

@OptIn(ExperimentalTestApi::class)
class ScreenTrackingUiTest {

    @Test
    fun trackScreenViewFiresOnceOnEntry() = runComposeUiTest {
        val tracker = RecordingTracker()
        setContent {
            WithTracker(tracker) {
                TrackScreenView("Home")
            }
        }
        waitForIdle()

        assertEquals(listOf("Home"), tracker.names)
        assertNull(tracker.screens[0].second.previousScreen(), "the first screen has no previous")
    }

    @Test
    fun trackScreenViewRefiresWithPreviousScreenOnNameChange() = runComposeUiTest {
        val tracker = RecordingTracker()
        var name by mutableStateOf("Home")
        setContent {
            WithTracker(tracker) {
                TrackScreenView(name)
            }
        }
        waitForIdle()

        name = "Detail"
        waitForIdle()

        // The screen re-fires when its name changes, and the second view carries the first as
        // previous_screen — the propagation the pure withPreviousScreen test could not exercise.
        assertEquals(listOf("Home", "Detail"), tracker.names)
        assertNull(tracker.screens[0].second.previousScreen())
        assertEquals("Home", tracker.screens[1].second.previousScreen())
    }

    @Test
    fun trackedScreenProvidesScreenContextAndFiresView() = runComposeUiTest {
        val tracker = RecordingTracker()
        var captured: ScreenContext? = null
        setContent {
            WithTracker(tracker) {
                TrackedScreen("Cart") {
                    captured = LocalScreenContext.current
                }
            }
        }
        waitForIdle()

        assertEquals(ScreenContext("Cart"), captured, "nested content sees the ambient screen")
        assertEquals(listOf("Cart"), tracker.names)
    }

    @Test
    fun navTrackScreenViewsFiresPerDestinationWithPreviousScreen() = runComposeUiTest {
        val tracker = RecordingTracker()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            WithTracker(tracker) {
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
        assertEquals(listOf("home", "detail"), tracker.names)
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

        assertEquals(listOf("Home"), tracker.names)
    }

    @Test
    fun composeScreenViewsLandInACallerSuppliedStacksHistory() = runComposeUiTest {
        // The hybrid contract, and the whole point of moving history onto ScopeStack: an app hands
        // the SAME stack to AutographProvider and to the native pipeline, and the native side must be
        // able to read a screen Compose viewed. Before this, ScreenHistory was internal to
        // autograph-compose and reachable only through a CompositionLocal, so a native pipeline had
        // no way to see it at all and previous_screen could not be continuous across the boundary.
        val shared = ScopeStack()
        val tracker = RecordingTracker()
        setContent {
            AutographProvider(tracker, scopeStack = shared) {
                TrackScreenView("ComposeFeed")
            }
        }
        waitForIdle()

        // What the native pipeline would read when it next emits a screen view or an autocaptured tap.
        assertEquals("ComposeFeed", shared.screenHistory.lastScreen)
    }

    @Test
    fun aScreenViewedNativelyBecomesTheNextComposeScreensPreviousScreen() = runComposeUiTest {
        // The other direction across the same boundary. The native half is represented by a direct
        // `record` on the shared stack, which is exactly what the native screen capture will do —
        // there is no iOS runtime in a common test, so this pins the contract between the two halves,
        // not the iOS mechanism. The mechanism is verified in the sample-ios XCUITest suite.
        val shared = ScopeStack()
        shared.screenHistory.record("NativeSettings")

        val tracker = RecordingTracker()
        setContent {
            AutographProvider(tracker, scopeStack = shared) {
                TrackScreenView("ComposeFeed")
            }
        }
        waitForIdle()

        assertEquals(
            "NativeSettings",
            tracker.screens.single().second.previousScreen(),
            "a Compose screen entered from a native one must carry it as previous_screen",
        )
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
        assertEquals(listOf("Home", "Detail"), before.names)
        assertEquals("Home", before.screens[1].second.previousScreen())

        // Replacing the tracker (e.g. after logout) gives a fresh history, so the first screen on
        // the new tracker must NOT inherit "Detail" as its previous_screen.
        assertEquals(listOf("Login"), after.names)
        assertNull(after.screens[0].second.previousScreen(), "previous_screen leaked across trackers")
    }
}
