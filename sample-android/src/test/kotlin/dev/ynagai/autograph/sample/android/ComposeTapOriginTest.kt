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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.android.installAutographNativeScreenCapture
import dev.ynagai.autograph.android.installAutographNativeTapCapture
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.AutographScope
import dev.ynagai.autograph.compose.TrackScreenViews
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
    /** Native chrome beside the composition — a bottom-navigation item, say. */
    lateinit var chrome: android.widget.Button
    val container = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownCompose = ComposeView(this).apply { setContent { DeclaringTappable("Main", "own") } }
        chrome = android.widget.Button(this).apply { id = androidx.fragment.R.id.fragment_container_view_tag; isClickable = true }
        setContentView(
            FrameLayout(this).apply {
                addView(ownCompose)
                addView(chrome)
                addView(FrameLayout(context).apply { id = container })
            },
        )
    }
}

/** Two sibling providers in ONE ComposeView, each declaring a screen around an interop button. */
class SiblingProvidersFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                androidx.compose.foundation.layout.Column {
                    AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                        TrackedScreen("A") { InteropButton { OriginFixtures.interopButton = it } }
                    }
                    AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                        TrackedScreen("B") { InteropButton { OriginFixtures.secondInteropButton = it } }
                    }
                }
            }
        }
}

/** A provider nested inside another's composition, each with its own tracker. */
class NestedProvidersFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                    TrackedScreen("Outer") {
                        AutographProvider(OriginFixtures.innerTracker!!, AutocaptureConfig(), OriginFixtures.scopeStack) {
                            TrackedScreen("Inner") { Box(Modifier.fillMaxSize().testTag("inner").clickable {}) {} }
                        }
                    }
                }
            }
        }
}

@Composable
private fun InteropButton(onCreated: (android.widget.Button) -> Unit) {
    AndroidView(factory = { context ->
        android.widget.Button(context).apply {
            id = androidx.fragment.R.id.fragment_container_view_tag
            isClickable = true
            onCreated(this)
        }
    })
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

/** Hosts a composition that declares `Article` and wraps its tappable in an `AutographScope`. */
class ScopedTapFragment : Fragment() {
    lateinit var compose: ComposeView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                    TrackedScreen("Article") {
                        AutographScope("article_id" to "1") { Box(Modifier.fillMaxSize().testTag("scoped").clickable {}) {} }
                    }
                }
            }
        }.also { compose = it }
}

/**
 * Hosts a composition that declares `Detail` around an `AndroidView` interop button — real View
 * content, which the **native** tap capture reports (see `RegisterComposeHostForNativeCapture`).
 */
class InteropFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                    TrackedScreen("Detail") {
                        AndroidView(factory = { context ->
                            android.widget.Button(context).apply {
                                id = androidx.fragment.R.id.fragment_container_view_tag
                                isClickable = true
                                OriginFixtures.interopButton = this
                            }
                        })
                    }
                }
            }
        }
}

/** Hosts a NavHost whose destinations declare nothing — the route frame is the only declaration. */
class NavHostFragment2 : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(OriginFixtures.tracker, AutocaptureConfig(), OriginFixtures.scopeStack) {
                    val navController = rememberNavController()
                    navController.TrackScreenViews()
                    NavHost(navController, startDestination = "home") {
                        composable("home") {
                            Box(Modifier.fillMaxSize().testTag("nav").clickable {}) {}
                        }
                    }
                }
            }
        }
}

class PageAFragment : DeclaringTapFragment("PageA", "a")
class PageBFragment : DeclaringTapFragment("PageB", "b")
class DeclaringInsideMaskFragment : DeclaringTapFragment("ComposeScreen", "declared")

/** The fragments compose before the test can hand them anything, so the shared state lives here. */
object OriginFixtures {
    lateinit var tracker: Tracker
    lateinit var scopeStack: ScopeStack
    var innerTracker: Tracker? = null
    var interopButton: android.widget.Button? = null
    var secondInteropButton: android.widget.Button? = null
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
// A plain Application: the sample's own installs a second pair of captures on a stack of its own,
// which claims every view first — measured, a late-install test then passed for the wrong reason
// (the composition had linked under the OTHER stack's frame).
@Config(sdk = [36], application = android.app.Application::class)
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
    private var nativeTaps: dev.ynagai.autograph.android.AutographNativeTapCapture? = null

    @After
    fun tearDown() {
        screens?.uninstall()
        nativeTaps?.uninstall()
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

    @Test
    fun aNativeTapOnInteropContentInsideACompositionCarriesTheScreenDeclaredAroundIt() {
        // An AndroidView inside a TrackedScreen is View content: the NATIVE tap capture reports it,
        // resolving from the Compose host fragment around it — a masked surface. The descent from
        // the fragment must reach the composition's declaration, i.e. the provider's frame must not
        // be a boundary; when it was one the tap carried no screen (measured).
        val activity = launch()
        run {
            val host = InteropFragment()
            activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
            idle()
            nativeTap(activity, OriginFixtures.interopButton!!)
            assertEquals("fragment_container_view_tag", taps.single().first)
            assertEquals("Detail", taps.single().second["screen"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun aMaskedShellActivitysOwnNativeChromeCarriesTheScreenItsCompositionDeclares() {
        // The Activity hosts Compose, so the native capture masks it; its composition declares the
        // screen FOR it. A native tap on chrome beside the ComposeView resolves from the Activity,
        // and the composition's frame — not a boundary — is on the way down, so the tap carries the
        // declared screen, as it did ambiently. It also must not be blanked by a sibling mask.
        val activity = launch()
        val mini = SilentTapFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, mini).commitNow()
        idle()
        assertNull(scopeStack.current().screen)

        nativeTap(activity, activity.chrome)
        assertEquals("Main", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun siblingProvidersInOneComposeViewResolveInteropTapsAsTheHostDoes() {
        // Two providers share one host view; neither can claim it for itself without mis-attributing
        // the other's interop taps, so both resolve from the fragment, whose descent reaches both
        // declarations and insertion order decides — the ambient rule, and the honest limit: the
        // FIRST provider's interop button carries "B" here, exactly as it did before origins
        // existed. What this pins is that the two buttons agree (a per-provider claim on the shared
        // view gave the same wrong answer for a different reason, and disposing the second provider
        // then dropped the first back to the masked host); the provider-boundary mutant blanks both.
        val activity = launch()
        val host = SiblingProvidersFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
        idle()

        nativeTap(activity, OriginFixtures.interopButton!!)
        val first = taps.single().second["screen"]?.jsonPrimitive?.content
        taps.clear()
        nativeTap(activity, OriginFixtures.secondInteropButton!!)
        val second = taps.single().second["screen"]?.jsonPrimitive?.content
        assertEquals("both interop taps resolve from the shared host: same answer", first, second)
        assertEquals("B", second)
    }

    @Test
    fun aNestedProvidersDeclarationStaysVisibleToTheOuterProvidersTap() {
        // The outer observer cannot localize a tap out of a nested provider's subtree, so the nested
        // frame must not be a boundary: a tap on the inner content, reported by BOTH observers,
        // carries "Inner" on both — the ambient answer — never "Outer" for the outer tracker.
        val innerTaps = mutableListOf<Map<String, JsonElement>>()
        OriginFixtures.innerTracker = object : Tracker {
            override fun track(name: String, properties: Map<String, JsonElement>, target: String?) { innerTaps += properties }
            override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
            override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
        }
        val activity = launch()
        val host = NestedProvidersFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
        idle()
        val compose = host.view as ComposeView

        tap(compose)
        assertEquals("Inner", taps.single().second["screen"]?.jsonPrimitive?.content)
        assertEquals("Inner", innerTaps.single()["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aCompositionThatPredatesTheInstallIsRelinkedWhenItsSurfaceIsClaimed() {
        // Late install, two Compose pages already composed and never tapped. When the capture
        // adopts the Activity and claims the pages' views, each composition must re-link under its
        // page — an unlinked root is under no boundary and would lend "PageB" to a tap on A.
        val a = PageAFragment()
        val b = PageBFragment()
        val activity = launch(install = false)
        activity.supportFragmentManager.beginTransaction().add(activity.container, a).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.container, b).commitNow()
        idle()
        installCaptures()
        controller.pause().resume()
        idle()

        tap(a.compose)
        assertEquals("PageA", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun everyOutermostProviderInOneComposeViewIsRelinkedOnALateInstall() {
        // Two outermost providers share one host view, so they share one listener registration
        // point. A single slot let the second overwrite the first; the first then stayed a root on a
        // late install — global — and its "A" leaked into a tap on the Activity's own composition.
        val activity = launch(install = false)
        val host = SiblingProvidersFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
        idle()
        installCaptures()
        controller.pause().resume()
        idle()

        tap(activity.ownCompose)
        assertEquals("Main", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aFrameTheAppPushedByHandReachesEveryTapWhileTheNativeCaptureIsInstalled() {
        // An app-wide frame pushed through the public API — an experiment scope at startup — is
        // under no boundary, so every origin sees it. Dropping it once the capture claims the view
        // tree would be silent data loss; both pipelines are checked.
        val activity = launch()
        scopeStack.pushGlobal(scope = mapOf("experiment" to kotlinx.serialization.json.JsonPrimitive("b")))
        val host = InteropFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.container, host).commitNow()
        idle()

        tap(activity.ownCompose)
        assertEquals("b", taps.single().second["experiment"]?.jsonPrimitive?.content)
        taps.clear()
        nativeTap(activity, OriginFixtures.interopButton!!)
        assertEquals("b", taps.single().second["experiment"]?.jsonPrimitive?.content)
        assertEquals("Detail", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    @Test
    fun aGlobalFrameCoexistsWithAScreensOwnAutographScope() {
        // #237: the test above held only because nothing else on the stack carried scope. With a
        // screen wrapped in `AutographScope`, a plain hand-pushed root was an ambiguous sibling of
        // the scope nested under the provider, and BOTH dropped — adding app-wide context removed
        // the screen scope the app already had. A global frame merges instead: the Compose tap
        // carries both keys, the native tap (on a surface with no scope of its own) the global one.
        val activity = launch()
        val scoped = ScopedTapFragment()
        val interop = InteropFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(activity.container, scoped).add(activity.container, interop).commitNow()
        idle()
        scopeStack.pushGlobal(scope = mapOf("tenant_id" to kotlinx.serialization.json.JsonPrimitive("acme")))

        tap(scoped.compose)
        assertEquals("scoped", taps.single().first)
        assertEquals("Article", taps.single().second["screen"]?.jsonPrimitive?.content)
        assertEquals("1", taps.single().second["article_id"]?.jsonPrimitive?.content)
        assertEquals("acme", taps.single().second["tenant_id"]?.jsonPrimitive?.content)

        taps.clear()
        nativeTap(activity, OriginFixtures.interopButton!!)
        assertEquals("Detail", taps.single().second["screen"]?.jsonPrimitive?.content)
        assertEquals("acme", taps.single().second["tenant_id"]?.jsonPrimitive?.content)
        assertNull("another surface's AutographScope never reaches a native tap", taps.single().second["article_id"])
    }

    @Test
    fun aNavHostsRouteDoesNotAttributeTapsOnAnotherSurface() {
        // The route frame is nested under its provider, which is nested under the fragment hosting
        // it. Drop that parent link and the route frame is under no boundary — global — so it lends
        // its destination name to a tap on the Activity's own composition beside it.
        val activity = launch()
        val nav = NavHostFragment2()
        activity.supportFragmentManager.beginTransaction().add(activity.container, nav).commitNow()
        idle()
        assertEquals("the route is ambiently innermost", "home", scopeStack.current().screen)

        tap(activity.ownCompose)
        assertEquals("Main", taps.single().second["screen"]?.jsonPrimitive?.content)

        taps.clear()
        tap(nav.view as ComposeView)
        assertEquals("home", taps.single().second["screen"]?.jsonPrimitive?.content)
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** A pressed native [button] lifting, through the window callback the native capture wraps. */
    private fun nativeTap(activity: FragmentActivity, button: android.widget.Button) {
        button.isPressed = true
        val downTime = SystemClock.uptimeMillis() + 1000L * ++gestures
        activity.window.callback!!.dispatchTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_UP, 1f, 1f, 0),
        )
        button.isPressed = false
        assertTrue("the tap was reported", taps.isNotEmpty())
    }

    private lateinit var controller: org.robolectric.android.controller.ActivityController<OwnComposeActivity>

    private fun installCaptures() {
        screens = installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { it.javaClass.simpleName },
        )
        nativeTaps = installAutographNativeTapCapture(RuntimeEnvironment.getApplication(), tracker, scopeStack)
    }

    private fun launch(install: Boolean = true): OwnComposeActivity {
        OriginFixtures.tracker = tracker
        OriginFixtures.scopeStack = scopeStack
        // Both native pipelines, as a hybrid app runs them; the tap capture wraps a window at resume,
        // so it has to be in place before the Activity is — except when a test installs late.
        if (install) installCaptures()
        controller = Robolectric.buildActivity(OwnComposeActivity::class.java).setup()
        val activity = controller.get()
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
