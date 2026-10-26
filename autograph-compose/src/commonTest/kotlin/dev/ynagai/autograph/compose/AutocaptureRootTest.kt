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
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [autographAutocaptureRoot] where it is not supported (the JVM and iOS targets this source set runs
 * on): a no-op that warns once and leaves the content's own clicks alone. Its Android behaviour is in
 * `sample-android`'s `AutocaptureRootTest`, which can host a real `Dialog`.
 */
@OptIn(ExperimentalTestApi::class)
class AutocaptureRootTest {

    @AfterTest
    fun reArm() {
        autocaptureRootWarned.clear()
    }

    @Test
    fun whereItIsNotSupportedItWarnsOnceAndLeavesClicksAlone() = runComposeUiTest {
        val tracked = mutableListOf<String?>()
        val tracker = object : Tracker {
            override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
                tracked += target
            }
            override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
            override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
        }
        var clicked = false
        autocaptureRootWarned.clear()
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(tracker, autocapture = Autocapture()) {
                    Box(Modifier.autographAutocaptureRoot().testTag("target").size(10.dp).clickable { clicked = true })
                }
            }
        }
        waitForIdle()

        onNodeWithTag("target").performClick()
        waitForIdle()

        assertTrue(clicked)
        assertEquals(setOf(Misuse.Unsupported), autocaptureRootWarned)
        // No second observer: whatever the provider's own observer reports, it reports once.
        assertTrue(tracked.size <= 1, "tracked: $tracked")
    }

    @Test
    fun theProviderOnlyOffersARootWhenAutocaptureIsOn() = runComposeUiTest {
        var withoutAutocapture: Boolean? = null
        var withAutocapture: Boolean? = null
        val tracker = object : Tracker {
            override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
            override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
            override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
        }
        setContent {
            AutographProvider(tracker, autocapture = null) { withoutAutocapture = LocalAutocaptureRoot.current != null }
            PlatformAutocaptureTestHost {
                AutographProvider(tracker, autocapture = Autocapture()) { withAutocapture = LocalAutocaptureRoot.current != null }
            }
        }
        waitForIdle()

        assertEquals(false, withoutAutocapture)
        assertEquals(true, withAutocapture)
    }
}
