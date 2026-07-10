package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ElementRecordingTracker : Tracker {
    val tracked = mutableListOf<Triple<String, JsonObject, String?>>()
    val names: List<String> get() = tracked.map { it.first }
    override fun track(name: String, properties: JsonObject, target: String?) {
        // Mirrors AutographTracker.track's real merge of target into properties (see
        // Autograph.kt's withTarget), so assertions here read properties the same way a real
        // Tracker's callers would observe them.
        val merged = target?.let { JsonObject(properties + ("target" to kotlinx.serialization.json.JsonPrimitive(it))) } ?: properties
        tracked += Triple(name, merged, target)
    }
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

/** Provides [tracker] (and, when given, a [screenContext]) — the ambient wiring these composables read. */
@Composable
private fun WithElementTracker(tracker: Tracker, screenContext: ScreenContext? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTracker provides tracker,
        LocalScreenContext provides screenContext,
        content = content,
    )
}

@OptIn(ExperimentalTestApi::class)
class ElementTrackingUiTest {

    @Test
    fun trackClickFiresOnClickAndInvokesOnClick() = runComposeUiTest {
        val tracker = ElementRecordingTracker()
        var clicked = false
        setContent {
            WithElementTracker(tracker) {
                Box(
                    Modifier
                        .testTag("target")
                        .size(10.dp)
                        .trackClick("Item Clicked", target = "share_button") { clicked = true },
                )
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        assertEquals(listOf("Item Clicked"), tracker.names)
        assertEquals("share_button", tracker.tracked.single().second["target"]?.jsonPrimitive?.content)
        assertEquals(true, clicked, "the wrapped onClick must still run")
    }

    @Test
    fun trackClickMergesAmbientScreenContext() = runComposeUiTest {
        val tracker = ElementRecordingTracker()
        setContent {
            WithElementTracker(tracker, screenContext = ScreenContext("RecipeDetail", section = "actions")) {
                Box(Modifier.testTag("target").size(10.dp).trackClick("Item Clicked") {})
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        val properties = tracker.tracked.single().second
        assertEquals("RecipeDetail", properties["screen"]?.jsonPrimitive?.content)
        assertEquals("actions", properties["section"]?.jsonPrimitive?.content)
    }

    @Test
    fun trackImpressionFiresOnceWhenVisibleForTheMinimumDuration() = runComposeUiTest {
        val tracker = ElementRecordingTracker()
        setContent {
            WithElementTracker(tracker) {
                Box(Modifier.size(10.dp).trackImpression("Item Viewed", minDurationMs = 100L))
            }
        }
        waitForIdle()

        // Before the minimum dwell time elapses, the impression must not have fired yet.
        mainClock.advanceTimeBy(50L)
        waitForIdle()
        assertEquals(emptyList(), tracker.names, "must not fire before minDurationMs elapses")

        mainClock.advanceTimeBy(100L)
        waitForIdle()
        assertEquals(listOf("Item Viewed"), tracker.names)

        // Further recompositions/time passing must not re-fire the same impression.
        mainClock.advanceTimeBy(1_000L)
        waitForIdle()
        assertEquals(listOf("Item Viewed"), tracker.names, "an impression must fire at most once")
    }

    @Test
    fun trackImpressionMergesAmbientScreenContext() = runComposeUiTest {
        val tracker = ElementRecordingTracker()
        setContent {
            WithElementTracker(tracker, screenContext = ScreenContext("Home")) {
                Box(Modifier.size(10.dp).trackImpression("Item Viewed", minDurationMs = 0L))
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(10L)
        waitForIdle()

        val properties = tracker.tracked.single().second
        assertEquals("Home", properties["screen"]?.jsonPrimitive?.content)
        assertNull(properties["section"], "no section was provided in the ambient context")
    }
}
