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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class AutocaptureRecordingTracker : Tracker {
    val tracked = mutableListOf<Pair<String, String?>>()
    override fun track(name: String, properties: JsonObject, target: String?) {
        tracked += name to target
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
        reportTapIfResolvable(tracker, ScreenHistory(), AutocaptureConfig(eventName = "Element Clicked")) { "share_button" }
        assertEquals(listOf<Pair<String, String?>>("Element Clicked" to "share_button"), tracker.tracked)
    }

    @Test
    fun doesNothingWhenResolveReturnsNull() {
        val tracker = AutocaptureRecordingTracker()
        reportTapIfResolvable(tracker, ScreenHistory(), AutocaptureConfig()) { null }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromResolveInsteadOfPropagatingIt() {
        val tracker = AutocaptureRecordingTracker()
        // Must not throw — a throwing resolve() must not kill the caller's while(true) loop.
        reportTapIfResolvable(tracker, ScreenHistory(), AutocaptureConfig()) { throw RuntimeException("boom") }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromTrackInsteadOfPropagatingIt() {
        // Must not throw — a throwing track() must not kill the caller's while(true) loop.
        reportTapIfResolvable(ThrowingTracker(), ScreenHistory(), AutocaptureConfig()) { "share_button" }
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
