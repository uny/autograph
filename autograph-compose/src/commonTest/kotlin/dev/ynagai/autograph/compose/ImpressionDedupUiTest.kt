@file:OptIn(dev.ynagai.autograph.AutographInternalApi::class)

package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.EventIdGenerator
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records each impression as (name, target, screen_view_id), and its fraction threshold alongside. */
private class ImpressionRecordingTracker : Tracker {
    val tracked = mutableListOf<Triple<String, String?, String?>>()
    val fractions = mutableListOf<String?>()
    val names: List<String> get() = tracked.map { it.first }
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        val metadata = properties[RESERVED_METADATA_KEY]?.let { Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject }
        val visit = metadata?.get("screen_view_id")?.jsonPrimitive?.content
        tracked += Triple(name, target ?: properties["target"]?.jsonPrimitive?.content, visit)
        fractions += metadata?.get("impression")?.jsonObject?.get("min_fraction_visible")?.jsonPrimitive?.content
    }
    override fun screen(name: String, properties: Map<String, JsonElement>) {}
    override fun identify(userId: String, traits: Map<String, JsonElement>) {}
}

private fun sequentialStack(): ScopeStack {
    var next = 0
    return ScopeStack().apply { screenViewIdGenerator = EventIdGenerator { "id-${++next}" } }
}

@Composable
private fun WithImpressionTracker(tracker: Tracker, stack: ScopeStack = sequentialStack(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack, content = content)
}

/** Lets visibility settle past [ms] of dwell. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.dwell(ms: Long = 10L) {
    waitForIdle()
    mainClock.advanceTimeBy(ms)
    waitForIdle()
}

/**
 * The keyed `trackImpression` (#243): once per key per unit — a [TrackedScreen] visit or an
 * [ImpressionScope] — however often the element is disposed and re-composed.
 */
@OptIn(ExperimentalTestApi::class)
class ImpressionDedupUiTest {

    /**
     * A 100-item list with item 0 at the top. Each item counts its own disposals into [disposed], so a
     * test can prove the item really left the composition rather than merely the viewport.
     */
    @Composable
    private fun ItemList(state: LazyListState, keyed: Boolean, disposed: MutableList<Int>) {
        LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
            items(count = 100, key = { it }) { index ->
                DisposableEffect(index) { onDispose { disposed += index } }
                val modifier = if (keyed) {
                    Modifier.trackImpression("Item Viewed", key = index, target = "item-$index", minDurationMs = 0L)
                } else {
                    Modifier.trackImpression("Item Viewed", target = "item-$index", minDurationMs = 0L)
                }
                Box(Modifier.size(100.dp).then(modifier))
            }
        }
    }

    private fun ComposeUiTest.scrollAwayAndBack() {
        onNodeWithTag("list").performScrollToIndex(90)
        dwell()
        onNodeWithTag("list").performScrollToIndex(0)
        dwell()
    }

    @Test
    fun aKeyedItemScrolledOutAndBackReportsOnceInAnImpressionScope() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        val disposed = mutableListOf<Int>()
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            WithImpressionTracker(tracker) {
                ImpressionScope { ItemList(state, keyed = true, disposed) }
            }
        }
        dwell()
        val firstRound = tracker.tracked.size
        assertTrue(firstRound > 0, "the items on screen must report")

        scrollAwayAndBack()

        assertTrue(0 in disposed, "item 0 must have left the composition, or this proves nothing")
        assertEquals(1, tracker.tracked.count { it.second == "item-0" }, "item 0 re-composed without a report")
        assertEquals(tracker.tracked.size, tracker.tracked.toSet().size, "no item may report twice: ${tracker.tracked}")
    }

    /** The control: without a key, the same scroll re-reports — the bug #243 describes. */
    @Test
    fun anUnkeyedItemScrolledOutAndBackReportsAgain() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        val disposed = mutableListOf<Int>()
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            WithImpressionTracker(tracker) {
                ImpressionScope { ItemList(state, keyed = false, disposed) }
            }
        }
        dwell()
        scrollAwayAndBack()

        assertTrue(0 in disposed)
        assertEquals(2, tracker.tracked.count { it.second == "item-0" }, "the overload without a key is unchanged")
    }

    @Test
    fun aTrackedScreenIsAUnitAndItsImpressionsCarryTheVisit() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var shown by mutableStateOf(true)
        setContent {
            WithImpressionTracker(tracker) {
                TrackedScreen("Feed") {
                    if (shown) Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = "card-1", minDurationMs = 0L))
                }
            }
        }
        dwell()
        shown = false
        dwell()
        shown = true
        dwell()

        assertEquals(listOf(Triple<String, String?, String?>("Card Viewed", null, "id-1")), tracker.tracked)
    }

    @Test
    fun aRenamedScreenReportsAgainOnlyAfterAFreshDwell() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var screen by mutableStateOf("A")
        setContent {
            WithImpressionTracker(tracker) {
                TrackedScreen(screen) {
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = "card-1", minDurationMs = 100L))
                }
            }
        }
        dwell(150L)
        assertEquals(listOf("id-1"), tracker.tracked.map { it.third })

        // The element never leaves the viewport, so only a restarted measurement can report again.
        screen = "B"
        dwell(50L)
        assertEquals(1, tracker.tracked.size, "the dwell before the rename must not count toward the new visit")
        dwell(100L)
        assertEquals(listOf("id-1", "id-2"), tracker.tracked.map { it.third })
    }

    @Test
    fun aChangeOfSectionKeepsTheVisitAndDoesNotReportAgain() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var section by mutableStateOf("For You")
        setContent {
            WithImpressionTracker(tracker) {
                TrackedScreen("Home", section = section) {
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = "card-1", minDurationMs = 0L))
                }
            }
        }
        dwell()
        section = "Following"
        dwell(1_000L)

        assertEquals(1, tracker.tracked.size)
    }

    @Test
    fun anImpressionIsItsNameThresholdsEffectiveTargetAndKey() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        setContent {
            WithImpressionTracker(tracker) {
                ImpressionScope {
                    // Same item, six definitions: each reports.
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L))
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L, minFractionVisible = 0.9f))
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L, minFractionVisible = 0f))
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L, target = "carousel"))
                    Box(Modifier.size(10.dp).trackImpression("Promo Viewed", key = 1, minDurationMs = 0L))
                    AutographScope("target" to "rail") {
                        Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L))
                    }
                    // The same definitions again, spelled differently: none reports.
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L, minFractionVisible = -0f))
                    Box(
                        Modifier.size(10.dp).trackImpression(
                            "Card Viewed",
                            key = 1,
                            properties = JsonObject(mapOf("target" to JsonPrimitive("carousel"))),
                            minDurationMs = 0L,
                        ),
                    )
                    AutographScope("target" to "carousel") {
                        Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = 1, minDurationMs = 0L))
                    }
                }
            }
        }
        dwell()

        assertEquals(
            setOf(
                "Card Viewed/null/0.5",
                "Card Viewed/null/0.9",
                "Card Viewed/null/0.0",
                "Card Viewed/carousel/0.5",
                "Promo Viewed/null/0.5",
                "Card Viewed/rail/0.5",
            ),
            // Whichever of 0f and -0f reports first names the threshold; the count below says only one did.
            tracker.tracked.zip(tracker.fractions) { (name, target, _), fraction -> "$name/$target/${fraction?.removePrefix("-")}" }.toSet(),
        )
        assertEquals(6, tracker.tracked.size, "${tracker.tracked}")
    }

    @Test
    fun aKeyChangeOnTheSameElementRestartsTheMeasurementAndReportsTheNewKey() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var key by mutableStateOf("a")
        setContent {
            WithImpressionTracker(tracker) {
                ImpressionScope {
                    Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = key, target = key, minDurationMs = 100L))
                }
            }
        }
        dwell(150L)
        key = "b"
        dwell(50L)
        assertEquals(listOf("a"), tracker.tracked.map { it.second }, "a's dwell must not count toward b")
        dwell(100L)
        assertEquals(listOf("a", "b"), tracker.tracked.map { it.second })
    }

    /** Outside any unit: per composable instance, as without a key. */
    @Test
    fun withoutAUnitAKeyedImpressionFallsBackToOncePerInstance() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var shown by mutableStateOf(true)
        setContent {
            WithImpressionTracker(tracker) {
                if (shown) Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = "card-1", minDurationMs = 0L))
            }
        }
        dwell()
        dwell(1_000L)
        assertEquals(1, tracker.tracked.size, "once per instance, not once per transition")
        shown = false
        dwell()
        shown = true
        dwell()

        assertEquals(2, tracker.tracked.size)
    }

    /** Inside a TrackedScreen, an ImpressionScope starts over with the visit: never one item across two visits. */
    @Test
    fun anImpressionScopeInsideATrackedScreenStartsOverWhenTheVisitDoes() = runComposeUiTest {
        val tracker = ImpressionRecordingTracker()
        var screen by mutableStateOf("A")
        var shown by mutableStateOf(true)
        setContent {
            WithImpressionTracker(tracker) {
                TrackedScreen(screen) {
                    ImpressionScope {
                        if (shown) Box(Modifier.size(10.dp).trackImpression("Card Viewed", key = "card-1", minDurationMs = 0L))
                    }
                }
            }
        }
        dwell()
        shown = false
        dwell()
        shown = true
        dwell()
        assertEquals(listOf("id-1"), tracker.tracked.map { it.third }, "the scope de-duplicates within the visit")

        screen = "B"
        dwell()
        assertEquals(listOf("id-1", "id-2"), tracker.tracked.map { it.third })
    }

    @Test
    fun aScreenVisitStartsItsImpressionsOverOnlyOnARename() {
        val visit = ScreenVisit()
        val card = ImpressionKey("Card Viewed", 0L, 0.5f, null, "card-1")
        // Reported between composition and the visit's first begin: still this visit.
        visit.impressions.record(card)
        visit.begin("A", id = null)
        assertTrue(card in visit.impressions, "the first begin must not forget what this visit already reported")
        assertEquals(0, visit.impressions.generation)

        visit.begin("B", id = null)
        assertFalse(card in visit.impressions, "a rename is a new visit")
        assertEquals(1, visit.impressions.generation)
    }
}
