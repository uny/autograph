package dev.ynagai.autograph.android

import android.app.Activity
import android.os.Bundle
import android.os.Looper
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
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** [OwnHostActivity] whose first container is marked as a slot beside the content. */
open class MarkedSlotActivity : OwnHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(containerA).isAutographEmbedded = true
    }
}

/** [MarkedSlotActivity] with a mini-player already in the slot at its first resume. */
class MarkedSlotWithMiniPlayerActivity : MarkedSlotActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().add(containerA, MiniPlayerFragment()).commitNow()
        }
    }
}

/** A mini-player that marks its own root view rather than relying on its container. */
class SelfMarkedMiniPlayerFragment : ButtonFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        (super.onCreateView(inflater, container, savedInstanceState) as FrameLayout).apply {
            addView(ComposeView(context))
            isAutographEmbedded = true
        }
}

/**
 * A native fragment with a slot of its own, marked, holding a Compose widget: the fragment-level
 * counterpart of the mini-player beside an Activity's content.
 */
class NativeScreenWithMarkedComposeSlotFragment : ButtonFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        (super.onCreateView(inflater, container, savedInstanceState) as FrameLayout).apply {
            addView(FrameLayout(context).apply { isAutographEmbedded = true; addView(ComposeView(context)) })
        }
}

/**
 * [isAutographEmbedded] lifts #281 for the shapes the app marks. Every test here has an unmarked
 * twin pinned as a limit in [AndroidTapOriginTest] / [AndroidScreenCaptureTest], which stays as it
 * is: without the mark, the behaviour is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutographEmbeddedTest {

    private val taps = mutableListOf<Map<String, JsonElement>>()
    private val screens = mutableListOf<String>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            taps += properties
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            screens += "$name:${(properties["previous_screen"] as? JsonPrimitive)?.content ?: "(none)"}"
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }
    private val scopeStack = ScopeStack()
    private lateinit var screenCapture: AutographNativeScreenCapture
    private lateinit var tapCapture: AutographNativeTapCapture

    @After
    fun tearDown() {
        if (::screenCapture.isInitialized) screenCapture.uninstall()
        if (::tapCapture.isInitialized) tapCapture.uninstall()
        warnedATapResolvedToNothing = false
    }

    @Test
    fun theMarkIsASettableTagThatClearsBackToUntagged() {
        val view = FrameLayout(RuntimeEnvironment.getApplication())
        val child = View(view.context).also(view::addView)
        assertFalse(view.isAutographEmbedded)
        assertFalse(isInEmbeddedSubtree(child))
        view.isAutographEmbedded = true
        assertTrue(view.isAutographEmbedded)
        assertTrue("a mark reaches the subtree under it", isInEmbeddedSubtree(child))
        view.isAutographEmbedded = false
        assertFalse(isInEmbeddedSubtree(child))
        assertFalse(view.isAutographEmbedded)
        assertNull(view.getTag(R.id.autograph_embedded))
    }

    @Test
    fun aMiniPlayerInAMarkedSlotDoesNotTurnItsActivityIntoAShellAtTheNextStop() {
        // The issue's first sequence, marked. Unmarked twin:
        // AndroidTapOriginTest.anActivityWithAMiniPlayerStillAttachedAtItsNextStopIsTakenForAShell.
        val controller = launch(MarkedSlotActivity::class.java)
        val activity = controller.get()
        val mini = MiniPlayerFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, mini).commitNow()

        controller.pause().stop().restart().resume()
        drainMainLooper()
        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)

        taps.clear()
        controller.pause().resume()
        drainMainLooper()
        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)

        // The mini-player is still excluded and still masks its own taps: it carries no screen, not
        // the Activity's.
        taps.clear()
        tap(activity, mini.button)
        assertNull(taps.single()["screen"])
    }

    @Test
    fun aMiniPlayerInAMarkedSlotAtTheFirstResumeLeavesTheActivityItsOwnScreen() {
        // Unmarked twin: AndroidTapOriginTest.anActivityWithAMiniPlayerAtItsFirstResumeIsTakenForAShell
        // and AndroidScreenCaptureTest.anActivityWithAMiniPlayerAtItsFirstResumeReportsNothingForItself.
        val controller = launch(MarkedSlotWithMiniPlayerActivity::class.java)
        val activity = controller.get()

        assertEquals(listOf("Main:(none)"), screens)
        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aMiniPlayerThatMarksItsOwnRootViewWorksLikeAMarkedSlot() {
        val controller = launch(OwnHostActivity::class.java)
        val activity = controller.get()
        val mini = SelfMarkedMiniPlayerFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, mini).commitNow()

        controller.pause().stop().restart().resume()
        drainMainLooper()
        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(activity, mini.button)
        assertNull("the root view's own mark does not make the mini-player capturable", taps.single()["screen"])
    }

    @Test
    fun anActivityWithAMiniPlayerInAMarkedSlotReportsItsReturnThroughAStop() {
        // The issue's second sequence, marked. Unmarked twin:
        // AndroidScreenCaptureTest.anActivityWithAMiniPlayerStillAttachedAtItsNextStopReportsNoReturnThroughIt.
        val controller = launch(MarkedSlotActivity::class.java)
        val activity = controller.get()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, MiniPlayerFragment()).commitNow()

        controller.pause()
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup().pause().stop().destroy()
        controller.stop().restart().resume()
        drainMainLooper()

        assertEquals(listOf("Main:(none)", "SecondPlainActivity:Main", "Main:SecondPlainActivity"), screens)
    }

    @Test
    fun aNamedNativeFragmentInAMarkedSlotDoesNotTakeItsActivitysReturn() {
        // A capturable fragment beside the content is still a screen of its own and reports itself,
        // but the Activity is what the user comes back to. Unmarked, the fragment would make the
        // Activity a shell that leaves its return to the fragment; marked, the Activity stays
        // capturable and reports the return itself.
        val controller = launch(MarkedSlotActivity::class.java)
        val activity = controller.get()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, ButtonFragmentA()).commitNow()
        assertEquals(listOf("Main:(none)", "ButtonFragmentA:Main"), screens)

        screens.clear()
        controller.pause()
        Robolectric.buildActivity(SecondPlainActivity::class.java).setup().pause().stop().destroy()
        controller.resume()
        drainMainLooper()

        assertEquals("Main:SecondPlainActivity", screens[1])
        tap(activity, activity.ownButton)
        assertEquals("Main", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aComposeWidgetInAMarkedSlotDoesNotMakeTheNativeFragmentAroundItAComposeHost() {
        val controller = launch(EmptyFragmentActivity::class.java, screenName = { "Main" })
        val fragment = NativeScreenWithMarkedComposeSlotFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment).commitNow()

        assertEquals("NativeScreenWithMarkedComposeSlotFragment", screens.last().substringBefore(':'))
        tap(controller.get(), fragment.button)
        assertEquals("NativeScreenWithMarkedComposeSlotFragment", taps.single()["screen"]?.jsonPrimitive?.content)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun <A : Activity> launch(
        activityClass: Class<A>,
        screenName: (Activity) -> String? = { if (it is SecondPlainActivity) it.javaClass.simpleName else "Main" },
    ): ActivityController<A> {
        screenCapture = installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = screenName,
            fragmentScreenName = { it.javaClass.simpleName },
        )
        tapCapture = installAutographNativeTapCapture(RuntimeEnvironment.getApplication(), tracker, scopeStack)
        val controller = Robolectric.buildActivity(activityClass).setup()
        drainMainLooper()
        return controller
    }

    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private var gestures = 0

    private fun tap(activity: FragmentActivity, button: Button) {
        button.isPressed = true
        val downTime = SystemClock.uptimeMillis() + 1000L * ++gestures
        activity.window.callback!!.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_UP, 1f, 1f, 0))
        button.isPressed = false
        assertTrue("the tap was reported", taps.isNotEmpty())
    }
}
