@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A capturable Activity with content of its own — a button — and two empty containers fragments
 * can be added into after the Activity has decided what it is.
 */
class OwnHostActivity : FragmentActivity() {
    lateinit var ownButton: Button
    val containerA = View.generateViewId()
    val containerB = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownButton = Button(this).apply { id = androidx.fragment.R.id.fragment_container_view_tag; isClickable = true }
        setContentView(
            FrameLayout(this).apply {
                addView(ownButton)
                addView(FrameLayout(context).apply { id = containerA })
                addView(FrameLayout(context).apply { id = containerB })
            },
        )
    }
}

/** A native fragment with a button of its own — a screen the native capture names and taps land in. */
open class ButtonFragment : Fragment() {
    lateinit var button: Button
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply {
            button = Button(context).apply { id = androidx.fragment.R.id.fragment_container_view_tag; isClickable = true }
            addView(button)
        }
}

class ButtonFragmentA : ButtonFragment()
class ButtonFragmentB : ButtonFragment()

/** A named fragment nesting an opted-out child (with a button) in its own view. */
class NestingButtonFragment : ButtonFragment() {
    val childContainer = View.generateViewId()
    val child: ButtonFragmentB? get() = childFragmentManager.findFragmentByTag("child") as ButtonFragmentB?
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        (super.onCreateView(inflater, container, savedInstanceState) as FrameLayout).apply {
            addView(FrameLayout(context).apply { id = childContainer })
        }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (child == null) {
            childFragmentManager.beginTransaction().add(childContainer, ButtonFragmentB(), "child").commitNow()
        }
    }
}

/**
 * An excluded fragment (it hosts Compose) that also has a native button of its own. Added straight
 * into a capturable Activity it is the #216 S2 shape: a mini-player over the Activity's content.
 */
class MiniPlayerFragment : ButtonFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        (super.onCreateView(inflater, container, savedInstanceState) as FrameLayout).apply {
            addView(ComposeView(context))
        }
}

/**
 * The two native pipelines together on one [ScopeStack]: what a tap **carries** depends on which
 * surface it landed in, not on which surface pushed its frame last. Asserted on the tap payload the
 * tracker receives, never on an ambient snapshot — an ambient `screen == null` is exactly the value
 * these tests exist to keep off the Activity's own taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTapOriginTest {

    private val taps = mutableListOf<Map<String, JsonElement>>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            taps += properties
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }
    private val scopeStack = ScopeStack()
    private lateinit var screens: AutographNativeScreenCapture
    private lateinit var tapsCapture: AutographNativeTapCapture
    private lateinit var controller: ActivityController<OwnHostActivity>

    @After
    fun tearDown() {
        if (::screens.isInitialized) screens.uninstall()
        if (::tapsCapture.isInitialized) tapsCapture.uninstall()
        warnedATapResolvedToNothing = false
    }

    @Test
    fun aMaskRaisedByAnExcludedFragmentReachesTheTapsInsideItAndNotTheActivitysOwn() {
        val activity = launch()
        // Added after the Activity decided it is a screen of its own: an unnamed surface on top of a
        // named one, in the same window. Measured on main before this: its mask, being the later
        // frame, blanked the Activity's own taps too.
        val mini = MiniPlayerFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, mini).commitNow()
        assertNull("the mask is up ambiently — the value the next assertion keeps off the Activity", scopeStack.current().screen)

        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(activity, mini.button)
        assertNull("inside the excluded fragment the tap carries no screen", taps.single()["screen"])
    }

    @Test
    fun aTapInOneNamedFragmentDoesNotCarryASiblingAddedLater() {
        val activity = launch()
        val a = ButtonFragmentA()
        val b = ButtonFragmentB()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, a).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.containerB, b).commitNow()
        // Both resumed, B later. Ambiently B wins; a tap in A must still say A.
        assertEquals("ButtonFragmentB", scopeStack.current().screen)

        tap(activity, a.button)
        assertEquals("ButtonFragmentA", taps.single()["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(activity, b.button)
        assertEquals("ButtonFragmentB", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aTapInAnOptedOutFragmentCarriesTheScreenOfTheSurfaceHostingItNotTheSiblingItCovers() {
        // The lineage half: an opted-out fragment (its name lambda answers null) neither names a
        // screen nor masks, so a tap in it is attributed by what CONTAINS it — the Activity — and
        // not by the named sibling it was added on top of, which is still resumed and, ambiently,
        // still the innermost screen. That sibling's name is the "wrong, not absent" residual the
        // previous design documented. Break the parent link and the tap has no screen at all.
        val activity = launch(fragmentScreenName = { if (it is ButtonFragmentB) null else it.javaClass.simpleName })
        val screen = ButtonFragmentA()
        val optedOut = ButtonFragmentB()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, screen).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.containerB, optedOut).commitNow()
        assertEquals("ButtonFragmentA", scopeStack.current().screen)

        tap(activity, optedOut.button)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aChildStaysLinkedToItsParentAcrossARebuildOfBothViews() {
        // detach()+attach() destroys and re-creates both views, and the frames are re-reserved
        // child-first (a child's view goes inside its parent's performDestroyView). The child's fresh
        // frame therefore pointed at a parent frame that was replaced a moment later; the link is
        // re-read when the views come back. Observable only through the child: opted out, it is
        // attributed by its parent, so a stale link drops the parent's name.
        val activity = launch(fragmentScreenName = { if (it is ButtonFragmentB) null else it.javaClass.simpleName })
        val parent = NestingButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, parent).commitNow()
        tap(activity, parent.child!!.button)
        assertEquals("NestingButtonFragment", taps.single()["screen"]?.jsonPrimitive?.content)
        taps.clear()

        activity.supportFragmentManager.beginTransaction().detach(parent).commitNow()
        activity.supportFragmentManager.beginTransaction().attach(parent).commitNow()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        tap(activity, parent.child!!.button)
        assertEquals("NestingButtonFragment", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aTapOutsideTheContentViewStillBelongsToTheActivity() {
        // An AppCompat window action bar lives in the decor, outside android.R.id.content — a
        // Toolbar menu tap must resolve from the Activity like any other, not fall back to the
        // ambient stack where a sibling's mask wins.
        val activity = launch()
        val mini = MiniPlayerFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, mini).commitNow()
        assertNull(scopeStack.current().screen)
        val menuItem = Button(activity).apply { id = androidx.fragment.R.id.fragment_container_view_tag; isClickable = true }
        (activity.window.decorView as ViewGroup).addView(menuItem)

        tap(activity, menuItem)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun uninstallingTheScreenCaptureTakesItsClaimsWithIt() {
        // A claim left on a view after uninstall hands the tap capture a stale origin, whose
        // lineage is gone — resolving to nothing, where the ambient stack still has an answer.
        val activity = launch()
        val fragment = ButtonFragmentA()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, fragment).commitNow()
        screens.uninstall()
        scopeStack.push(screen = "Manual")

        tap(activity, activity.ownButton)
        assertEquals("Manual", taps.single()["screen"]?.jsonPrimitive?.content)
        taps.clear()
        tap(activity, fragment.button)
        assertEquals("Manual", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aTapOnAViewNoSurfaceHasClaimedResolvesAmbiently() {
        // Tap capture alone: no screen capture, so nothing claims the view tree, and the tap falls
        // back to the ambient stack — here a frame the app pushed by hand.
        tapsCapture = installAutographNativeTapCapture(RuntimeEnvironment.getApplication(), tracker, scopeStack)
        scopeStack.push(screen = "Manual")
        controller = Robolectric.buildActivity(OwnHostActivity::class.java).setup()
        Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable()
        val activity = controller.get()

        tap(activity, activity.ownButton)
        assertEquals("Manual", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun launch(fragmentScreenName: (Fragment) -> String? = { it.javaClass.simpleName }): OwnHostActivity {
        screens = installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { "Main" },
            fragmentScreenName = fragmentScreenName,
        )
        tapsCapture = installAutographNativeTapCapture(RuntimeEnvironment.getApplication(), tracker, scopeStack)
        controller = Robolectric.buildActivity(OwnHostActivity::class.java).setup()
        Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertEquals("Main", scopeStack.current().screen)
        return activity
    }

    private var gestures = 0

    /** Presses [button] and lifts, the way the framework leaves it at the moment the capture reads. */
    private fun tap(activity: FragmentActivity, button: Button) {
        button.isPressed = true
        // A fresh gesture each time: the capture reports once per downTime, and Robolectric's clock
        // does not advance on its own.
        val downTime = SystemClock.uptimeMillis() + 1000L * ++gestures
        activity.window.callback!!.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_UP, 1f, 1f, 0))
        button.isPressed = false
        assertTrue("the tap was reported", taps.isNotEmpty())
    }
}
