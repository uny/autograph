package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AutocaptureRecordingTracker : Tracker {
    val tracked = mutableListOf<Pair<String, String?>>()
    val trackedProps = mutableListOf<JsonObject>()
    override fun track(name: String, properties: JsonObject, target: String?) {
        tracked += name to target
        trackedProps += properties
    }
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

private class ThrowingTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?): Unit = throw RuntimeException("boom")
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

class ReportTapIfResolvableTest {

    @Test
    fun reportsTheResolvedTarget() {
        val tracker = AutocaptureRecordingTracker()
        reportTapIfResolvable(tracker, ScreenHistory(), ScopeStack(), AutocaptureConfig(eventName = "Element Clicked")) { "share_button" }
        assertEquals(listOf<Pair<String, String?>>("Element Clicked" to "share_button"), tracker.tracked)
    }

    @Test
    fun doesNothingWhenResolveReturnsNull() {
        val tracker = AutocaptureRecordingTracker()
        reportTapIfResolvable(tracker, ScreenHistory(), ScopeStack(), AutocaptureConfig()) { null }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromResolveInsteadOfPropagatingIt() {
        val tracker = AutocaptureRecordingTracker()
        // Must not throw — a throwing resolve() must not kill the caller's while(true) loop.
        reportTapIfResolvable(tracker, ScreenHistory(), ScopeStack(), AutocaptureConfig()) { throw RuntimeException("boom") }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromTrackInsteadOfPropagatingIt() {
        // Must not throw — a throwing track() must not kill the caller's while(true) loop.
        reportTapIfResolvable(ThrowingTracker(), ScreenHistory(), ScopeStack(), AutocaptureConfig()) { "share_button" }
    }

    @Test
    fun attributesTheAmbientScopeScreenAndSectionFromTheStack() {
        val tracker = AutocaptureRecordingTracker()
        val stack = ScopeStack()
        stack.push(scope = JsonObject(mapOf("article_id" to JsonPrimitive("42"))))
        stack.push(screen = "Article", section = "Body")
        reportTapIfResolvable(tracker, ScreenHistory(), stack, AutocaptureConfig()) { "like_button" }

        val props = tracker.trackedProps.single()
        // The scope this tap happened under — the pre-existing blind spot where autocapture, sitting
        // above the AutographScope decorator, attributed no scope at all.
        assertEquals("42", props["article_id"]?.jsonPrimitive?.content)
        assertEquals("Article", props["screen"]?.jsonPrimitive?.content)
        assertEquals("Body", props["section"]?.jsonPrimitive?.content)
    }

    @Test
    fun fallsBackToTheLastViewedScreenWhenNoScreenFrameIsPushed() {
        val tracker = AutocaptureRecordingTracker()
        // A bare TrackScreenView updates history but pushes no ambient frame.
        val history = ScreenHistory().apply { lastScreen = "Feed" }
        reportTapIfResolvable(tracker, history, ScopeStack(), AutocaptureConfig()) { "row" }

        val props = tracker.trackedProps.single()
        assertEquals("Feed", props["screen"]?.jsonPrimitive?.content)
        assertNull(props["section"])
    }

    @Test
    fun theAmbientScreenFrameWinsOverTheHistoryFallback() {
        val tracker = AutocaptureRecordingTracker()
        val history = ScreenHistory().apply { lastScreen = "Feed" }
        val stack = ScopeStack().apply { push(screen = "Article") }
        reportTapIfResolvable(tracker, history, stack, AutocaptureConfig()) { "x" }

        assertEquals("Article", tracker.trackedProps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun keepsAnAmbientSectionEvenWhenNoScreenResolves() {
        val tracker = AutocaptureRecordingTracker()
        // A section-only frame with no screen anywhere (no ambient screen, empty history) — a shape
        // ScopeStack supports and native push sites can produce. The capture path defers precedence
        // to AmbientContext.enrich, which writes screen and section independently, so the section
        // must survive; hand-rolling the precedence here used to drop it.
        val stack = ScopeStack().apply { push(section = "Header") }
        reportTapIfResolvable(tracker, ScreenHistory(), stack, AutocaptureConfig()) { "x" }

        val props = tracker.trackedProps.single()
        assertEquals("Header", props["section"]?.jsonPrimitive?.content)
        assertNull(props["screen"])
    }
}

/**
 * The platform resolver has a real implementation on Android and iOS (see [ElementResolver.android.kt]
 * / [ElementResolver.ios.kt]); on the JVM target — and wherever [PlatformAutocaptureTestHost] can't
 * supply what the platform resolver needs — these tests exercise the AutographProvider(autocapture=)
 * wiring itself — composition, layout, and that clicks still reach child composables — not target
 * resolution.
 */
@OptIn(ExperimentalTestApi::class)
class AutocaptureObserverTest {

    @Test
    fun autographProviderWithAutocaptureStillDeliversClicksToChildren() = runComposeUiTest {
        val tracker = AutocaptureRecordingTracker()
        var clicked = false
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(tracker, autocapture = AutocaptureConfig()) {
                    Box(
                        Modifier
                            .testTag("target")
                            .size(10.dp)
                            .clickable { clicked = true },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        assertTrue(clicked, "a plain clickable under an autocapture-enabled AutographProvider must still receive clicks")
    }

    @Test
    fun autographProviderWithAutocaptureStillFiresExplicitTrackClick() = runComposeUiTest {
        val tracker = AutocaptureRecordingTracker()
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(tracker, autocapture = AutocaptureConfig()) {
                    Box(Modifier.testTag("target").size(10.dp).trackClick("Item Clicked", target = "share_button") {})
                }
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        assertEquals(listOf<Pair<String, String?>>("Item Clicked" to "share_button"), tracker.tracked)
    }
}
