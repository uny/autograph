@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.AutographInternalApi
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// --- Test fixtures (public top-level: the framework recreates them reflectively) ------------------

class PlainActivity : Activity()

class SecondPlainActivity : Activity()

class EmptyFragmentActivity : FragmentActivity()

/** A fragment with a real (non-null) view — the thing that makes it a screen rather than headless. */
open class ViewFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = View(requireContext())
}

class DetailFragment : ViewFragment()

class SecondFragment : ViewFragment()

/** A FragmentActivity that adds a content fragment in onCreate, so it is a fragment *host* at resume. */
class FragmentHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, DetailFragment(), "detail")
            .commitNow()
    }
}

/**
 * A ComponentActivity whose content is a ComposeView, so it is a Compose *host* at resume.
 * ComponentActivity (the real base of a Compose app) supplies the ViewTreeLifecycleOwner the
 * ComposeView needs on attach.
 */
class ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An empty ComposeView is enough: the exclusion looks for an AbstractComposeView instance in
        // the content subtree, not for composed content.
        setContentView(ComposeView(this))
    }
}

/**
 * Drives the real [installAutographNativeScreenCapture] pipeline through Robolectric's framework
 * lifecycle and asserts the `Screen Viewed` events and [ScopeStack] frames it produces. Builds on the
 * harness proven in `LifecycleHarnessTest`; these are PR-E's production tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidScreenCaptureTest {

    /** Records each `Screen Viewed` as "name:previous" (previous = "(none)" when absent). */
    private class RecordingTracker : Tracker {
        val screens = mutableListOf<String>()
        override fun track(name: String, properties: JsonObject, target: String?) = Unit
        override fun screen(name: String, properties: JsonObject) {
            val previous = (properties["previous_screen"] as? JsonPrimitive)?.content ?: "(none)"
            screens += "$name:$previous"
        }
        override fun identify(userId: String, traits: JsonObject) = Unit
    }

    private val tracker = RecordingTracker()
    private val scopeStack = ScopeStack()

    private fun install() = installAutographNativeScreenCapture(
        application = RuntimeEnvironment.getApplication(),
        tracker = tracker,
        scopeStack = scopeStack,
        activityScreenName = { it.javaClass.simpleName },
        fragmentScreenName = { it.javaClass.simpleName },
    )

    @Test
    fun aResumedActivityEmitsScreenViewedAndPushesItsFrame() {
        install()
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
        assertEquals("PlainActivity", scopeStack.current().screen)
    }

    @Test
    fun aSecondActivityCarriesTheFirstAsPreviousScreen() {
        install()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup()

        assertEquals(
            listOf("PlainActivity:(none)", "SecondPlainActivity:PlainActivity"),
            tracker.screens,
        )
        assertEquals("SecondPlainActivity", scopeStack.current().screen)
    }

    @Test
    fun anActivityFrameIsRemovedWhenItStops() {
        install()
        Robolectric.buildActivity(PlainActivity::class.java).setup().pause().stop()

        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
        assertNull("stopping the only screen empties the stack", scopeStack.current().screen)
    }

    @Test
    fun aReResumeWithoutAStopDoesNotEmitAgain() {
        install()
        Robolectric.buildActivity(PlainActivity::class.java).setup().pause().resume()

        // The Activity never stopped, so its frame is still live; the re-resume is deduped.
        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
    }

    @Test
    fun returningToTheSameScreenHasNoSelfPreviousScreen() {
        install()
        Robolectric.buildActivity(PlainActivity::class.java).setup().pause().stop()
        // A fresh view of the same screen, nothing captured in between: previous would be itself, so it
        // is suppressed to none rather than naming the screen its own previous_screen.
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertEquals(
            listOf("PlainActivity:(none)", "PlainActivity:(none)"),
            tracker.screens,
        )
    }

    @Test
    fun aFragmentEmitsAndTheHostingActivityDoesNot() {
        install()
        Robolectric.buildActivity(FragmentHostActivity::class.java).setup()

        // Only the Fragment is the screen; the shell Activity that hosts it is excluded, so there is no
        // "FragmentHostActivity" double-count.
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aComposeHostingActivityIsExcluded() {
        install()
        Robolectric.buildActivity(ComposeHostActivity::class.java).setup()

        // Its Compose content reports its own Screen Viewed via TrackedScreen; the Activity must not
        // also report a coarse one. Nothing native is emitted.
        assertEquals(emptyList<String>(), tracker.screens)
        assertNull(scopeStack.current().screen)
    }

    @Test
    fun aFragmentBackStackReplaceAndPopReEmitsTheReturnedScreen() {
        install()
        // FragmentHostActivity shows DetailFragment from onCreate, so the Activity is an excluded shell
        // and DetailFragment is the first screen — the real single-Activity + Fragment nav shape.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager

        fm.beginTransaction().replace(android.R.id.content, SecondFragment(), "second")
            .addToBackStack(null).commit()
        fm.executePendingTransactions()
        fm.popBackStack()
        fm.executePendingTransactions()

        assertEquals(
            listOf(
                "DetailFragment:(none)",
                "SecondFragment:DetailFragment",
                "DetailFragment:SecondFragment",
            ),
            tracker.screens,
        )
    }

    @Test
    fun aConfigurationChangeDoesNotReEmitTheSameScreen() {
        install()
        val controller = Robolectric.buildActivity(PlainActivity::class.java).setup()
        // A rotation destroys + re-creates the Activity. It is one continuous view of one screen, so it
        // must not emit a second Screen Viewed, and the frame must survive (a re-created frame is fine).
        controller.recreate()

        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
        assertEquals("PlainActivity", scopeStack.current().screen)
    }

    @Test
    fun uninstallStopsReportingAndRemovesFrames() {
        val handle = install()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        handle.uninstall()

        assertNull("uninstall removes the frames it pushed", scopeStack.current().screen)

        // A screen appearing after uninstall is not reported.
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup()
        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
    }
}
