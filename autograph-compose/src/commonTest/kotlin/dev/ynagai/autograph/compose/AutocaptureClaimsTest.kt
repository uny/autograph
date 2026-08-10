package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generation half of [AutocaptureClaims] — the execution evidence iOS resolves against.
 *
 * These are the tests the design's safety argument rests on. "Every failure falls toward a duplicate,
 * never toward a dropped event" is only true while a mark cannot outlive the dispatch that produced
 * it, so each way it could — an activation outside a dispatch, a stale token closing a live
 * generation, evidence surviving a close — is pinned here rather than argued in prose.
 */
class AutocaptureClaimsGenerationTest {

    @Test
    fun marksMadeWhileAGenerationIsOpenAreVisible() {
        val claims = AutocaptureClaims()
        claims.openTapGeneration()

        claims.markInstrumentedClickExecuted()

        assertTrue(claims.instrumentedClickExecutedThisGeneration())
    }

    /**
     * The property that makes VoiceOver double-taps, `Enter` activations, and dialog/popup routes
     * harmless: each invokes [trackClick]'s handler without any pointer dispatch reaching the
     * observer, so the mark has nowhere to land and cannot suppress the next real tap.
     */
    @Test
    fun aMarkOutsideAnyGenerationIsANoOp() {
        val claims = AutocaptureClaims()

        claims.markInstrumentedClickExecuted()

        assertFalse(claims.instrumentedClickExecutedThisGeneration())
        claims.openTapGeneration()
        assertFalse(claims.instrumentedClickExecutedThisGeneration(), "a mark from before the generation must not leak into it")
    }

    @Test
    fun openingAGenerationDiscardsThePreviousOnesEvidence() {
        val claims = AutocaptureClaims()
        val first = claims.openTapGeneration()
        claims.markInstrumentedClickExecuted()

        claims.openTapGeneration()

        assertFalse(claims.instrumentedClickExecutedThisGeneration())
        claims.closeTapGeneration(first)
    }

    @Test
    fun closingAGenerationDiscardsItsEvidence() {
        val claims = AutocaptureClaims()
        val token = claims.openTapGeneration()
        claims.markInstrumentedClickExecuted()

        claims.closeTapGeneration(token)

        assertFalse(claims.instrumentedClickExecutedThisGeneration())
        claims.markInstrumentedClickExecuted()
        assertFalse(claims.instrumentedClickExecutedThisGeneration(), "the generation must be closed, not merely emptied")
    }

    /**
     * The reason [AutocaptureClaims.closeTapGeneration] takes a token at all.
     *
     * The observer's `pointerInput` coroutine restarts whenever its keys change. If the cancelled
     * coroutine's `finally` could close the generation its replacement had just opened, the mark that
     * followed would land nowhere and the tap would be reported twice — or worse, a later mark would
     * be attributed to the wrong dispatch. A stale token has to be inert.
     */
    @Test
    fun aStaleTokenCannotCloseTheCurrentGeneration() {
        val claims = AutocaptureClaims()
        val stale = claims.openTapGeneration()
        claims.openTapGeneration()

        claims.closeTapGeneration(stale)

        claims.markInstrumentedClickExecuted()
        assertTrue(claims.instrumentedClickExecutedThisGeneration(), "the live generation must survive a stale close")
    }

    /**
     * [AutocaptureClaims.clearTapExecution] drops the evidence without closing the generation — what
     * the observer does when it cannot attribute a mark to the pointer it is about to report.
     */
    @Test
    fun clearingExecutionLeavesTheGenerationOpen() {
        val claims = AutocaptureClaims()
        claims.openTapGeneration()
        claims.markInstrumentedClickExecuted()

        claims.clearTapExecution()

        assertFalse(claims.instrumentedClickExecutedThisGeneration())
        claims.markInstrumentedClickExecuted()
        assertTrue(claims.instrumentedClickExecutedThisGeneration(), "the generation must still be open")
    }

    @Test
    fun ignoredBoundsAreIndependentOfTheGeneration() {
        val claims = AutocaptureClaims()
        val key = Any()
        val bounds = Rect(0f, 0f, 10f, 10f)
        claims.ignored[key] = bounds

        val token = claims.openTapGeneration()
        claims.markInstrumentedClickExecuted()
        claims.closeTapGeneration(token)

        assertEquals(bounds, claims.ignored[key], "autographIgnore has no execution to observe and must not ride on the generation")
    }
}

private class NoopTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) {}
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

/** Runs [onTrack] from inside `track`, so a test can observe state as the tracker sees it. */
private class ProbingTracker(private val onTrack: () -> Unit) : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) = onTrack()
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

/**
 * The composition-lifecycle wiring [AutocaptureClaimsGenerationTest] can't reach: that
 * [autographIgnore] really registers and removes its bounds through
 * [androidx.compose.runtime.DisposableEffect], and that [trackClick] really marks its execution and
 * really still sets its semantics key.
 *
 * The [trackClick] cases matter more than they look. Its two markers now live in different worlds —
 * a semantics key that Android reads off the ancestry, and an execution mark that iOS reads off the
 * dispatch — and neither platform's tests exercise the other's. A regression that silently dropped
 * one would leave one platform's suppression working and the other's gone.
 */
@OptIn(ExperimentalTestApi::class)
class AutocaptureClaimDisposalTest {

    @Test
    fun leavingCompositionRemovesTheElementsIgnoredBounds() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    if (visible) {
                        Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(claims?.ignored?.isNotEmpty() == true, "expected the bounds to be registered while composed")

        visible = false
        waitForIdle()

        assertTrue(claims?.ignored?.isEmpty() == true, "expected the bounds to be removed once its composable left the composition")
    }

    /**
     * [autographIgnore] is `composed { semantics{...}.registerIgnoredBounds() }` — the test above only
     * checks the [AutocaptureClaims] map (iOS's path), never that [AutographIgnoredKey] still reaches
     * the [androidx.compose.ui.semantics.SemanticsConfiguration] through the real modifier (Android's
     * path, via `config.isAutocaptureIgnored()`) after that wrapping.
     */
    @Test
    fun autographIgnoreStillSetsTheSemanticsKey() = runComposeUiTest {
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                }
            }
        }
        waitForIdle()

        val config = onNodeWithTag("ignored").fetchSemanticsNode().config
        assertTrue(config.isAutocaptureIgnored(), "expected AutographIgnoredKey to still reach the SemanticsConfiguration")
    }

    /**
     * [trackClick]'s execution mark, through the real modifier and a real pointer dispatch.
     *
     * The flag is read from *inside* the handlers rather than after the click, because by then the
     * observer has already closed the generation — that is the design working, not a failure. Reading
     * it in flight pins three things at once, and they are exactly the three the iOS suppression rests
     * on:
     *
     * - **A generation is open while the handler runs.** [autocaptureTaps] opens it on the `Initial`
     *   pass, `clickable` invokes the handler on `Main`, and the observer resolves on `Final`. If that
     *   ordering ever stopped holding the mark would land outside any generation and be discarded —
     *   costing a duplicate, never an event, but this is where it would be noticed.
     * - **The mark follows `tracker.track`.** So a throwing tracker leaves no mark and autocapture
     *   still reports the tap: no explicit event was recorded, and suppressing then would lose the
     *   click rather than merely duplicate it.
     * - **The mark precedes the caller's `onClick`.**
     *
     * Raw touch injection, not `performClick`: the latter can invoke the semantics action directly,
     * which never produces the pointer passes this is about.
     */
    @Test
    fun trackClickMarksItsExecutionBetweenTrackAndOnClick() = runComposeUiTest {
        var markedDuringTrack: Boolean? = null
        var markedDuringOnClick: Boolean? = null
        lateinit var claims: AutocaptureClaims
        val tracker = ProbingTracker { markedDuringTrack = claims.instrumentedClickExecutedThisGeneration() }
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(tracker, autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current!!
                    Box(
                        Modifier.testTag("tracked").size(40.dp).trackClick("Item Clicked") {
                            markedDuringOnClick = claims.instrumentedClickExecutedThisGeneration()
                        },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("tracked").performTouchInput { down(center); up() }
        waitForIdle()

        assertEquals(false, markedDuringTrack, "the mark must follow tracker.track, so a failed track leaves none")
        assertEquals(
            true,
            markedDuringOnClick,
            "expected trackClick to have marked its execution into a generation the observer had open",
        )
    }

    /**
     * [trackImpression] neither registers bounds nor marks an execution.
     *
     * It reports a visibility event and never a click, so there is nothing for autocapture to
     * double-report and nothing to suppress. It used to register an instrumented claim, and on iOS
     * geometry could not tell that claim apart from the clickable enclosing it: a coincident
     * impression element silently dropped its host's real tap (#158).
     */
    @Test
    fun trackImpressionRegistersNothing() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.testTag("tracked").size(10.dp).trackImpression("Card Viewed"))
                }
            }
        }
        waitForIdle()
        val live = claims
        assertTrue(live != null)
        live.openTapGeneration()
        waitForIdle()

        assertTrue(live.ignored.isEmpty(), "trackImpression must register no ignored bounds (#158)")
        assertFalse(live.instrumentedClickExecutedThisGeneration(), "trackImpression must not mark an execution (#158)")
    }

    /**
     * Asserts [registerIgnoredBounds] stores the element's actual `boundsInWindow()`.
     * **Known harness limitation** (confirmed empirically): `runComposeUiTest`'s root composition IS
     * its own window, so `boundsInRoot()` and `boundsInWindow()` are numerically identical here even
     * with this test's padding offset — this assertion alone can't discriminate the two coordinate
     * spaces, and would pass unchanged if [registerIgnoredBounds] reverted to `boundsInRoot()`.
     * Real regression protection for that would need an on-device/instrumented harness where the
     * composition root is offset from the platform window (safe-area insets, nested embedding) —
     * same class of limitation as this file's other iOS on-device-only findings. Kept as coverage
     * that the stored bounds are real, positioned geometry (not a stale/zero rect), not as a
     * discriminating regression guard.
     */
    @Test
    fun registerIgnoredBoundsStoresTheElementsActualBoundsInWindow() = runComposeUiTest {
        var claims: AutocaptureClaims? = null
        var actualBoundsInWindow: Rect? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.padding(start = 40.dp, top = 60.dp)) {
                        Box(
                            Modifier.testTag("ignored")
                                .size(10.dp)
                                .onGloballyPositioned { actualBoundsInWindow = it.boundsInWindow() }
                                .autographIgnore(),
                        )
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(actualBoundsInWindow, claims?.ignored?.values?.singleOrNull())
    }

    /**
     * A still-composed element that moves/resizes must overwrite its existing entry, not accumulate a
     * second one — [registerIgnoredBounds] keys by a stable per-call-site identity specifically so
     * relayout re-puts under the same key.
     */
    @Test
    fun registerIgnoredBoundsOverwritesOnRepositionRatherThanAccumulating() = runComposeUiTest {
        var offset by mutableStateOf(0.dp)
        var claims: AutocaptureClaims? = null
        setContent {
            PlatformAutocaptureTestHost {
                AutographProvider(NoopTracker(), autocapture = AutocaptureConfig()) {
                    claims = LocalAutocaptureClaims.current
                    Box(Modifier.padding(start = offset)) {
                        Box(Modifier.testTag("ignored").size(10.dp).autographIgnore())
                    }
                }
            }
        }
        waitForIdle()
        val boundsBeforeMove = claims?.ignored?.values?.singleOrNull()
        assertTrue(boundsBeforeMove != null, "expected exactly one ignored entry before moving")

        offset = 40.dp
        waitForIdle()

        assertEquals(1, claims?.ignored?.size, "expected the entry to be overwritten in place, not accumulated")
        val boundsAfterMove = claims?.ignored?.values?.singleOrNull()
        assertTrue(boundsAfterMove != boundsBeforeMove, "expected the stored bounds to reflect the new position")
    }
}
