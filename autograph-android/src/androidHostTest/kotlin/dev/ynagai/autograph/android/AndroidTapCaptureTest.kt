@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** A plain Activity with a content view the tests press views inside of. */
class TapHostActivity : Activity()

/**
 * Covers the two halves of the tap capture separately: [resolveTapTarget], which decides *what* a
 * pressed hierarchy names, and [AndroidTapCapture], which decides *when* and *how often* that is
 * reported. The resolution rules here are the ones measured on a device (see
 * `installAutographNativeTapCapture`'s kdoc); this pins them so a later simplification cannot quietly
 * revert one.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned like the screen-capture tests: the module's targetSdk is ahead of Robolectric's newest
// supported SDK image.
@Config(sdk = [36])
class AndroidTapCaptureTest {

    /** An id that belongs to the module under test rather than to the platform. */
    private val appOwnedId = androidx.fragment.R.id.fragment_container_view_tag

    private val recorded = mutableListOf<Pair<String, String?>>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            recorded += name to target
        }

        override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }

    @Before
    fun reset() {
        recorded.clear()
        warnedATapResolvedToNothing = false
    }

    // --- resolveTapTarget -------------------------------------------------------------------------

    @Test
    fun `names the root of the pressed subtree, not its deepest pressed view`() {
        // The shape ViewGroup.dispatchSetPressed produces: a clickable row pressed by the dispatch,
        // and its non-clickable label pressed only by propagation. Naming the label would report a
        // view that handled nothing — and would change with where in the row the finger landed.
        val label = TextView(context()).apply { isPressed = true }
        val row = LinearLayout(context()).apply {
            id = appOwnedId
            isClickable = true
            isPressed = true
            addView(label)
        }

        assertEquals(TapResolution.Target("fragment_container_view_tag"), resolveTapTarget(row))
    }

    @Test
    fun `names the inner one when clickables nest`() {
        val inner = Button(context()).apply {
            id = appOwnedId
            isPressed = true
        }
        // The outer one is NOT pressed: dispatchSetPressed does not propagate into a clickable child,
        // and the dispatch delivered to the child.
        val outer = LinearLayout(context()).apply {
            isClickable = true
            addView(inner)
        }

        assertEquals(TapResolution.Target("fragment_container_view_tag"), resolveTapTarget(outer))
    }

    @Test
    fun `resolves to nothing when no view is pressed`() {
        // A scroll, a cancelled press, a disabled control, a tap on Compose content: all reach here.
        val root = FrameLayout(context()).apply { addView(Button(context()).apply { id = appOwnedId }) }

        assertEquals(TapResolution.Unresolved, resolveTapTarget(root))
    }

    @Test
    fun `declines rather than guessing when two separate elements are pressed`() {
        val root = FrameLayout(context()).apply {
            addView(Button(context()).apply { id = appOwnedId; isPressed = true })
            addView(Button(context()).apply { id = android.R.id.button2; isPressed = true })
        }

        assertEquals(TapResolution.Ambiguous, resolveTapTarget(root))
    }

    @Test
    fun `reports nothing for a view with no id`() {
        val root = Button(context()).apply { isPressed = true }

        assertEquals(TapResolution.Unresolved, resolveTapTarget(root))
    }

    @Test
    fun `reports nothing for a generated id, which has no resource entry`() {
        // View.generateViewId() returns a valid, non-NO_ID value that getResourceEntryName THROWS on.
        // Checking NO_ID alone would let this through as an exception at tap time.
        val root = Button(context()).apply {
            id = View.generateViewId()
            isPressed = true
        }

        assertEquals(TapResolution.Unresolved, resolveTapTarget(root))
    }

    @Test
    fun `reports nothing for a platform id`() {
        // The ids inside platform layouts — every simple_list_item_1 row in the app shares this one —
        // name nothing in particular and collide across unrelated screens.
        val root = TextView(context()).apply {
            id = android.R.id.text1
            isPressed = true
        }

        assertEquals(TapResolution.Unresolved, resolveTapTarget(root))
    }

    @Test
    fun `never reports displayed text`() {
        val root = Button(context()).apply {
            text = "Buy now"
            contentDescription = "Buy now"
            id = appOwnedId
            isPressed = true
        }

        assertEquals(TapResolution.Target("fragment_container_view_tag"), resolveTapTarget(root))
    }

    // --- the capture itself -----------------------------------------------------------------------

    @Test
    fun `reports one event per touch-up, with the pressed view as target`() {
        val (activity, capture) = install()
        pressedButton(activity)

        activity.window.callback!!.dispatchTouchEvent(touchUp())

        assertEquals(listOf("Element Clicked" to "fragment_container_view_tag"), recorded)
        capture.uninstall()
    }

    @Test
    fun `a second capture in the same chain does not double-count`() {
        // The shape another SDK's Window.Callback wrapper produces: it wraps ours, a later resume
        // wraps that, and the one MotionEvent travels through two of our wrappers.
        val (activity, capture) = install()
        activity.window.callback = PassThrough(activity.window.callback!!)
        // A later foreground transition: the capture sees a callback that is no longer its own and
        // wraps again, leaving two of its wrappers in one chain.
        controller.pause().resume()
        Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable()
        pressedButton(activity)

        activity.window.callback!!.dispatchTouchEvent(touchUp())

        assertEquals(1, recorded.size)
        capture.uninstall()
    }

    @Test
    fun `re-wrapping retires the previous wrapper rather than leaving two live`() {
        // Pins the retire step on its own. Without this, only the integration test above covers it —
        // and the MotionEvent-identity guard masks the retire there, so removing the retire entirely
        // still passed (measured with a deliberate mutation). Two live wrappers would double-count
        // any pair of events the identity guard does not happen to collapse.
        val (activity, capture) = install()
        val first = activity.window.callback as TapWindowCallback
        activity.window.callback = PassThrough(first)
        controller.pause().resume()
        Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable()

        // The re-wrap happened at all (otherwise `first` would still be on top and still active)...
        assertTrue(activity.window.callback is TapWindowCallback)
        // ...and it retired the old one instead of leaving both reporting.
        assertTrue(!first.active)
        capture.uninstall()
    }

    @Test
    fun `uninstall stops reporting without unwrapping anyone`() {
        val (activity, capture) = install()
        val foreign = PassThrough(activity.window.callback!!)
        activity.window.callback = foreign
        pressedButton(activity)

        capture.uninstall()
        activity.window.callback!!.dispatchTouchEvent(touchUp())

        assertTrue(recorded.isEmpty())
        // Still the foreign wrapper: restoring what we captured would have uninstalled it.
        assertSame(foreign, activity.window.callback)
    }

    @Test
    fun `the diagnostic is spent at most once`() {
        assertTrue(warnOnceIfATapResolvedToNothing())
        assertTrue(!warnOnceIfATapResolvedToNothing())
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun context() = RuntimeEnvironment.getApplication()

    private lateinit var controller: ActivityController<TapHostActivity>

    private fun install(): Pair<Activity, AutographNativeTapCapture> {
        val capture = installAutographNativeTapCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = ScopeStack(),
        )
        controller = Robolectric.buildActivity(TapHostActivity::class.java).setup()
        Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable()
        return controller.get() to capture
    }

    /** Puts an already-pressed, identified button into the Activity's content view. */
    private fun pressedButton(activity: Activity) {
        val button = Button(activity).apply {
            id = appOwnedId
            isClickable = true
            isPressed = true
        }
        activity.setContentView(button)
    }

    private fun touchUp(): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1f, 1f, 0)
    }

    /** Stands in for another SDK's callback wrapper. */
    private class PassThrough(private val delegate: Window.Callback) : Window.Callback by delegate

}
