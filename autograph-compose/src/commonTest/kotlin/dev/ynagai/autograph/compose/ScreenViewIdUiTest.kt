package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ynagai.autograph.EventIdGenerator
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Records the `screen_view_id` each screen view and each event carries, by name. */
private class VisitRecordingTracker : Tracker {
    val screens = mutableListOf<Pair<String, String?>>()
    val tracks = mutableListOf<Pair<String, String?>>()
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        tracks += name to properties.screenViewId()
    }
    override fun screen(name: String, properties: Map<String, JsonElement>) {
        screens += name to properties.screenViewId()
    }
    override fun identify(userId: String, traits: Map<String, JsonElement>) {}
}

private fun Map<String, JsonElement>.screenViewId(): String? =
    this[RESERVED_METADATA_KEY]?.let {
        Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject["screen_view_id"]?.jsonPrimitive?.content
    }

private fun visits(vararg visits: Pair<String, String?>): List<Pair<String, String?>> = visits.toList()

private fun sequentialStack(): ScopeStack {
    var next = 0
    return ScopeStack(EventIdGenerator { "id-${++next}" })
}

/**
 * The visit a Compose screen view starts (#242) and which events carry it: the explicit
 * `trackClick` lexically, through the `TrackedScreen` it sits in; autocapture through the stack.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenViewIdUiTest {

    @Test
    fun aTrackedScreensClicksCarryTheVisitItsScreenViewStarted() = runComposeUiTest {
        val tracker = VisitRecordingTracker()
        val stack = sequentialStack()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                TrackedScreen("Detail") {
                    Box(Modifier.size(10.dp).testTag("save").trackClick("save_tapped") {})
                }
            }
        }
        waitForIdle()
        onNodeWithTag("save").performClick()
        waitForIdle()

        assertEquals(visits("Detail" to "id-1"), tracker.screens)
        assertEquals(visits("save_tapped" to "id-1"), tracker.tracks)
        assertEquals("id-1", stack.current().screenViewId, "autocapture reads the same visit from the stack")
    }

    @Test
    fun renamingAScreenStartsANewVisitAndAChangeOfSectionDoesNot() = runComposeUiTest {
        val tracker = VisitRecordingTracker()
        val stack = sequentialStack()
        var name by mutableStateOf("List")
        var section by mutableStateOf("Tab A")
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                TrackedScreen(name, section = section) {
                    Box(Modifier.size(10.dp).testTag("open").trackClick("open_tapped") {})
                }
            }
        }
        waitForIdle()
        section = "Tab B"
        waitForIdle()
        assertEquals("id-1", stack.current().screenViewId, "a section change keeps the visit")

        name = "Detail"
        waitForIdle()
        onNodeWithTag("open").performClick()
        waitForIdle()

        assertEquals(visits("List" to "id-1", "Detail" to "id-2"), tracker.screens)
        assertEquals(visits("open_tapped" to "id-2"), tracker.tracks)
        assertEquals("id-2", stack.current().screenViewId)
    }

    @Test
    fun aBareTrackScreenViewStartsAVisitForAutocaptureOnly() = runComposeUiTest {
        val tracker = VisitRecordingTracker()
        val stack = sequentialStack()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                TrackScreenView("Sheet")
                Box(Modifier.size(10.dp).testTag("close").trackClick("close_tapped") {})
            }
        }
        waitForIdle()
        onNodeWithTag("close").performClick()
        waitForIdle()

        assertEquals(visits("Sheet" to "id-1"), tracker.screens)
        assertEquals("id-1", stack.current().screenViewId)
        // Like the screen name: a bare TrackScreenView provides no lexical context to trackClick.
        assertEquals(visits("close_tapped" to null), tracker.tracks)
    }

    @Test
    fun everyDestinationChangeIsANewVisitOfTheRoute() = runComposeUiTest {
        val tracker = VisitRecordingTracker()
        val stack = sequentialStack()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                navController.TrackScreenViews()
                NavHost(navController, startDestination = "home") {
                    composable("home") {}
                    composable("detail") {}
                }
            }
        }
        waitForIdle()
        assertEquals("id-1", stack.current().screenViewId)

        runOnUiThread { navController.navigate("detail") }
        waitForIdle()
        runOnUiThread { navController.popBackStack() }
        waitForIdle()

        assertEquals(visits("home" to "id-1", "detail" to "id-2", "home" to "id-3"), tracker.screens)
        assertEquals("id-3", stack.current().screenViewId)
    }

    @Test
    fun aTrackedScreenInsideADestinationLeavesAutocaptureWithNoVisit() = runComposeUiTest {
        // The route frame and the destination's TrackedScreen are siblings that both name a screen,
        // so a tap read from the stack cannot be tied to one visit and carries none — the screen
        // name still goes to the TrackedScreen. Its own trackClick keeps the lexical visit.
        val tracker = VisitRecordingTracker()
        val stack = sequentialStack()
        lateinit var navController: NavHostController
        setContent {
            navController = rememberNavController()
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                navController.TrackScreenViews()
                NavHost(navController, startDestination = "home") {
                    composable("home") {
                        TrackedScreen("Home") {
                            Box(Modifier.size(10.dp).testTag("save").trackClick("save_tapped") {})
                        }
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("save").performClick()
        waitForIdle()

        assertEquals("Home", stack.current().screen)
        assertNull(stack.current().screenViewId)
        val homeVisit = tracker.screens.single { it.first == "Home" }.second
        assertNotEquals(null, homeVisit)
        assertEquals(listOf("save_tapped" to homeVisit), tracker.tracks)
    }
}
