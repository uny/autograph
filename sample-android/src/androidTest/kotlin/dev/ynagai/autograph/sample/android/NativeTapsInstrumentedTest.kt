package dev.ynagai.autograph.sample.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device smoke for #63's Android View tap capture, driving the **shipped**
 * `installAutographNativeTapCapture` (installed by [NativeSampleApplication]) through real touches on
 * [NativeTapsActivity]. Robolectric can pin what a pressed hierarchy resolves to; only this can show
 * that a real finger produces that hierarchy in the first place — the pressed state is set by the
 * framework's own touch dispatch, which a unit test does not run.
 *
 * Each case here is one of the claims the README makes about this capture. They are asserted through
 * the production install rather than a reimplementation, so a regression in the wiring — wrapping the
 * wrong window, reading the pressed state too late, reporting on the wrong event — fails the test
 * rather than passing quietly.
 */
@RunWith(AndroidJUnit4::class)
class NativeTapsInstrumentedTest {

    @Before
    fun clearLog() {
        NativeTapLog.targets.clear()
        NativeTapLog.composeClicks.clear()
        NativeTapLog.viewClicks.clear()
    }

    @Test
    fun anIdentifiedViewIsReportedByItsResourceEntryName() {
        withTapScreen { onView(withId(R.id.tap_button)).perform(click()) }

        assertEquals(listOf("tap_button"), NativeTapLog.targets.toList())
    }

    @Test
    fun aViewWithNoIdReportsNothing() {
        withTapScreen { onView(withText("Unidentified button")).perform(click()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
    }

    @Test
    fun aTapOnAnInnerLabelReportsTheClickableRowThatHandledIt() {
        // The label is what the finger is over, and it has an id of its own — but it handled nothing.
        withTapScreen { onView(withId(R.id.tap_row_label)).perform(click()) }

        assertEquals(listOf("tap_row"), NativeTapLog.targets.toList())
    }

    @Test
    fun aDisabledControlReportsNothing() {
        withTapScreen { onView(withId(R.id.tap_disabled)).perform(click()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
    }

    @Test
    fun overlappingViewsReportTheOneAndroidDispatchesTo() {
        // tap_over is the later child; tap_under has the higher elevation and receives the touch. A
        // geometric walk in child order would answer tap_over here.
        withTapScreen { onView(withId(R.id.tap_over)).perform(click()) }

        assertEquals(listOf("tap_under"), NativeTapLog.targets.toList())
    }

    @Test
    fun aTapOnComposeContentIsLeftToTheComposePipeline() {
        withTapScreen { onView(withId(R.id.tap_compose_host)).perform(click()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
        // ...and the tap did land on the island, so the assertion above is about the View pipeline
        // declining it rather than about the touch having missed.
        assertEquals(listOf("compose_island"), NativeTapLog.composeClicks.toList())
    }

    @Test
    fun nestedClickablesReportTheInnerOne() {
        withTapScreen { onView(withId(R.id.tap_nested_child)).perform(click()) }

        assertEquals(listOf("tap_nested_child"), NativeTapLog.targets.toList())
    }

    @Test
    fun aRecyclerViewRowReportsItsOwnId() {
        withTapScreen { onView(withText("Recycler row 1")).perform(click()) }

        assertEquals(listOf("tap_recycler_row"), NativeTapLog.targets.toList())
    }

    @Test
    fun aListViewRowReportsTheList() {
        // Documented, and not a bug to be fixed silently: AbsListView presses the list as well as the
        // row, and a platform row layout's id (`text1`) is shared by every such list in the app.
        withTapScreen { onView(withText("List row 1")).perform(click()) }

        assertEquals(listOf("tap_list"), NativeTapLog.targets.toList())
    }

    @Test
    fun aScrollReportsNothing() {
        // The gesture ends in an ACTION_UP like a tap does; reporting on every touch-up would invent
        // a tap here.
        withTapScreen { onView(withId(R.id.tap_scroller)).perform(swipeUp()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
    }

    @Test
    fun aViewTheAppExcludedReportsNothing() {
        withTapScreen { onView(withId(R.id.tap_ignored)).perform(scrollTo(), click()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
        // ...and the button was really clicked, so the assertion above is about the exclusion rather
        // than about the touch having missed.
        assertEquals(listOf("tap_ignored"), NativeTapLog.viewClicks.toList())
    }

    @Test
    fun aClickableInsideAnExcludedContainerReportsNothing() {
        // The mark is on the container only. Excluding a region must not mean marking every clickable
        // in it, which is the whole reason the check walks ancestors.
        withTapScreen { onView(withId(R.id.tap_ignored_child)).perform(scrollTo(), click()) }

        assertEquals(emptyList<String>(), NativeTapLog.targets.toList())
        assertEquals(listOf("tap_ignored_child"), NativeTapLog.viewClicks.toList())
    }

    private fun withTapScreen(interact: () -> Unit) {
        ActivityScenario.launch(NativeTapsActivity::class.java).use {
            // The capture attaches at the resumed lifecycle callback, which ActivityScenario has
            // already delivered by the time launch returns.
            interact()
        }
    }
}
