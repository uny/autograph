package dev.ynagai.autograph.compose

import dev.ynagai.autograph.asJsonObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ElementRecordingTracker : Tracker {
    val tracked = mutableListOf<Triple<String, JsonObject, String?>>()
    val names: List<String> get() = tracked.map { it.first }
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        // Mirrors AutographTracker.track's real merge of target into properties.asJsonObject() (see
        // Autograph.kt's withTarget), so assertions here read properties.asJsonObject() the same way a real
        // Tracker's callers would observe them.
        val merged = target?.let { JsonObject(properties.asJsonObject() + ("target" to kotlinx.serialization.json.JsonPrimitive(it))) } ?: properties.asJsonObject()
        tracked += Triple(name, merged, target)
    }
    override fun screen(name: String, properties: Map<String, JsonElement>) {}
    override fun identify(userId: String, traits: Map<String, JsonElement>) {}
}

/** The root-space centre of the element tagged [tag] — where a user tapping it would land. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centreOfNode(tag: String): Offset =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

/**
 * The unmerged semantics root, the node `ElementResolver.android.kt` hit-tests from. Unmerged
 * matters: on the merged tree a descendant's marker folds into its clickable ancestor's config.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.unmergedRoot(): SemanticsNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

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
    fun trackImpressionFiresWhenElementScrollsIntoView() = runComposeUiTest {
        val tracker = ElementRecordingTracker()
        setContent {
            WithElementTracker(tracker) {
                Column(Modifier.testTag("scrollContainer").fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Pushes the tracked element below the fold — taller than any plausible test
                    // window, so it starts outside the visible viewport rather than merely
                    // clipped by the scroll container.
                    Box(Modifier.size(5_000.dp))
                    // minDurationMs is deliberately far longer than performScrollTo's own scroll
                    // animation so the dwell timer can't be satisfied merely by the mainClock time
                    // that auto-advances while that animation settles.
                    Box(Modifier.testTag("target").size(10.dp).trackImpression("Item Viewed", minDurationMs = 5_000L))
                }
            }
        }
        waitForIdle()

        // Off-screen below the fold: must not have fired from initial composition alone.
        assertEquals(emptyList(), tracker.names, "must not fire while scrolled out of view")

        onNodeWithTag("target").performScrollTo()
        waitForIdle()
        assertEquals(emptyList(), tracker.names, "must not fire immediately upon scrolling into view, before minDurationMs elapses")

        mainClock.advanceTimeBy(5_000L)
        waitForIdle()
        assertEquals(listOf("Item Viewed"), tracker.names, "must fire once minDurationMs elapses after scrolling into view")
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

    /**
     * [trackImpression] must not suppress autocapture on the element carrying it (#158).
     *
     * It reports a visibility event and never a click, so suppressing left a tappable element that
     * also reported an impression firing **no** click event at all. Asserted through [resolveTapAt] —
     * the production hit test `ElementResolver.android.kt` runs — rather than through a tracker,
     * because the JVM `rememberElementResolver` resolves nothing (autocapture is Android/iOS only),
     * so an end-to-end assertion here would pass vacuously.
     */
    @Test
    fun trackImpressionDoesNotSuppressAutocaptureOnItsOwnElement() = runComposeUiTest {
        setContent {
            WithElementTracker(ElementRecordingTracker()) {
                Box(Modifier.testTag("row").size(48.dp).trackImpression("Card Viewed").clickable {})
            }
        }
        waitForIdle()

        assertEquals("row", unmergedRoot().resolveTapAt(centreOfNode("row"))?.identifier)
    }

    /**
     * The shape #158 was reported as: a `trackImpression` child exactly coincident with the clickable
     * enclosing it. On iOS that coincidence was indistinguishable from the element self-registering,
     * and the host's real tap vanished; here the marker is read off the ancestry, so this states the
     * behaviour both platforms now agree on.
     */
    @Test
    fun trackImpressionDoesNotSuppressAutocaptureOnTheClickableEnclosingIt() = runComposeUiTest {
        setContent {
            WithElementTracker(ElementRecordingTracker()) {
                Box(Modifier.testTag("host").size(48.dp).clickable {}) {
                    Box(Modifier.testTag("inner").fillMaxSize().trackImpression("Card Viewed"))
                }
            }
        }
        waitForIdle()

        assertEquals("host", unmergedRoot().resolveTapAt(centreOfNode("inner"))?.identifier)
    }

    /** The contrast: [trackClick] does fire a click, so autocapture must still stand down for it. */
    @Test
    fun trackClickStillSuppressesAutocaptureOnItsOwnElement() = runComposeUiTest {
        setContent {
            WithElementTracker(ElementRecordingTracker()) {
                Box(Modifier.testTag("row").size(48.dp).trackClick("Item Clicked") {})
            }
        }
        waitForIdle()

        assertNull(unmergedRoot().resolveTapAt(centreOfNode("row")))
    }
}
