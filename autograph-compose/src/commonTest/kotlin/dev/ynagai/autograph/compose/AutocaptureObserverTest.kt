package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
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
import kotlin.test.assertFalse
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

/**
 * The two guards [autocaptureTaps] applies before it trusts a [trackClick] execution mark.
 *
 * Extracted from the pointer loop so they can be stated against constructed events: the behaviour
 * they protect against — a second finger on the screen, a `Final` that belongs to another dispatch —
 * is not reachable through the test harness's touch injection, and it is exactly the behaviour a
 * later refactor is most likely to get subtly wrong.
 */
class ExecutionEvidenceAttributionTest {

    private fun change(id: Long, consumed: Boolean): PointerInputChange = PointerInputChange(
        id = PointerId(id),
        uptimeMillis = 10L,
        position = Offset(1f, 1f),
        pressed = false,
        previousUptimeMillis = 0L,
        previousPosition = Offset(1f, 1f),
        previousPressed = true,
        isInitiallyConsumed = consumed,
    )

    @Test
    fun trustsASingleConsumedChangeInTheSameDispatch() {
        val event = PointerEvent(listOf(change(1, consumed = true)))

        assertTrue(executionEvidenceIsAttributable(event, event))
    }

    /**
     * The discriminating case for counting `isConsumed` instead of `changes.size`.
     *
     * A [PointerEvent] carries every active pointer, so a finger resting on the screen while another
     * taps rides along unconsumed. Keying the guard on `changes.size` would discard the mark here and
     * double-report every tap made with a second finger down — allowed by the failure contract, but a
     * regression against the behaviour before this mechanism, and avoidable.
     */
    @Test
    fun trustsATapMadeWhileAnotherFingerRestsOnTheScreen() {
        val event = PointerEvent(listOf(change(1, consumed = true), change(2, consumed = false)))

        assertTrue(executionEvidenceIsAttributable(event, event))
    }

    /**
     * Two consumed releases in one dispatch: the mark could belong to either, and suppressing the
     * wrong one loses that element's tap outright. This is the multi-touch shape that made a
     * geometry-gated design unsafe, and the reason the answer here is "discard", not "guess".
     */
    @Test
    fun refusesTwoConsumedChangesInOneDispatch() {
        val event = PointerEvent(listOf(change(1, consumed = true), change(2, consumed = true)))

        assertFalse(executionEvidenceIsAttributable(event, event))
    }

    /**
     * A `Final` that is not the `Initial`'s own event — what an abnormal dispatch (a Main-pass
     * handler throwing past the remaining passes, say) would leave the loop holding. The marks in the
     * open generation then describe some other tap.
     */
    @Test
    fun refusesAFinalFromADifferentDispatch() {
        val initial = PointerEvent(listOf(change(1, consumed = true)))
        val final = PointerEvent(listOf(change(1, consumed = true)))

        assertFalse(executionEvidenceIsAttributable(initial, final))
    }

    @Test
    fun trustsADispatchWhereNothingWasConsumed() {
        // Nothing to misattribute. The loop drops such an event before resolving anyway; the guard
        // simply must not treat "no consumption" as ambiguity.
        val event = PointerEvent(listOf(change(1, consumed = false)))

        assertTrue(executionEvidenceIsAttributable(event, event))
    }
}

class ReportTapIfResolvableTest {

    @Test
    fun reportsTheResolvedTargetUnderTheDefaultEventName() {
        val tracker = AutocaptureRecordingTracker()
        // Deliberately the no-arg config: this pins the default all the way through to the tracked
        // event, so re-hardcoding a literal here instead of the shared constant fails the build.
        // Pinning the constant alone (AutocaptureDefaultsTest) leaves that wiring untested.
        reportTapIfResolvable(tracker, ScopeStack(), AutocaptureConfig()) { AutocaptureTarget("share_button") }
        assertEquals(listOf<Pair<String, String?>>("Element Clicked" to "share_button"), tracker.tracked)
    }

    @Test
    fun doesNothingWhenResolveReturnsNull() {
        val tracker = AutocaptureRecordingTracker()
        reportTapIfResolvable(tracker, ScopeStack(), AutocaptureConfig()) { null }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromResolveInsteadOfPropagatingIt() {
        val tracker = AutocaptureRecordingTracker()
        // Must not throw — a throwing resolve() must not kill the caller's while(true) loop.
        reportTapIfResolvable(tracker, ScopeStack(), AutocaptureConfig()) { throw RuntimeException("boom") }
        assertTrue(tracker.tracked.isEmpty())
    }

    @Test
    fun swallowsAnExceptionFromTrackInsteadOfPropagatingIt() {
        // Must not throw — a throwing track() must not kill the caller's while(true) loop.
        reportTapIfResolvable(ThrowingTracker(), ScopeStack(), AutocaptureConfig()) { AutocaptureTarget("share_button") }
    }

    @Test
    fun attributesTheAmbientScopeScreenAndSectionFromTheStack() {
        val tracker = AutocaptureRecordingTracker()
        val stack = ScopeStack()
        stack.push(scope = JsonObject(mapOf("article_id" to JsonPrimitive("42"))))
        stack.push(screen = "Article", section = "Body")
        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) { AutocaptureTarget("like_button") }

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
        val stack = ScopeStack().apply { screenHistory.record("Feed") }
        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) { AutocaptureTarget("row") }

        val props = tracker.trackedProps.single()
        assertEquals("Feed", props["screen"]?.jsonPrimitive?.content)
        assertNull(props["section"])
    }

    @Test
    fun theAmbientScreenFrameWinsOverTheHistoryFallback() {
        val tracker = AutocaptureRecordingTracker()
        val stack = ScopeStack().apply {
            screenHistory.record("Feed")
            push(screen = "Article")
        }
        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) { AutocaptureTarget("x") }

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
        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) { AutocaptureTarget("x") }

        val props = tracker.trackedProps.single()
        assertEquals("Header", props["section"]?.jsonPrimitive?.content)
        assertNull(props["screen"])
    }

    @Test
    fun theResolvedElementsOwnScopeRefinesTheAmbientScopeButNotScreenOrSection() {
        val tracker = AutocaptureRecordingTracker()
        val stack = ScopeStack()
        // An ambient route scope that also happens to name the same key the tapped row does, plus
        // the reserved keys — which no scope of either kind may overwrite.
        stack.push(
            scope = JsonObject(
                mapOf("surface" to JsonPrimitive("feed"), "experiment" to JsonPrimitive("b")),
            ),
        )
        stack.push(screen = "Feed", section = "For You")

        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) {
            AutocaptureTarget(
                identifier = "row",
                scope = JsonObject(
                    mapOf(
                        "article_id" to JsonPrimitive("42"),
                        "surface" to JsonPrimitive("row"),
                        "screen" to JsonPrimitive("hijacked"),
                        "section" to JsonPrimitive("hijacked"),
                    ),
                ),
            )
        }

        val props = tracker.trackedProps.single()
        assertEquals("42", props["article_id"]?.jsonPrimitive?.content, "the element's own scope must be attributed")
        assertEquals("b", props["experiment"]?.jsonPrimitive?.content, "the ambient scope must still contribute")
        // The tapped element is more specific than the screen it sits on, so it wins the shared key.
        assertEquals("row", props["surface"]?.jsonPrimitive?.content)
        // ...but screen/section stay reserved: an element scope cannot rename the screen it is on.
        assertEquals("Feed", props["screen"]?.jsonPrimitive?.content)
        assertEquals("For You", props["section"]?.jsonPrimitive?.content)
    }

    /**
     * The edge of that reservation, pinned deliberately: `screen` is only reserved by a screen that
     * exists. With none resolved anywhere — no ambient frame, no history — `enrich` writes nothing
     * over the element scope, so a scope key named `screen` stands. That is not special to this
     * path: the element scope occupies the slot an explicit call site's properties occupy, and those
     * behave the same way. The kdoc says "don't name a scope key `screen`" because of exactly this.
     */
    @Test
    fun anElementScopeKeyedScreenSurvivesWhenNoScreenIsResolvedAnywhere() {
        val tracker = AutocaptureRecordingTracker()
        reportTapIfResolvable(tracker, ScopeStack(), AutocaptureConfig()) {
            AutocaptureTarget(
                identifier = "row",
                scope = JsonObject(mapOf("screen" to JsonPrimitive("hijacked"))),
            )
        }

        assertEquals("hijacked", tracker.trackedProps.single()["screen"]?.jsonPrimitive?.content)
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
