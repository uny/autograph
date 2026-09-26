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
import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RecordingTracker : Tracker {
    val screens = mutableListOf<Pair<String, JsonObject>>()
    val names: List<String> get() = screens.map { it.first }
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {}
    override fun screen(name: String, properties: Map<String, JsonElement>) {
        screens += name to properties.asJsonObject()
    }
    override fun identify(userId: String, traits: Map<String, JsonElement>) {}
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

/** [WithTracker] over a caller-supplied [stack], for the cases that push frames onto it first. */
@Composable
private fun WithTracker(tracker: Tracker, stack: ScopeStack, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTracker provides tracker,
        LocalScopeStack provides stack,
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
    fun trackedScreenSectionReachesTheScreenContextButNotTheScreenViewedEvent() = runComposeUiTest {
        val tracker = RecordingTracker()
        var captured: ScreenContext? = null
        setContent {
            WithTracker(tracker) {
                TrackedScreen("Article", section = "Header") {
                    captured = LocalScreenContext.current
                }
            }
        }
        waitForIdle()

        assertEquals(ScreenContext("Article", "Header"), captured)
        // A section refines the events fired inside the screen; it is not a destination of its own,
        // so the Screen Viewed event itself stays a plain screen view.
        assertEquals(listOf("Article"), tracker.names)
        assertNull(tracker.screens[0].second["section"])
    }

    @Test
    fun changingOnlyTheSectionRefreshesTheContextWithoutRefiringTheScreenView() = runComposeUiTest {
        val tracker = RecordingTracker()
        var section by mutableStateOf("Header")
        var captured: ScreenContext? = null
        setContent {
            WithTracker(tracker) {
                TrackedScreen("Article", section = section) {
                    captured = LocalScreenContext.current
                }
            }
        }
        waitForIdle()
        assertEquals(ScreenContext("Article", "Header"), captured)

        section = "Recommendations"
        waitForIdle()

        // The new section reaches nested instrumentation, but the user did not navigate anywhere —
        // a section change must not look like a second screen view.
        assertEquals(ScreenContext("Article", "Recommendations"), captured)
        assertEquals(listOf("Article"), tracker.names)
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
    fun navTrackScreenViewsDeclaresTheCurrentRoute() = runComposeUiTest {
        val stack = ScopeStack()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                navController.TrackScreenViews()
                NavHost(navController, startDestination = "home") {
                    composable("home") {}
                    composable("detail") {}
                }
            }
        }
        waitForIdle()
        assertEquals("home", stack.current().screen)

        runOnUiThread { navController.navigate("detail") }
        waitForIdle()
        // Revised in place, not re-pushed: the route frame keeps its position below the NavHost's
        // content for the life of the composition.
        assertEquals("detail", stack.current().screen)
    }

    @Test
    fun aTrackedScreenInsideADestinationRefinesTheRouteRatherThanLosingToIt() = runComposeUiTest {
        // Both halves in one navigation, which is what makes it discriminating: the route names the
        // destination that declares nothing, and the one that declares wins over the route. Asserting
        // only the second passes with no route frame at all.
        val stack = ScopeStack()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                navController.TrackScreenViews()
                NavHost(navController, startDestination = "home") {
                    composable("home") { TrackedScreen("Home", section = "Top") {} }
                    composable("plain") {}
                }
            }
        }
        waitForIdle()
        assertEquals("Home", stack.current().screen)
        assertEquals("Top", stack.current().section)

        runOnUiThread { navController.navigate("plain") }
        waitForIdle()
        assertEquals("plain", stack.current().screen, "a destination that declares nothing is named by the route")
    }

    @Test
    fun theRouteFrameLeavesWhenTheCompositionDoes() = runComposeUiTest {
        val stack = ScopeStack()
        var shown by mutableStateOf(true)
        setContent {
            val navController = rememberNavController()
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                if (shown) {
                    navController.TrackScreenViews()
                    NavHost(navController, startDestination = "home") { composable("home") {} }
                }
            }
        }
        waitForIdle()
        assertEquals("home", stack.current().screen)

        shown = false
        waitForIdle()
        // A route frame that outlived its composition would keep naming a destination nobody is on —
        // and, on a stack shared with a native pipeline, would attribute native taps to it.
        assertNull(stack.current().screen)
    }

    @Test
    fun aBareTrackScreenViewInsideATrackedScreenSplitsTheTwoPaths() = runComposeUiTest {
        // The documented divergence, pinned in both directions: the frame (what an autocaptured tap
        // reads) names the inner screen, while LocalScreenContext (what trackClick reads) keeps the
        // enclosing one, because a bare TrackScreenView deliberately does not provide it.
        val stack = ScopeStack()
        var context: ScreenContext? = null
        setContent {
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackedScreen("Outer", section = "Top") {
                    TrackScreenView("Inner")
                    context = LocalScreenContext.current
                }
            }
        }
        waitForIdle()

        assertEquals("Inner", stack.current().screen)
        assertNull(stack.current().section, "a frame that names a screen owns its section")
        assertEquals(ScreenContext("Outer", "Top"), context)
    }

    @Test
    fun aTrackerSwapDoesNotMoveTheRouteFrameAboveTheScreenOnDisplay() {
        // The route frame's position must hold for the life of the composition. Pushing it from the
        // listener's effect — which is keyed on the tracker — meant a swap on logout re-pushed it at
        // the end of the list, above the still-mounted TrackedScreen, and the route id then overrode
        // the declared screen and dropped its section.
        val shared = ScopeStack()
        var swapped by mutableStateOf(false)
        runComposeUiTest {
            setContent {
                val navController = rememberNavController()
                CompositionLocalProvider(
                    LocalTracker provides if (swapped) RecordingTracker() else RecordingTracker(),
                    LocalScopeStack provides shared,
                ) {
                    navController.TrackScreenViews()
                    NavHost(navController, startDestination = "home") {
                        composable("home") { TrackedScreen("Home", section = "Top") {} }
                    }
                }
            }
            waitForIdle()
            assertEquals("Home", shared.current().screen)

            swapped = true
            waitForIdle()

            assertEquals("Home", shared.current().screen)
            assertEquals("Top", shared.current().section)
        }
    }

    @Test
    fun anUntrackedDestinationDeclaresNoScreenRatherThanKeepingThePreviousRoute() = runComposeUiTest {
        // Opting a destination out must not leave the route the user came FROM attributing taps on
        // it — the wrong-value failure #216 is about. Measured before the fix: "home".
        val tracker = RecordingTracker()
        val stack = ScopeStack()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScopeStack provides stack,
            ) {
                navController.TrackScreenViews(screenName = { it.route?.takeIf { r -> r != "private" } })
                NavHost(navController, startDestination = "home") {
                    composable("home") {}
                    composable("private") {}
                }
            }
        }
        waitForIdle()

        runOnUiThread { navController.navigate("private") }
        waitForIdle()

        assertNull(stack.current().screen)
        assertEquals(listOf("home"), tracker.names, "an opted-out destination is not reported either")
    }

    @Test
    fun aBareTrackScreenViewDeclaresItsScreenForAutocapture() = runComposeUiTest {
        // A bare TrackScreenView pushes a frame like TrackedScreen does, so an autocaptured tap
        // under it carries the screen. It does NOT provide LocalScreenContext, which is the whole
        // difference between the two — an explicit trackClick nested inside a TrackedScreen still
        // reads the enclosing screen.
        val stack = ScopeStack()
        setContent {
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                TrackScreenView("Home")
            }
        }
        waitForIdle()

        assertEquals("Home", stack.current().screen)
    }

    @Test
    fun aBareTrackScreenViewsFrameLeavesWithIt() = runComposeUiTest {
        val stack = ScopeStack()
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(
                LocalTracker provides RecordingTracker(),
                LocalScopeStack provides stack,
            ) {
                if (shown) TrackScreenView("Home")
            }
        }
        waitForIdle()
        assertEquals("Home", stack.current().screen)

        shown = false
        waitForIdle()
        assertNull(stack.current().screen, "the declaration is scoped to the composition, not to history")
    }

    @Test
    fun aTrackedScreenEmitsExactlyOnceDespiteOwningItsFrame() = runComposeUiTest {
        // TrackedScreen used to call TrackScreenView, which now pushes a frame of its own; the
        // reporting half is split out so the screen is declared once and emitted once.
        val tracker = RecordingTracker()
        val stack = ScopeStack()
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScopeStack provides stack,
            ) {
                TrackedScreen("Home", section = "Top") {}
            }
        }
        waitForIdle()

        assertEquals(listOf("Home"), tracker.names)
        assertEquals("Home", stack.current().screen)
        assertEquals("Top", stack.current().section)
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
    fun aCallerSuppliedStackKeepsItsHistoryAcrossATrackerSwap() = runComposeUiTest {
        // Deliberate, and a change from when history was keyed on the tracker alone: a stack the
        // caller owns is never swapped out from under the native pipeline holding it, and its history
        // travels with it. So swapping ONLY the tracker does not reset previous_screen here.
        //
        // The remedy is to replace the stack, not the tracker — which is the same obligation the
        // caller already has for the stack's frames, and why ScreenHistory offers no clear(): resetting
        // history while leaving the frames would attribute a screen to a context that no longer exists.
        // Pinned because it is a real trade, not an accident: an app that swaps trackers on logout and
        // keeps its stack will carry the pre-logout screen into the first post-logout previous_screen.
        val shared = ScopeStack()
        val before = RecordingTracker()
        val after = RecordingTracker()
        var step by mutableStateOf(0)
        setContent {
            val (tracker, screen) = if (step == 0) before to "Home" else after to "Login"
            AutographProvider(tracker, scopeStack = shared) {
                TrackScreenView(screen)
            }
        }
        waitForIdle()
        step = 1
        waitForIdle()

        assertEquals(
            "Home",
            after.screens.single().second.previousScreen(),
            "a caller-owned stack carries its history across a tracker swap",
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

    @Test
    fun aSiblingSurfacesScopeDoesNotReachAScreenView() {
        // A Compose `Screen Viewed` is an explicit emit and reads nothing off the ambient stack;
        // app-wide context reaches it through DefaultProperties instead (#253). A sibling surface's
        // declaration, which the ambient snapshot would show, must stay off it.
        runComposeUiTest {
            val tracker = RecordingTracker()
            val stack = ScopeStack()
            val sibling = stack.pushSurface(screen = "Sibling")
            stack.push(parent = sibling, scope = mapOf("row" to JsonPrimitive("3")))
            setContent {
                WithTracker(tracker, stack) { TrackScreenView("Home") }
            }
            waitForIdle()

            assertNull(tracker.screens.single().second["row"], "a sibling surface's scope is not ours to carry")
        }
    }
}
