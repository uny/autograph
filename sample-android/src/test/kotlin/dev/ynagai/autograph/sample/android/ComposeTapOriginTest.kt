@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.sample.android

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.android.installAutographNativeScreenCapture
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.TrackedScreen
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
import org.robolectric.annotation.Config

// Fragment fixtures must be public top-level classes: the framework recreates them reflectively.

/** One provider-rooted composition with a single tappable element filling it. */
@Composable
private fun Tappable(tag: String, content: @Composable () -> Unit = {}) {
    AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
        content()
        Box(Modifier.fillMaxSize().testTag(tag).clickable {}) {}
    }
}

/** A composition that declares [screen] around its tappable. */
@Composable
private fun DeclaringTappable(screen: String, tag: String) {
    AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
        TrackedScreen(screen) { Box(Modifier.fillMaxSize().testTag(tag).clickable {}) {} }
    }
}

/**
 * An Activity whose own content is a ComposeView declaring `Main`, beside an empty container a
 * mini-player fragment can be added into. This is the #216 S2 shape seen from the Compose side: the
 * Activity is a Compose host (so the native capture masks it and its composition names the screen),
 * and its composition sits *beside* the excluded fragment, not inside it.
 */
class OwnComposeActivity : FragmentActivity() {
    lateinit var ownCompose: ComposeView
    val container = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownCompose = ComposeView(this).apply { setContent { DeclaringTappable("Main", "own") } }
        setContentView(
            FrameLayout(this).apply {
                addView(ownCompose)
                addView(FrameLayout(context).apply { id = container })
            },
        )
    }
}

/** Hosts a composition that declares nothing — the mask-raising shape. */
class SilentTapFragment : Fragment() {
    lateinit var compose: ComposeView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { Tappable("silent") } }.also { compose = it }
}

/** Hosts a composition that declares [screen]. */
open class DeclaringTapFragment(private val screen: String, private val tag: String) : Fragment() {
    lateinit var compose: ComposeView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val screen = this.screen
        val tag = this.tag
        return ComposeView(requireContext()).apply { setContent { DeclaringTappable(screen, tag) } }.also { compose = it }
    }
}

class PageAFragment : DeclaringTapFragment("PageA", "a")
class PageBFragment : DeclaringTapFragment("PageB", "b")
class DeclaringInsideMaskFragment : DeclaringTapFragment("ComposeScreen", "declared")

/** The fragments compose before the test can hand them anything, so the shared state lives here. */
object OriginFixtures {
    lateinit var tracker: Tracker
    lateinit var scopeStack: ScopeStack
}

/**
 * What a **Compose** tap carries, on one [ScopeStack] shared with the native screen capture, as
 * seen in the tap payload the tracker receives. The counterpart of `autograph-android`'s
 * `AndroidTapOriginTest`, and the half that module cannot run — a real `AutographProvider`
 * composition needs the Compose compiler. Every assertion here is on a tap payload: the ambient
 * snapshot is exactly what these taps must NOT be attributed by (`ComposeHostMaskTest`'s tracker
 * dropped `track`, and was structurally blind to this).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeTapOriginTest {

    private val taps = mutableListOf<Pair<String?, Map<String, JsonElement>>>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            taps += target to properties
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }
    private val scopeStack = ScopeStack()
    private var screens: dev.ynagai.autograph.android.AutographNativeScreenCapture? = null

    @After
    fun tearDown() {
        screens?.uninstall()
    }

    @Test
    fun aMaskRaisedByAnExcludedFragmentDoesNotReachTheActivitysOwnComposition() {
        val activity = launch()
        val mini = SilentTapFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, mini).commitNow()
        idle()
        assertNull("the mask is up ambiently", scopeStack.current().screen)
        assertTrue(scopeStack.current().screenMasked)

        tap(activity.ownCompose)
        assertEquals("own", taps.single().first)
        assertEquals("Main", taps.single().second["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(mini.compose)
        assertEquals("silent", taps.single().first)
        assertNull("inside the excluded fragment the tap carries no screen", taps.single().second["screen"])
    }

    @Test
    fun aScreenDeclaredInsideAMaskedHostCrossesThatHostsMask() {
        val activity = launch()
        val host = DeclaringInsideMaskFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
        idle()

        tap(host.compose)
        assertEquals("declared", taps.single().first)
        assertEquals("ComposeScreen", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aTapOnOnePageDoesNotCarryTheScreenASiblingPageDeclaredLater() {
        // Two Compose pages, both attached and resumed, B after A — the pager shape, without the
        // demotion: the sibling is simply another surface, and its later frame must not win.
        val activity = launch()
        val a = PageAFragment()
        val b = PageBFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, a).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.container, b).commitNow()
        idle()
        assertEquals("ambiently the later page wins", "PageB", scopeStack.current().screen)

        tap(a.compose)
        assertEquals("a", taps.single().first)
        assertEquals("PageA", taps.single().second["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(b.compose)
        assertEquals("PageB", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun launch(): OwnComposeActivity {
        OriginFixtures.tracker = tracker
        OriginFixtures.scopeStack = scopeStack
        screens = installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { it.javaClass.simpleName },
        )
        val activity = Robolectric.buildActivity(OwnComposeActivity::class.java).setup().get()
        idle()
        assertEquals("the Activity's own composition names the screen", "Main", scopeStack.current().screen)
        return activity
    }

    private fun idle() = Shadows.shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()

    private var gestures = 0

    /** A down/up pair inside [view], as a distinct gesture each time. */
    private fun tap(view: View) {
        val downTime = SystemClock.uptimeMillis() + 1000L * ++gestures
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 10, MotionEvent.ACTION_UP, 5f, 5f, 0))
        idle()
        assertTrue("the tap was reported", taps.isNotEmpty())
    }
}
