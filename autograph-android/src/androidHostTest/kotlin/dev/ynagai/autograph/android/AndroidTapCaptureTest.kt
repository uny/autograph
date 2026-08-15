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

    /**
     * A non-platform id, which is all `developerSetIdentifier` can actually distinguish. That this is
     * a *library* id and is nonetheless reported is not an accident of the fixture — it is the
     * behaviour: AAR resources merge into the application package, so only the `android` package
     * stays separable at runtime. See `developerSetIdentifier`'s kdoc.
     */
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

    // --- isAutographIgnored -----------------------------------------------------------------------

    @Test
    fun `an ignored view reports nothing`() {
        val root = Button(context()).apply {
            id = appOwnedId
            isPressed = true
            isAutographIgnored = true
        }

        assertEquals(TapResolution.Ignored, resolveTapTarget(root))
    }

    @Test
    fun `ignoring a container excludes the clickables inside it`() {
        // The point of the marker: excluding a region must not require finding every clickable in it.
        val button = Button(context()).apply {
            id = appOwnedId
            isPressed = true
        }
        val section = FrameLayout(context()).apply {
            isAutographIgnored = true
            addView(button)
        }
        val root = FrameLayout(context()).apply { addView(section) }

        assertEquals(TapResolution.Ignored, resolveTapTarget(root))
    }

    @Test
    fun `ignoring one subtree leaves its siblings reporting`() {
        // What this actually pins is that the check walks *ancestors* and not the whole tree: an
        // implementation that looked for any mark anywhere under the root would fail here. Deleting
        // the exclusion outright does not — a sibling mark is unreachable from the walk by
        // construction, which is the property being asserted.
        val root = FrameLayout(context()).apply {
            addView(FrameLayout(context()).apply { isAutographIgnored = true })
            addView(Button(context()).apply { id = appOwnedId; isPressed = true })
        }

        assertEquals(TapResolution.Target("fragment_container_view_tag"), resolveTapTarget(root))
    }

    @Test
    fun `the mark can be taken back, because RecyclerView recycles views`() {
        // A ViewHolder binding a mixed list writes `false` as often as `true`; a one-way mark would
        // leak the exclusion onto whatever row inherited the view.
        val root = Button(context()).apply {
            id = appOwnedId
            isPressed = true
            isAutographIgnored = true
        }
        root.isAutographIgnored = false

        assertTrue(!root.isAutographIgnored)
        assertEquals(TapResolution.Target("fragment_container_view_tag"), resolveTapTarget(root))
    }

    @Test
    fun `an ignored view with no id is Ignored, not Unresolved`() {
        // Which is what keeps it from spending the one-shot diagnostic: the app excluded this region
        // deliberately, and telling it that a tap "resolved to nothing" would blame an integration
        // that is fine, and then never print the line for the integration that isn't.
        val root = Button(context()).apply {
            isPressed = true
            isAutographIgnored = true
        }

        assertEquals(TapResolution.Ignored, resolveTapTarget(root))
    }

    @Test
    fun `the marker does not disturb the view's own tag`() {
        // It is stored under a resource id private to this library, so an app using View.setTag for
        // its own purposes — the common case — is unaffected in both directions.
        val root = Button(context()).apply {
            tag = "the app's own tag"
            isAutographIgnored = true
        }

        assertEquals("the app's own tag", root.tag)
        assertTrue(root.isAutographIgnored)
    }

    @Test
    fun `an ignored tap sends no event and spends no diagnostic`() {
        val (activity, capture) = install()
        pressedButton(activity)
        activity.findViewById<View>(appOwnedId).isAutographIgnored = true

        activity.window.callback!!.dispatchTouchEvent(touchUp())

        assertTrue(recorded.isEmpty())
        assertTrue(!warnedATapResolvedToNothing)
        capture.uninstall()
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
    fun `one gesture reports once, however many pointers lift in it`() {
        // The shape a stray second finger produces, and the reason the gesture guard is keyed on
        // downTime rather than on each touch-up: the pressed state is global to the hierarchy, not
        // per pointer, so the ACTION_POINTER_UP a resting finger lifts with resolves to the button
        // the *other* finger is still holding — and then the real ACTION_UP resolves to it again.
        // Reporting per touch-up sent that one press twice.
        val (activity, capture) = install()
        pressedButton(activity)
        val gesture = SystemClock.uptimeMillis()

        activity.window.callback!!.dispatchTouchEvent(pointerUpIn(gesture, gesture + 10))
        activity.window.callback!!.dispatchTouchEvent(upIn(gesture, gesture + 20))

        assertEquals(listOf("Element Clicked" to "fragment_container_view_tag"), recorded)
        capture.uninstall()
    }

    @Test
    fun `the next gesture is still reported`() {
        // The guard must collapse one gesture, not silence everything after the first tap.
        val (activity, capture) = install()
        pressedButton(activity)
        val first = SystemClock.uptimeMillis()

        activity.window.callback!!.dispatchTouchEvent(upIn(first, first))
        activity.window.callback!!.dispatchTouchEvent(upIn(first + 500, first + 500))

        assertEquals(2, recorded.size)
        capture.uninstall()
    }

    @Test
    fun `a touch-up that resolved to nothing leaves the gesture reportable`() {
        // Why the guard is spent by a report rather than by a touch-up: a first pointer lifting off
        // Compose content, or off a view with no id, must not consume the gesture and silence the
        // real tap still to come in it.
        val (activity, capture) = install()
        activity.setContentView(Button(activity).apply { id = appOwnedId }) // present, but not pressed
        val gesture = SystemClock.uptimeMillis()

        activity.window.callback!!.dispatchTouchEvent(pointerUpIn(gesture, gesture + 10))
        pressedButton(activity)
        activity.window.callback!!.dispatchTouchEvent(upIn(gesture, gesture + 20))

        assertEquals(listOf("Element Clicked" to "fragment_container_view_tag"), recorded)
        capture.uninstall()
    }

    @Test
    fun `a touch-up on an excluded view leaves the gesture reportable`() {
        // The same claim as the test above, for the other resolution that sends nothing: an excluded
        // view a stray finger happens to lift from must not consume the gesture that the real tap, on
        // a view the app did not exclude, is still going to report. Without this, folding `Ignored`
        // into the reporting branch passes every other test in this file.
        val (activity, capture) = install()
        activity.setContentView(
            Button(activity).apply {
                id = appOwnedId
                isPressed = true
                isAutographIgnored = true
            },
        )
        val gesture = SystemClock.uptimeMillis()

        activity.window.callback!!.dispatchTouchEvent(pointerUpIn(gesture, gesture + 10))
        pressedButton(activity)
        activity.window.callback!!.dispatchTouchEvent(upIn(gesture, gesture + 20))

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
        // and the per-gesture guard masks the retire there, so removing the retire entirely still
        // passed (measured with a deliberate mutation). Two live wrappers would double-count any pair
        // of events that guard does not happen to collapse.
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

    /** The final pointer of the gesture that began at [downTime] lifting. */
    private fun upIn(downTime: Long, eventTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, 1f, 1f, 0)

    /**
     * A non-final pointer of the gesture that began at [downTime] lifting. The capture reads only
     * `actionMasked` and `downTime`, so carrying the pointer-up action on a one-pointer event is an
     * accurate stand-in and avoids depending on how faithfully Robolectric models a real two-pointer
     * MotionEvent.
     */
    private fun pointerUpIn(downTime: Long, eventTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_POINTER_UP, 1f, 1f, 0)

    /** Stands in for another SDK's callback wrapper. */
    private class PassThrough(private val delegate: Window.Callback) : Window.Callback by delegate

}
