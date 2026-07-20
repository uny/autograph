package dev.ynagai.autograph.sample.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The narrow instrumented smoke for #65's Android screen capture — the on-device coverage Robolectric
 * cannot give (real framework, real windowing, a real back button). It drives the non-Compose
 * [NativeScreensActivity] flow through Espresso and asserts the `Screen Viewed` sequence the shipped
 * `installAutographNativeScreenCapture` (installed by [NativeSampleApplication]) produced.
 *
 * This is the Android peer of sample-ios's XCUITest: exercise the real production install through real
 * UI, not a reimplementation.
 */
@RunWith(AndroidJUnit4::class)
class NativeScreensInstrumentedTest {

    @Before
    fun clearLog() {
        NativeScreenLog.entries.clear()
    }

    @Test
    fun fragmentNavigationEmitsScreenViewsWithAContinuousPreviousChain() {
        ActivityScenario.launch(NativeScreensActivity::class.java).use {
            onView(withText("Screen A")).check(matches(isDisplayed()))

            onView(withText("Next")).perform(click())
            onView(withText("Screen B")).check(matches(isDisplayed()))

            pressBack()
            onView(withText("Screen A")).check(matches(isDisplayed()))
        }

        // Fragments are the screens (the host Activity is an excluded shell); pop re-reports the return
        // screen, and previous_screen stays continuous across the whole flow.
        assertEquals(
            listOf(
                "ScreenAFragment:(none)",
                "ScreenBFragment:ScreenAFragment",
                "ScreenAFragment:ScreenBFragment",
            ),
            NativeScreenLog.entries.toList(),
        )
    }
}
