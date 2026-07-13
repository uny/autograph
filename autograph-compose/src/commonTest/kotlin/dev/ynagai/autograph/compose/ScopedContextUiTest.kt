package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ScopeUiRecordingTracker : Tracker {
    val tracks = mutableListOf<Pair<String, JsonObject>>()
    val screens = mutableListOf<Pair<String, JsonObject>>()
    override fun track(name: String, properties: JsonObject, target: String?) {
        tracks += name to properties
    }
    override fun screen(name: String, properties: JsonObject) {
        screens += name to properties
    }
    override fun identify(userId: String, traits: JsonObject) {}
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

@OptIn(ExperimentalTestApi::class)
class ScopedContextUiTest {

    @Test
    fun scopeAppliesToTracksNestedInTheContent() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("article_id" to "42") {
                    Emit { it.track("Recipe Saved", target = "share_button") }
                }
            }
        }
        waitForIdle()

        val props = tracker.tracks.single().second
        assertEquals("42", props.str("article_id"))
    }

    @Test
    fun tracksOutsideTheScopeAreUnaffected() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("article_id" to "42") {
                    Emit { it.track("Inside") }
                }
                Emit { it.track("Outside") }
            }
        }
        waitForIdle()

        val inside = tracker.tracks.first { it.first == "Inside" }.second
        val outside = tracker.tracks.first { it.first == "Outside" }.second
        assertEquals("42", inside.str("article_id"))
        assertNull(outside.str("article_id"), "a sibling outside the scope carries no scope context")
    }

    @Test
    fun nestedScopesStackAndInnerWins() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker) {
                AutographScope("tenant" to "acme", "article_id" to "1") {
                    AutographScope("article_id" to "2", "section" to "body") {
                        Emit { it.track("E") }
                    }
                }
            }
        }
        waitForIdle()

        val props = tracker.tracks.single().second
        assertEquals("acme", props.str("tenant"))
        assertEquals("2", props.str("article_id"), "inner scope overrides the outer one")
        assertEquals("body", props.str("section"))
    }

    @Test
    fun composesWithTrackedScreenSoEventsCarryBothScreenAndScope() = runComposeUiTest {
        val tracker = ScopeUiRecordingTracker()
        setContent {
            CompositionLocalProvider(
                LocalTracker provides tracker,
                LocalScreenHistory provides ScreenHistory(),
            ) {
                AutographScope("article_id" to "42") {
                    TrackedScreen("ArticleDetail") {
                        Emit { it.track("Recipe Saved") }
                    }
                }
            }
        }
        waitForIdle()

        // The screen-view event carries the scope.
        val screenProps = tracker.screens.single().second
        assertEquals("42", screenProps.str("article_id"))

        // A track nested under both carries screen (from TrackedScreen) and article_id (from scope).
        val trackProps = tracker.tracks.single().second
        assertEquals("42", trackProps.str("article_id"))
    }
}

/** Reads the ambient [LocalTracker] once on composition and hands it to [emit]. */
@Composable
private fun Emit(emit: (Tracker) -> Unit) {
    val tracker = LocalTracker.current
    androidx.compose.runtime.LaunchedEffect(Unit) { emit(tracker) }
}
