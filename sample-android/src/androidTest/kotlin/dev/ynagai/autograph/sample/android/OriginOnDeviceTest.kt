@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.sample.android

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.android.AutographNativeScreenCapture
import dev.ynagai.autograph.android.AutographNativeTapCapture
import dev.ynagai.autograph.android.installAutographNativeScreenCapture
import dev.ynagai.autograph.android.installAutographNativeTapCapture
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device half of `ComposeTapOriginTest` (#216): the cases whose outcome depends on framework
 * behaviour Robolectric does not run — a real `ViewPager2` demoting its neighbours, real fragment
 * lifecycle ordering, real touch dispatch through `Window.Callback`. Every assertion is on the tap
 * PAYLOAD the tracker receives (which `screen` a real Espresso click carried), except the ambient
 * reads in [realPagerAttributesTheVisiblePage] and [ambientWhileHostPausedComposeDeclared] that pin
 * what #231 changed about the ambient snapshot.
 *
 * It runs in the sample's process, beside [NativeSampleApplication]'s own captures. Those would claim
 * every fixture Activity first, on a stack this test cannot see, so they are stood down for the
 * duration of each test and restored afterwards — see [standDownSampleCaptures].
 */
@RunWith(AndroidJUnit4::class)
class OriginOnDeviceTest {

    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            synchronized(Rig.taps) { Rig.taps += target to properties }
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }
    private var screens: AutographNativeScreenCapture? = null
    private var nativeTaps: AutographNativeTapCapture? = null
    private var scenario: ActivityScenario<out FragmentActivity>? = null

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NativeSampleApplication

    @Before
    fun standDownSampleCaptures() {
        // Install/uninstall are main-thread contracts; JUnit's fixtures run on the instrumentation thread.
        instrumentation.runOnMainSync { app.uninstallCaptures() }
        Rig.tracker = tracker
        Rig.scopeStack = ScopeStack()
        Rig.taps.clear()
    }

    @After
    fun tearDown() {
        // Close the fixture before the sample's captures come back: they must not see a Paused /
        // Destroyed for an Activity whose Created they never saw.
        // In a finally: a close() that throws must not leave the sample's captures uninstalled for
        // every test that follows in this process.
        try {
            scenario?.close()
        } finally {
            scenario = null
            instrumentation.runOnMainSync {
                screens?.uninstall()
                nativeTaps?.uninstall()
                screens = null
                nativeTaps = null
                app.installCaptures()
            }
        }
    }

    private fun installCaptures() = instrumentation.runOnMainSync {
        screens = installAutographNativeScreenCapture(
            application = app, tracker = tracker, scopeStack = Rig.scopeStack,
            activityScreenName = { it.javaClass.simpleName }, fragmentScreenName = { it.javaClass.simpleName },
        )
        nativeTaps = installAutographNativeTapCapture(app, tracker, Rig.scopeStack)
    }

    private fun <A : FragmentActivity> launchFixture(activity: Class<A>): ActivityScenario<A> =
        ActivityScenario.launch(activity).also { scenario = it }

    private fun launch(install: Boolean = true): ActivityScenario<OriginActivity> {
        if (install) installCaptures()
        return launchFixture(OriginActivity::class.java)
    }

    private fun add(s: ActivityScenario<OriginActivity>, containerId: Int, f: Fragment) {
        s.onActivity { it.supportFragmentManager.beginTransaction().add(containerId, f).commitNow() }
        idle()
    }

    private fun idle() = instrumentation.waitForIdleSync()

    /** Polls [condition] for up to [timeoutMs]; the caller asserts afterwards, so a timeout is not hidden. */
    private fun awaitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun awaitAmbientScreen(expected: String?) {
        idle()
        awaitUntil { Rig.scopeStack.current().screen == expected }
        assertEquals(expected, Rig.scopeStack.current().screen)
    }

    private fun tapAndRead(id: Int): Pair<String?, String?> {
        synchronized(Rig.taps) { Rig.taps.clear() }
        onView(withId(id)).perform(click())
        idle()
        awaitUntil { synchronized(Rig.taps) { Rig.taps.isNotEmpty() } }
        // A settle after the first report, so a double report of the same tap is still caught.
        Thread.sleep(200)
        val all = synchronized(Rig.taps) { Rig.taps.toList() }
        assertEquals("exactly one report for one tap: $all", 1, all.size)
        val (target, props) = all.single()
        return target to props["screen"]?.jsonPrimitive?.content
    }

    // 1. Mask raised by an excluded (silent) fragment does not reach the Activity's own composition.
    @Test
    fun maskFromSilentFragmentStaysInsideIt() {
        val s = launch()
        add(s, Rig.LEFT, SilentFragment())
        awaitAmbientScreen(null)  // the mask IS up: without this, "own carries Main" would also pass if it never rose
        assertEquals("own" to "Main", tapAndRead(Rig.OWN))
        assertEquals("silent" to null, tapAndRead(Rig.LEFT))
    }

    // 2. Two sibling pages, B added after A: a tap on A must not carry PageB.
    @Test
    fun siblingPageDeclaredLaterDoesNotLeak() {
        val s = launch()
        add(s, Rig.LEFT, PageA())
        add(s, Rig.RIGHT, PageB())
        assertEquals("a" to "ScreenA", tapAndRead(Rig.LEFT))
        assertEquals("b" to "ScreenB", tapAndRead(Rig.RIGHT))
    }

    // 3. Native interop button inside a TrackedScreen composition carries the declared screen; the
    //    Activity's own native chrome carries the screen its composition declares, despite a mask beside it.
    @Test
    fun interopAndChromeCarryTheDeclaredScreens() {
        val s = launch()
        add(s, Rig.LEFT, InteropFragment2())
        add(s, Rig.RIGHT, SilentFragment())
        awaitAmbientScreen(null)  // the sibling mask IS up
        assertEquals("Detail", tapAndRead(Rig.INTEROP).second)
        assertEquals("Main", tapAndRead(Rig.CHROME).second)
    }

    // 4. Late install: compositions that predate the capture are re-linked when the Activity is adopted.
    @Test
    fun lateInstallRelinksCompositions() {
        val s = launch(install = false)
        add(s, Rig.LEFT, PageA())
        add(s, Rig.RIGHT, PageB())
        installCaptures()
        s.moveToState(Lifecycle.State.STARTED)
        s.moveToState(Lifecycle.State.RESUMED)
        idle()
        assertEquals("a" to "ScreenA", tapAndRead(Rig.LEFT))
        assertEquals("own" to "Main", tapAndRead(Rig.OWN))
    }

    // 5. A NavHost route beside the Activity's own composition stays local to its surface.
    @Test
    fun navHostRouteStaysOnItsSurface() {
        val s = launch()
        add(s, Rig.LEFT, NavFragment())
        assertEquals("own" to "Main", tapAndRead(Rig.OWN))
        assertEquals("nav" to "home", tapAndRead(Rig.LEFT))
    }

    // 6. REAL ViewPager2: swipe A -> B -> C -> back. With offscreenPageLimit=1 the neighbour stays
    //    mounted and demoted; a tap on the page on display must carry ITS screen, not the neighbour's,
    //    and (#231) the ambient screen must follow the display the same way.
    @Test
    fun realPagerAttributesTheVisiblePage() {
        installCaptures()
        val s = launchFixture(PagerActivity::class.java)
        awaitAmbientScreen("ScreenA")
        fun goTo(page: Int) {
            s.onActivity { it.findViewById<ViewPager2>(Rig.PAGER).setCurrentItem(page, false) }
            awaitAmbientScreen("Screen" + "ABC"[page])
        }
        assertEquals("a" to "ScreenA", tapAndRead(Rig.PAGER))
        goTo(1); assertEquals("b" to "ScreenB", tapAndRead(Rig.PAGER))
        goTo(2); assertEquals("c" to "ScreenC", tapAndRead(Rig.PAGER))
        goTo(1); assertEquals("b" to "ScreenB", tapAndRead(Rig.PAGER))
        goTo(0); assertEquals("a" to "ScreenA", tapAndRead(Rig.PAGER))
    }

    // 7. Ambient snapshot while the host Activity is paused, for a screen declared in COMPOSE (#231):
    //    the declaration leaves the ambient with its host and comes back with it.
    @Test
    fun ambientWhileHostPausedComposeDeclared() {
        val s = launch()
        awaitAmbientScreen("Main")
        s.moveToState(Lifecycle.State.STARTED)
        awaitAmbientScreen(null)
        s.moveToState(Lifecycle.State.RESUMED)
        awaitAmbientScreen("Main")
    }
}
