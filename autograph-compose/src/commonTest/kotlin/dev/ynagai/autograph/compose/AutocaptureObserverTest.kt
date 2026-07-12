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

/**
 * The platform resolver only has a real implementation on Android (see [ElementResolver.android.kt]);
 * on the JVM target these tests exercise the AutographProvider(autocapture=) wiring itself —
 * composition, layout, and that clicks still reach child composables — not target resolution.
 */
@OptIn(ExperimentalTestApi::class)
class AutocaptureObserverTest {

    @Test
    fun autographProviderWithAutocaptureStillDeliversClicksToChildren() = runComposeUiTest {
        val tracker = AutocaptureRecordingTracker()
        var clicked = false
        setContent {
            AutographProvider(tracker, autocapture = AutocaptureConfig()) {
                Box(
                    Modifier
                        .testTag("target")
                        .size(10.dp)
                        .clickable { clicked = true },
                )
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
            AutographProvider(tracker, autocapture = AutocaptureConfig()) {
                Box(Modifier.testTag("target").size(10.dp).trackClick("Item Clicked", target = "share_button") {})
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        assertEquals(listOf<Pair<String, String?>>("Item Clicked" to "share_button"), tracker.tracked)
    }
}
