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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
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

/** A fragment whose content is Compose and which declares no screen — the #216 shape. */
class ComposeHostFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext())
}

/** The same shape as a Compose-content BottomSheetDialogFragment. */
class ComposeHostDialogFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext())
}

/** A retained worker fragment — no view, so not a surface. Glide's is the everyday example. */
class HeadlessFragment : Fragment()

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
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            val previous = (properties.asJsonObject()["previous_screen"] as? JsonPrimitive)?.content ?: "(none)"
            screens += "$name:$previous"
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
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
    fun aViewPagerStylePageSwitchBackReportsTheReturnedPage() {
        install()
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        val first = fm.findFragmentByTag("detail")!!
        val second = SecondFragment()

        // The ViewPager2 / FragmentStateAdapter shape: pages are ADDED once and switched by moving the
        // off-screen one down to STARTED. STARTED never calls onStop, so the capture never sees the
        // page leave.
        fm.beginTransaction().add(android.R.id.content, second, "second").commitNow()
        fm.beginTransaction().setMaxLifecycle(first, Lifecycle.State.STARTED).commitNow()
        // ... and back to page one.
        fm.beginTransaction().setMaxLifecycle(second, Lifecycle.State.STARTED).commitNow()
        fm.beginTransaction().setMaxLifecycle(first, Lifecycle.State.RESUMED).commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
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
    fun anActivityReturnedToUnderAPartialCoverReportsTheReturn() {
        install()
        // A partially-covering Activity pauses the one beneath without stopping it, so the frame of the
        // screen underneath survives — deliberately, so a dialog or a permission prompt stays silent.
        // A capturable Activity on top is the case where that is NOT one continuous view: the user
        // really did see another screen, so the return is a screen view of its own and the frame
        // underneath has to come back on top of the stack.
        val underneath = Robolectric.buildActivity(PlainActivity::class.java).setup().pause()
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup().pause().stop().destroy()
        underneath.resume()

        assertEquals("PlainActivity", scopeStack.current().screen)
        assertEquals(
            listOf(
                "PlainActivity:(none)",
                "SecondPlainActivity:PlainActivity",
                "PlainActivity:SecondPlainActivity",
            ),
            tracker.screens,
        )
    }

    // --- The screen mask: a surface that reports no screen of its own (#216) ----------------------

    @Test
    fun aComposeHostAddedOverAScreenCarriesNoScreenRatherThanTheOneBeneath() {
        install()
        // ADD, not replace: the fragment underneath is only paused, never stopped, so its frame stays
        // — correctly, since a dialog or a permission prompt must not drop it. Without a mask the
        // Compose host, which reports nothing itself, would inherit that frame and every tap on it
        // would name the screen the user just left.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, ComposeHostFragment(), "compose")
            .addToBackStack(null).commit()
        fm.executePendingTransactions()

        assertNull(scopeStack.current().screen)
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
    }

    @Test
    fun aComposeHostDialogCarriesNoScreenRatherThanTheOneBeneath() {
        install()
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        ComposeHostDialogFragment().show(fm, "dialog")
        fm.executePendingTransactions()

        assertNull(scopeStack.current().screen)
    }

    @Test
    fun aPagerPageReturnedToAfterAnUnnamedPageCarriesItsOwnScreenAgain() {
        install()
        // The round trip the one-way mask got wrong. A ViewPager2 / FragmentStateAdapter demotes the
        // outgoing page to STARTED — onPause, never onStop, never detach — so a mask that only came
        // off at detach stayed live above the returning page's frame, and onScreenResumed's supersede
        // re-push never fires here because an unnamed page emits nothing and so leaves
        // screenHistory.lastScreen unchanged. Before the fix: null on the page actually on display.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        val named = fm.findFragmentByTag("detail")!!
        val unnamed = ComposeHostFragment()
        fm.beginTransaction().add(android.R.id.content, unnamed, "page2")
            .setMaxLifecycle(unnamed, Lifecycle.State.STARTED).commitNow()
        assertEquals("DetailFragment", scopeStack.current().screen)

        fm.beginTransaction()
            .setMaxLifecycle(named, Lifecycle.State.STARTED)
            .setMaxLifecycle(unnamed, Lifecycle.State.RESUMED).commitNow()
        assertNull("the unnamed page must still mask while it is the one on display", scopeStack.current().screen)

        fm.beginTransaction()
            .setMaxLifecycle(unnamed, Lifecycle.State.STARTED)
            .setMaxLifecycle(named, Lifecycle.State.RESUMED).commitNow()
        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aDetachedUnnamedSurfaceStopsMaskingTheScreenBeneath() {
        install()
        // detach() destroys the view and pauses the fragment without ever calling onDetach, and the
        // screen beneath never re-resumes — so nothing re-lifted its frame either.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        val host = ComposeHostFragment()
        fm.beginTransaction().add(android.R.id.content, host, "host").commitNow()
        assertNull(scopeStack.current().screen)

        fm.beginTransaction().detach(host).commitNow()
        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aFragmentOptedOutByNameDoesNotMaskTheScreenBeneath() {
        // `null` from fragmentScreenName is documented as "opt a screen out" — a view-bearing library
        // fragment, an ad or consent SDK surface. Opting out must not also blank the host's screen.
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { if (it is SecondFragment) null else it.javaClass.simpleName },
        )
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, SecondFragment(), "opted-out").commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun anUnnamedChildFragmentDoesNotMaskTheNamedFragmentContainingIt() {
        install()
        // An embedded Compose widget committed into a child FragmentManager AFTER its host resumed —
        // a mini-player, an inline card. It is contained by the screen, not a cover for it, so the
        // host must keep answering. Its mask frame still lands above the host's frame in the stack;
        // only containment tells the two apart.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val host = activity.supportFragmentManager.findFragmentByTag("detail")!!
        host.childFragmentManager.beginTransaction()
            .add(ComposeHostFragment(), "widget").commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aComposeHostDialogShownThroughAChildFragmentManagerStillMasks() {
        install()
        // A DialogFragment draws its own window over everything, so it covers its host whichever
        // FragmentManager it was shown through — the one case the "nested fragments do not cover
        // their host" gate must not catch.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val parent = activity.supportFragmentManager.findFragmentByTag("detail")!!
        ComposeHostDialogFragment().show(parent.childFragmentManager, "dialog")
        parent.childFragmentManager.executePendingTransactions()

        assertNull(scopeStack.current().screen)
    }

    @Test
    fun anActivityDestroyLeavesNoFrameBehind() {
        install()
        // onActivityDestroyed unregisters the fragment callbacks, and FragmentActivity.onDestroy runs
        // super.onDestroy() (which dispatches it) BEFORE mFragments.dispatchDestroy() — so
        // onFragmentDetached / onFragmentDestroyed never fire for the fragments still attached, and
        // the masks they own were never released. Counting frames is the only way to see it: with the
        // pause-deactivation in place a leaked mask is inert, so no observable screen value differs.
        repeat(3) {
            Robolectric.buildActivity(FragmentHostActivity::class.java).setup().pause().stop().destroy()
        }
        assertEquals(0, frameCount())
    }

    /** The size of [scopeStack]'s private frame list — the only way to observe an inert leak. */
    private fun frameCount(): Int {
        val field = ScopeStack::class.java.getDeclaredField("frames")
        field.isAccessible = true
        return (field.get(scopeStack) as List<*>).size
    }

    @Test
    fun theScreenBeneathComesBackWhenTheUnnamedSurfaceLeaves() {
        install()
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, ComposeHostFragment(), "compose")
            .addToBackStack(null).commit()
        fm.executePendingTransactions()
        fm.popBackStack()
        fm.executePendingTransactions()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun anOffScreenPagerPageDoesNotMaskThePageOnDisplay() {
        install()
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        // What FragmentStateAdapter does for a page cached by offscreenPageLimit: add it and cap it at
        // STARTED, so it attaches and never resumes. A mask that masked from the moment it was pushed
        // would blank the page actually on display — measured, and the reason the mask stays inert
        // until its surface resumes.
        // An EXCLUDED page (a Compose host): a capturable one never reaches the mask gate at all, so
        // it would pin `pushInertMask` pushing an unmasked frame rather than the property named here.
        val cached = ComposeHostFragment()
        fm.beginTransaction()
            .add(android.R.id.content, cached, "cached")
            .setMaxLifecycle(cached, Lifecycle.State.STARTED)
            .commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aHeadlessFragmentDoesNotMaskTheScreenItAttachesOver() {
        install()
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        // A retained worker fragment resumes like any other, so its mask would go live if it were not
        // dropped for having no view.
        fm.beginTransaction().add(HeadlessFragment(), "worker").commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
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
