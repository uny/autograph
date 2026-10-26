package dev.ynagai.autograph.sample.android

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog as ComposeDialog
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.compose.Autocapture
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.AutographScope
import dev.ynagai.autograph.compose.TrackedScreen
import dev.ynagai.autograph.compose.autographAutocaptureRoot
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** How the fixture below is set up; written by each test before the Activity is built. */
object RootFixtures {
    lateinit var tracker: Tracker
    var autocapture = true
    var markDialog = true
    var markOwnWindow = false
    var clicks = 0
}

/**
 * A screen with a wrap-content `Dialog` up over it: the issue's `ModalBottomSheet` measurement in
 * its smallest form. Every alternate root (`Dialog`, `Popup`, a material sheet) composes into a view
 * of its own, which is what makes the provider's observer blind to it.
 */
class DialogOverScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutographProvider(RootFixtures.tracker, if (RootFixtures.autocapture) Autocapture() else null, ScopeStack()) {
                TrackedScreen("Main") {
                    AutographScope("filter" to "open") {
                        Box(
                            Modifier.fillMaxSize().testTag("own")
                                .then(if (RootFixtures.markOwnWindow) Modifier.autographAutocaptureRoot() else Modifier)
                                .clickable {},
                        )
                        ComposeDialog(onDismissRequest = {}) {
                            Box(
                                (if (RootFixtures.markDialog) Modifier.autographAutocaptureRoot() else Modifier)
                                    .size(120.dp)
                                    .testTag("chip")
                                    .clickable { RootFixtures.clicks++ },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [autographAutocaptureRoot] (#210): taps inside an alternate root are captured with the enclosing
 * screen and scope, the root keeps its own size, and the provider's own window is not double-counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AutocaptureRootTest {

    private val taps = mutableListOf<Pair<String?, Map<String, JsonElement>>>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            taps += target to properties
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }

    @Test
    fun withoutTheModifierATapInsideTheDialogLandsButIsNotReported() {
        // The control the issue insisted on: the tap is delivered and consumed (the click ran), and
        // the provider's observer still never sees it. Without this, "nothing reported" would read as
        // "nothing tapped".
        val activity = launch(markDialog = false)

        tap(dialogContent())
        assertEquals(1, RootFixtures.clicks)
        assertEquals(emptyList<Pair<String?, Map<String, JsonElement>>>(), taps)

        tap(ownContent(activity))
        assertEquals("the provider's own window is still observed", "own", taps.single().first)
    }

    @Test
    fun withTheModifierATapInsideTheDialogIsReportedWithTheScreenAndScopeAroundIt() {
        launch(markDialog = true)

        tap(dialogContent())
        assertEquals(1, RootFixtures.clicks)
        val (target, properties) = taps.single()
        assertEquals("chip", target)
        assertEquals("Main", properties["screen"]?.jsonPrimitive?.content)
        assertEquals("open", properties["filter"]?.jsonPrimitive?.content)
    }

    @Test
    fun theModifierDoesNotChangeTheDialogsSize() {
        // A nested AutographProvider stretched a wrap-content sheet to the window (its observer is a
        // fillMaxSize box); this observer sits on the node it is applied to.
        launch(markDialog = false)
        val unmarked = dialogContent().let { it.width to it.height }
        ShadowDialog.getLatestDialog().dismiss()

        launch(markDialog = true)
        val marked = dialogContent().let { it.width to it.height }

        val expected = (120 * RuntimeEnvironment.getApplication().resources.displayMetrics.density).toInt()
        assertEquals(expected to expected, unmarked)
        assertEquals(unmarked, marked)
    }

    @Test
    fun inTheProvidersOwnWindowItIsANoOpRatherThanASecondReport() {
        val activity = launch(markDialog = false, markOwnWindow = true)

        tap(ownContent(activity))
        assertEquals(listOf<String?>("own"), taps.map { it.first })
    }

    @Test
    fun withoutAutocaptureOnTheProviderItReportsNothing() {
        launch(markDialog = true, autocapture = false)

        tap(dialogContent())
        assertEquals(1, RootFixtures.clicks)
        assertEquals(emptyList<Pair<String?, Map<String, JsonElement>>>(), taps)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun launch(markDialog: Boolean, markOwnWindow: Boolean = false, autocapture: Boolean = true): ComponentActivity {
        RootFixtures.tracker = tracker
        RootFixtures.autocapture = autocapture
        RootFixtures.markDialog = markDialog
        RootFixtures.markOwnWindow = markOwnWindow
        RootFixtures.clicks = 0
        val activity = Robolectric.buildActivity(DialogOverScreenActivity::class.java).setup().get()
        idle()
        return activity
    }

    /** The view the Dialog's content composes into: the one Compose view in the dialog's window. */
    private fun dialogContent(): View {
        val dialog: Dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "no dialog is showing" }
        return checkNotNull(findComposeView(dialog.window!!.decorView)) { "no Compose view in the dialog" }
    }

    private fun ownContent(activity: ComponentActivity): View =
        checkNotNull(findComposeView(activity.window.decorView))

    private fun findComposeView(view: View): View? {
        if (view is AbstractComposeView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findComposeView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun idle() = Shadows.shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()

    private var gestures = 0

    /** A down/up pair near the top-left of [view], as a distinct gesture each time. */
    private fun tap(view: View) {
        val downTime = SystemClock.uptimeMillis() + 1000L * ++gestures
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 10, MotionEvent.ACTION_UP, 5f, 5f, 0))
        idle()
        assertTrue("a tap at (5, 5) is inside the view", view.width > 5 && view.height > 5)
    }
}
