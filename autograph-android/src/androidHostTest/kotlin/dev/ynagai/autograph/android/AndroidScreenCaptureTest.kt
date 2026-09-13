@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import dev.ynagai.autograph.context.autographScopeOrigin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

/** Hosts an inline DialogFragment as its content from onCreate, so it is present at resume. */
class InlineDialogHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, InlineDialogFragment(), "inline").commitNow()
    }
}

/** A DialogFragment used as inline content — added to a container, so it draws no window. */
class InlineDialogFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = View(requireContext())
}

/** Owns no fragment content of its own, but shows a view-bearing dialog fragment from onCreate. */
class DialogAtStartActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ComposeHostDialogFragment().show(supportFragmentManager, "sheet")
    }
}

/** A named View-based parent hosting a named View-based child. */
class PlainParentFragment : ViewFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.beginTransaction().add(DetailFragment(), "child").commitNow()
    }
}

/** A fragment host that exists before the capture is installed. */
class PreexistingHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, PlainParentFragment(), "parent").commitNow()
    }
}

/**
 * Installs the capture from its OWN `onCreate`, after `super`, and then adds a fragment — the shape an
 * app has when it initialises the SDK behind a consent gate rather than from `Application.onCreate`.
 */
class LateInstallHostActivity : FragmentActivity() {
    companion object {
        lateinit var tracker: Tracker
        lateinit var scopeStack: ScopeStack
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAutographNativeScreenCapture(
            application = application,
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { it.javaClass.simpleName },
        )
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, DetailFragment(), "detail").commitNow()
    }
}

/** A named fragment hosting an unnamed Compose child in a container inside its own layout. */
class ContainerNestingFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = WIDGET_CONTAINER_ID }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .add(WIDGET_CONTAINER_ID, ComposeHostFragment(), "widget").commitNow()
    }

    private companion object { const val WIDGET_CONTAINER_ID = 4242 }
}

/** A Compose host that commits a NAMED child from onAttach — earlier than its own attach callback. */
class AttachTimeNestingFragment : Fragment() {
    override fun onAttach(context: Context) {
        super.onAttach(context)
        childFragmentManager.beginTransaction().add(SecondFragment(), "child").commitNow()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext())
}

/** A named fragment that commits an unnamed Compose child at construction, before it resumes. */
class NestingFragment : ViewFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.beginTransaction().add(ComposeHostFragment(), "widget").commitNow()
    }
}

/** A FragmentActivity that adds a content fragment in onCreate, so it is a fragment *host* at resume. */
class FragmentHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guarded so a recreate() restores the fragment rather than adding a second one — without it a
        // rotation test would fail on the fixture instead of on the capture.
        if (savedInstanceState != null) return
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
    fun aResumedFragmentThatDeclaresNothingStillLetsWhatIsComposedInsideItSpeak() {
        // An opted-out Compose-hosting fragment declares nothing of its own, but it is on display, and
        // the ambient read now takes a frame's descendants off display with it (#228). Left
        // deselected on resume, the fragment would silence every TrackedScreen composed inside it
        // and the ambient screen would fall back to the screen beneath — a wrong value. The
        // composition's frame is stood in for here by a plain push under the fragment's claimed
        // handle, which is exactly the link the Compose provider makes.
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { if (it is ComposeHostFragment) null else it.javaClass.simpleName },
        )
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, ComposeHostFragment(), "compose").commitNow()
        val host = fm.findFragmentByTag("compose")!!.requireView().autographScopeOrigin()
        assertNotNull("the fragment's view is claimed", host)

        scopeStack.push(screen = "ComposedInside", parent = host)
        assertEquals("ComposedInside", scopeStack.current().screen)
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

    /** Runs [block] with this capture installed, then uninstalls it. */
    private fun AutographNativeScreenCapture.uninstallAfter(block: () -> Unit) {
        block()
        uninstall()
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
        // it would pin `reserveFrame` pushing an unmasked frame rather than the property named here.
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

    // --- Selection, and the two questions it is kept apart from -----------------------------------

    /**
     * Lets the posted demotion check run.
     *
     * The check has to see the host's resume dispatch *finished* — mid-dispatch a sibling about to
     * resume still reads `isResumed == false` — so it is posted to the main looper. Robolectric's
     * paused looper does not drain it on its own; a real one always does.
     */
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun coResumedFragmentsDoNotReEmitWhenTheirHostIsMerelyPausedAndResumed() {
        install()
        // Several surfaces RESUMED at once is ordinary: `add` on top, `show()`/`hide()` navigation, a
        // child fragment resuming before its parent. A permission prompt then pauses and resumes all
        // of them, and nothing about that is a new view of anything.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, SecondFragment(), "top").commitNow()
        assertEquals(listOf("DetailFragment:(none)", "SecondFragment:DetailFragment"), tracker.screens)

        controller.pause()
        controller.resume()
        drainMainLooper()

        assertEquals(
            "a host interruption is not a screen view of anything",
            listOf("DetailFragment:(none)", "SecondFragment:DetailFragment"),
            tracker.screens,
        )
    }

    @Test
    fun aNamedPageShownAfterAnUnnamedOneDoesNotLendItItsScreenOnTheWayBack() {
        install()
        // The three-page pager. Position alone cannot answer this: the named page attaches LATER than
        // the unnamed page, so its frame sits above the mask and wins on insertion order — and unlike
        // a page that was only ever cached off-screen, this one really was shown, so its frame carries
        // a screen. It is off-screen by the time the user comes back, though: demoted to STARTED,
        // never stopped, never detached. Only selection can tell the two apart.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        val named = fm.findFragmentByTag("detail")!!
        val unnamed = ComposeHostFragment()
        fm.beginTransaction()
            .add(android.R.id.content, unnamed, "page2")
            .setMaxLifecycle(unnamed, Lifecycle.State.STARTED)
            .commitNow()
        fm.beginTransaction()
            .setMaxLifecycle(named, Lifecycle.State.STARTED)
            .setMaxLifecycle(unnamed, Lifecycle.State.RESUMED)
            .commitNow()
        assertNull(scopeStack.current().screen)

        // Swipe on to a third, named page — attached and resumed after the mask was already standing.
        val third = SecondFragment()
        fm.beginTransaction()
            .add(android.R.id.content, third, "page3")
            .setMaxLifecycle(unnamed, Lifecycle.State.STARTED)
            .setMaxLifecycle(third, Lifecycle.State.RESUMED)
            .commitNow()
        assertEquals("SecondFragment", scopeStack.current().screen)

        // ... and back to the unnamed page.
        fm.beginTransaction()
            .setMaxLifecycle(third, Lifecycle.State.STARTED)
            .setMaxLifecycle(unnamed, Lifecycle.State.RESUMED)
            .commitNow()

        assertNull("the unnamed page on display still names no screen", scopeStack.current().screen)
        assertEquals(
            listOf("DetailFragment:(none)", "SecondFragment:DetailFragment"),
            tracker.screens,
        )
    }

    @Test
    fun aPageSwappedInWhileTheHostWasPausedIsTheOneReportedOnReturn() {
        install()
        // The case no Fragment callback describes. Both pages are already STARTED while the host is
        // paused, so swapping which one is primary produces NOTHING — measured: no pause, no resume,
        // for either page. Only the host's own resume, and the state as it stands after its whole
        // dispatch, can tell that the outgoing page is not coming back.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        val first = fm.findFragmentByTag("detail")!!
        val second = SecondFragment()
        fm.beginTransaction()
            .add(android.R.id.content, second, "second")
            .setMaxLifecycle(second, Lifecycle.State.STARTED)
            .commitNow()

        controller.pause()
        fm.beginTransaction()
            .setMaxLifecycle(first, Lifecycle.State.STARTED)
            .setMaxLifecycle(second, Lifecycle.State.RESUMED)
            .commitNow()
        controller.resume()
        drainMainLooper()

        assertEquals("SecondFragment", scopeStack.current().screen)
        assertEquals(
            listOf("DetailFragment:(none)", "SecondFragment:DetailFragment"),
            tracker.screens,
        )
    }

    @Test
    fun thePageDemotedWhileTheHostWasPausedReportsAgainWhenTheUserComesBackToIt() {
        install()
        // The other half of the swap above, and the one an event-based rule gets wrong: the demoted
        // page never received a pause, so nothing marked its view as ended, and returning to it would
        // be silent — the screen the user is looking at would simply never be reported again.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        val first = fm.findFragmentByTag("detail")!!
        val second = SecondFragment()
        fm.beginTransaction()
            .add(android.R.id.content, second, "second")
            .setMaxLifecycle(second, Lifecycle.State.STARTED)
            .commitNow()

        controller.pause()
        fm.beginTransaction()
            .setMaxLifecycle(first, Lifecycle.State.STARTED)
            .setMaxLifecycle(second, Lifecycle.State.RESUMED)
            .commitNow()
        controller.resume()
        drainMainLooper()

        // ... and now the user swipes back.
        fm.beginTransaction()
            .setMaxLifecycle(second, Lifecycle.State.STARTED)
            .setMaxLifecycle(first, Lifecycle.State.RESUMED)
            .commitNow()

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
    fun aFragmentUnderAPartialCoverIsSilentOnReturnAndKeepsItsScreen() {
        install()
        // A dialog Activity / permission prompt over a fragment host: paused, never stopped, and the
        // user never left the screen. Coming back must not report it twice, and it must attribute
        // again the moment it does.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()

        controller.pause()
        assertNull("nothing of this app is on display while the host is paused", scopeStack.current().screen)
        controller.resume()
        drainMainLooper()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
    }

    @Test
    fun aFragmentUnderACoveringScreenReportsAgainOnReturn() {
        install()
        // The same partial cover, but by another Activity that IS a screen: the user demonstrably saw
        // something else, so the return is a new view. This is the fragment half of
        // anActivityReturnedToUnderAPartialCoverReportsTheReturn.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        controller.pause()
        Robolectric.buildActivity(PlainActivity::class.java).setup().pause().stop().destroy()
        controller.resume()
        drainMainLooper()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(
            listOf(
                "DetailFragment:(none)",
                "PlainActivity:DetailFragment",
                "DetailFragment:PlainActivity",
            ),
            tracker.screens,
        )
    }

    @Test
    fun anUnnamedChildCommittedBeforeItsParentResumesStillDoesNotMaskIt() {
        install()
        // The ordering the containment gate has to survive: a child fragment resumes BEFORE its parent
        // (measured), so an embedded Compose widget committed in the host's onCreate reaches the mask
        // gate while the host has declared nothing yet. Asking what the host *will* say, rather than
        // what it has already pushed, is what keeps this a screen instead of a null.
        val activity = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, NestingFragment(), "parent").commitNow()

        assertEquals("NestingFragment", scopeStack.current().screen)
    }

    // --- Mounting, masking and the surfaces a mask must NOT speak for ------------------------------

    @Test
    fun anActivityThatAlreadyExistedWhenTheCaptureInstalledIsStillCaptured() {
        // The documented contract is "not reported until its next transition", not "never". An app
        // that initialises the SDK from its first Activity's onCreate — or re-installs against a new
        // tracker on logout — misses onActivityCreated for the Activity on screen, and gating capture
        // on that callback made the screen the user is looking at invisible for the rest of its life.
        val controller = Robolectric.buildActivity(PlainActivity::class.java).create()
        install()
        controller.start().resume()

        assertEquals("PlainActivity", scopeStack.current().screen)
        assertEquals(listOf("PlainActivity:(none)"), tracker.screens)
    }

    @Test
    fun anActivityMaskedWhileADialogWasAttachedNamesItsScreenAgainAfterwards() {
        install()
        val controller = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        assertEquals("EmptyFragmentActivity", scopeStack.current().screen)

        // What makes the Activity look like a shell is time-varying: "does any added fragment have a
        // view". With a view-bearing dialog attached, a plain pause/resume — a permission prompt, a
        // phone call — used to re-derive that and mask the Activity ONE-WAY, so the screen never came
        // back even after the dialog was gone. The decision is settled once per mounting now.
        val dialog = ComposeHostDialogFragment()
        dialog.show(fm, "dialog")
        fm.executePendingTransactions()
        controller.pause()
        controller.resume()
        drainMainLooper()
        assertNull("the dialog covers the Activity while it is up", scopeStack.current().screen)

        dialog.dismiss()
        fm.executePendingTransactions()

        assertEquals("EmptyFragmentActivity", scopeStack.current().screen)
        assertEquals(false, scopeStack.current().screenMasked)
    }

    @Test
    fun aNamedChildCommittedFromItsHostsOnAttachStillOutranksTheHostsMask() {
        install()
        // A fragment's children are attached from inside its own performAttach, BEFORE the host's
        // attach callback — so reserving on `onFragmentAttached` put the host's mask ABOVE the child
        // it contains, and the child's screen was cleared by the very host it declares a screen for.
        // Reserving on `onFragmentPreAttached` is early enough; nothing else is.
        val activity = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, AttachTimeNestingFragment(), "host").commitNow()

        assertEquals("SecondFragment", scopeStack.current().screen)
    }

    @Test
    fun aReattachedFragmentOutranksTheSiblingItWasDetachedUnder() {
        install()
        // `detach()` stops the fragment and destroys its view but never calls onDetach, so its frame
        // is never dropped — and reusing the frame reserved at the original attach put the returning
        // fragment BELOW a sibling that mounted while it was away. It emitted its own name while the
        // ambient screen still said the sibling's. A mounting that ended takes its position with it.
        val activity = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        val first = DetailFragment()
        fm.beginTransaction().add(android.R.id.content, first, "first").commitNow()
        fm.beginTransaction().add(android.R.id.content, SecondFragment(), "second").commitNow()
        fm.beginTransaction().detach(first).commitNow()
        assertEquals("SecondFragment", scopeStack.current().screen)

        fm.beginTransaction().attach(first).commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(
            listOf(
                "EmptyFragmentActivity:(none)",
                "DetailFragment:EmptyFragmentActivity",
                "SecondFragment:DetailFragment",
                "DetailFragment:SecondFragment",
            ),
            tracker.screens,
        )
    }

    @Test
    fun anExcludedActivityDoesNotMaskANamedActivityResumedBesideIt() {
        install()
        // Multi-resume (Android 10+ split screen, and what Robolectric produces here): two Activities
        // RESUMED at once, in two windows. An Activity covers its OWN window, and this stack knows
        // nothing about windows, so one that is not alone on display does not mask — otherwise an
        // excluded Compose host blanks the screen of a named Activity it does not cover at all.
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        Robolectric.buildActivity(ComposeHostActivity::class.java).setup()

        assertEquals("PlainActivity", scopeStack.current().screen)
    }

    @Test
    fun anExcludedFragmentTheAdopterAlsoOptedOutOfDoesNotMask() {
        // A null name is "do not report this surface". It must not CAUSE a mask, and it must not let
        // one stand either: opting a Compose-based library fragment out of reporting cannot be what
        // blanks the screen it sits beside. Masking on the structural filter alone did exactly that.
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { if (it is ComposeHostFragment) null else it.javaClass.simpleName },
        )
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, ComposeHostFragment(), "library").commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aHostWhoseOwnLayoutContainsAComposeWidgetMasksRatherThanNamingAScreen() {
        install()
        // The documented shape of the containment gate's limit, pinned rather than left to prose. A
        // Compose widget embedded in the host's OWN layout is inside the host's view subtree, so the
        // host is a Compose host by the structural filter and declares no screen — the gate never
        // gets a named ancestor to protect. No screen, rather than the stale one underneath.
        val activity = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, ContainerNestingFragment(), "host").commitNow()

        assertNull(scopeStack.current().screen)
        assertTrue("and it says so — an assertion, not merely an absent screen", scopeStack.current().screenMasked)
    }

    @Test
    fun aMaskSaysItIsAMaskAndNotMerelyAnAbsentScreen() {
        install()
        // screen == null has two meanings, and AmbientContext.screenMasked is what tells them apart
        // for a pipeline holding a screen name from elsewhere: an absence may be filled, an assertion
        // must be respected. Asserting only the null would pass for an implementation that deselected
        // the frame beneath instead of masking — and that implementation reintroduces the stale
        // screen this whole change exists to remove.
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, ComposeHostFragment(), "compose").commitNow()

        assertNull(scopeStack.current().screen)
        assertTrue(scopeStack.current().screenMasked)
    }

    @Test
    fun anUnnamedActivityPassingOverAScreenDoesNotMakeItReportTwice() {
        install()
        // endViewsOffDisplay fires from an actual emit, not from every resume, and that tightness is
        // the whole difference between "the user saw another screen" and "something passed over".
        // A consent gate, a login Activity, a Compose host — anything that reports nothing — must
        // leave the screen beneath silent on return.
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        controller.pause()
        Robolectric.buildActivity(ComposeHostActivity::class.java).setup().pause().stop().destroy()
        controller.resume()
        drainMainLooper()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
    }

    @Test
    fun anActivityResumedBesideAnotherDoesNotEndItsView() {
        install()
        // The multi-resume guard in endViewsOffDisplay. Both Activities are RESUMED (Robolectric does
        // not pause the first, and split screen does not either), so neither has left the display and
        // neither ends the other's view — without the guard the first re-emits for a screen the user
        // never left.
        val first = Robolectric.buildActivity(PlainActivity::class.java).setup()
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup()
        first.pause().resume()
        drainMainLooper()

        assertEquals(
            listOf("PlainActivity:(none)", "SecondPlainActivity:PlainActivity"),
            tracker.screens,
        )
    }

    @Test
    fun uninstallDropsTheFramesOfAHostsFragmentsToo() {
        install().uninstallAfter {
            Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
            assertEquals("DetailFragment", scopeStack.current().screen)
        }
        // Fragment frames live on the per-Activity callbacks, so tearDown has to reach through the
        // registrations to release them. The Activity-only uninstall test cannot see that at all.
        assertNull(scopeStack.current().screen)
        assertEquals(0, frameCount())
    }

    @Test
    fun rotatingAFragmentHostDoesNotReEmitTheFragmentsScreen() {
        install()
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        // The whole config-change chain is rewritten here — the marker is left by onFragmentStopped,
        // the frames are released by onActivityDestroyed before the callbacks unregister, and the
        // re-created instance reserves and consumes on its own attach/resume. A single wrong step
        // double-counts the single most common screen shape this library targets.
        controller.recreate()
        drainMainLooper()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
    }

    @Test
    fun aNestedHostKeepsItsChildsScreenAcrossAStopAndRestart() {
        install()
        // A stop destroys nothing — the fragment keeps its view, its children keep their frames, and
        // an AbstractComposeView keeps its composition (the default strategy disposes on detach from
        // the window, which a stop is not). Treating a stop as the end of a mounting and re-reserving
        // the frame there jumped it above every one of those: pressing home and coming back turned
        // this child's screen into a masked null. A mounting ends when the VIEW does.
        val controller = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, AttachTimeNestingFragment(), "host").commitNow()
        assertEquals("SecondFragment", scopeStack.current().screen)

        controller.pause().stop().start().resume()
        drainMainLooper()

        assertEquals("SecondFragment", scopeStack.current().screen)
    }

    @Test
    fun aFragmentAttachedBeforeTheCaptureInstalledIsStillCaptured() {
        // The fragment callbacks are registered when their Activity is tracked, and for an Activity
        // that already existed that is at its resume — after its fragments have attached. Requiring a
        // reservation made those fragments invisible for their whole life while their host went on
        // masking over them: no screen at all, on the screen the user is looking at.
        LateInstallHostActivity.tracker = tracker
        LateInstallHostActivity.scopeStack = scopeStack
        Robolectric.buildActivity(LateInstallHostActivity::class.java).setup()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(listOf("DetailFragment:(none)"), tracker.screens)
    }

    @Test
    fun theScreenNameCallbackIsNotAskedAboutAHeadlessFragment() {
        // `{ it.requireView().tag as String }` is a reasonable lambda to write. Asking it about
        // Glide's retained worker fragment throws out of a FragmentManager dispatch and takes the app
        // with it — so a surface that can neither be reported nor mask is never asked.
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { it.requireView().let { _ -> it.javaClass.simpleName } },
        )
        val activity = Robolectric.buildActivity(FragmentHostActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction().add(HeadlessFragment(), "worker").commitNow()

        assertEquals("DetailFragment", scopeStack.current().screen)
    }

    @Test
    fun aScreenNameThatArrivesLateIsPickedUpAtTheNextResume() {
        // What a surface IS is settled once per mounting; what it is CALLED is not. A title that is
        // null until its data loads was latched to "declares nothing" by the first resume and never
        // reported for the rest of the mounting.
        var title: String? = null
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { if (it is DetailFragment) title else it.javaClass.simpleName },
        )
        val controller = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        assertNull("nothing names a screen yet", scopeStack.current().screen)

        title = "Detail"
        controller.pause().resume()
        drainMainLooper()

        assertEquals("Detail", scopeStack.current().screen)
        assertEquals(listOf("Detail:(none)"), tracker.screens)
    }

    @Test
    fun anActivityWithASheetUpAtItsFirstResumeIsStillItsOwnScreenAfterwards() {
        install()
        // "Does any added fragment have a view" is how a single-Activity shell is recognised, and a
        // DialogFragment is not one: it draws its own window OVER the Activity rather than being its
        // content. Counting it made an Activity that merely had a sheet up when it first resumed a
        // shell for good — the decision is settled once per mounting and the mask is one-way, so it
        // reported no screen at all for the rest of its life.
        val activity = Robolectric.buildActivity(DialogAtStartActivity::class.java).setup().get()
        val fm = activity.supportFragmentManager
        assertNull("the sheet covers it while it is up", scopeStack.current().screen)

        (fm.findFragmentByTag("sheet") as DialogFragment).dismiss()
        fm.executePendingTransactions()

        assertEquals("DialogAtStartActivity", scopeStack.current().screen)
        assertEquals(listOf("DialogAtStartActivity:(none)"), tracker.screens)
    }

    @Test
    fun anActivityThatHasBecomeAShellStopsReportingItselfOnTheNextReturn() {
        install()
        // An Activity's frame keeps its position for life, so a stop cannot replace it — but what it
        // SAYS still has to be re-derived there. An Activity that owned its content at its first
        // resume and has since become a fragment host went on emitting its own name on every
        // foreground return: two spurious screen views per return, and a bogus previous_screen link.
        val controller = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, DetailFragment(), "content").commitNow()

        controller.pause().stop().start().resume()
        drainMainLooper()

        assertEquals("DetailFragment", scopeStack.current().screen)
        assertEquals(
            listOf(
                "EmptyFragmentActivity:(none)",
                "DetailFragment:EmptyFragmentActivity",
                // The fragment stopped, so its return is a fresh view. The Activity's is not: it is
                // a shell now and says nothing.
                "DetailFragment:(none)",
            ),
            tracker.screens,
        )
    }

    @Test
    fun anActivityStillMaskingDoesNotReportAScreenItCannotResolve() {
        install()
        // An Activity re-derives what it says at every stop but cannot take a mask back, so it can
        // come back believing it is a screen again while its frame still says there is none. The
        // ambient value is absent either way; what must not happen is a `Screen Viewed` naming the
        // Activity, which would also write a coarse Activity-class name into the previous_screen
        // chain that no captured event on that screen can match. This pins the sequence, not the
        // `!masked` guard in onSurfaceResumed: removing that guard leaves this green, because the
        // state it defends against turned out not to be reachable. See its comment.
        val controller = Robolectric.buildActivity(EmptyFragmentActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, DetailFragment(), "content").commitNow()
        // This return is where the Activity re-derives itself as a shell and masks. The mask is not
        // visible yet — the fragment's frame sits above it and names a screen.
        controller.pause().stop().start().resume()
        drainMainLooper()
        assertEquals("DetailFragment", scopeStack.current().screen)

        fm.beginTransaction().remove(fm.findFragmentByTag("content")!!).commitNow()
        controller.pause().stop().start().resume()
        drainMainLooper()

        assertNull(scopeStack.current().screen)
        assertEquals(
            listOf(
                "EmptyFragmentActivity:(none)",
                "DetailFragment:EmptyFragmentActivity",
                "DetailFragment:(none)",
            ),
            tracker.screens,
        )
    }

    @Test
    fun aDialogFragmentInAContainerStillMakesItsActivityAShell() {
        install()
        // `showsDialog` is what the shell test actually means to ask, not the type: a DialogFragment
        // added to a CONTAINER is inline content (onCreate forces showsDialog to containerId == 0),
        // so it does make its Activity a shell. Reading the type alone would let the Activity report
        // its own class name beside the fragment's.
        Robolectric.buildActivity(InlineDialogHostActivity::class.java).setup()

        assertEquals("InlineDialogFragment", scopeStack.current().screen)
        // Only the fragment. Reading the type would make the host capturable and double-count it.
        assertEquals(listOf("InlineDialogFragment:(none)"), tracker.screens)
    }

    @Test
    fun aLateInstallOverANestedHostStillLetsTheInnermostScreenWin() {
        // Fragments already attached when the capture installs are adopted outermost-first. Reserving
        // them lazily at resume instead inverts the nesting, because a child resumes inside its
        // parent's performResume — the pair then reported the PARENT, a wrong screen where the
        // pre-existing behaviour was merely an absent one.
        val controller = Robolectric.buildActivity(PreexistingHostActivity::class.java).setup()
        install()
        controller.pause().resume()
        drainMainLooper()

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
