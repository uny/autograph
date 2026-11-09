@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.context.autographScopeOrigin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The stack the fixtures below declare on; set by each test before anything is created. */
private lateinit var fixtureStack: ScopeStack

/** A fragment that declares its scope from `onCreate`, as the kdoc says to. */
class ScopedButtonFragment : ButtonFragment() {
    lateinit var scope: AutographScopeHandle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope = autographScope(fixtureStack, mapOf("item_id" to "42", "source" to "fragment"))
    }
}

/** A scoped fragment nesting a scoped child (with a button) in its own view. */
class ScopedNestingFragment : ButtonFragment() {
    private val childContainer = android.view.View.generateViewId()
    val child: ScopedButtonFragment? get() = childFragmentManager.findFragmentByTag("child") as ScopedButtonFragment?
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autographScope(fixtureStack, mapOf("list" to "home", "source" to "parent"))
    }
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): android.view.View =
        (super.onCreateView(inflater, container, savedInstanceState) as android.widget.FrameLayout).apply {
            addView(android.widget.FrameLayout(context).apply { id = childContainer })
        }
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        if (child == null) childFragmentManager.beginTransaction().add(childContainer, ScopedButtonFragment(), "child").commitNow()
    }
}

/** [OwnHostActivity] declaring a scope of its own. */
class ScopedHostActivity : OwnHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autographScope(fixtureStack, mapOf("variant" to "b", "source" to "activity"))
    }
}

/**
 * [autographScope] on a native surface (#239): the declared properties reach the events captured in
 * that surface and in what is nested in it, and nobody else's. Asserted on tap payloads, as in
 * [AndroidTapOriginTest], since a scope that is right ambiently can still be wrong on a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutographSurfaceScopeTest {

    private val taps = mutableListOf<Map<String, JsonElement>>()
    private val screens = mutableListOf<Pair<String, Map<String, JsonElement>>>()
    private val tracker = object : Tracker {
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
            taps += properties
        }
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            screens += name to properties
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }
    private val scopeStack = ScopeStack().also { fixtureStack = it }
    private var screenCapture: AutographNativeScreenCapture? = null
    private lateinit var tapCapture: AutographNativeTapCapture

    @After
    fun tearDown() {
        screenCapture?.uninstall()
        if (::tapCapture.isInitialized) tapCapture.uninstall()
        warnedATapResolvedToNothing = false
    }

    @Test
    fun aFragmentsScopeReachesItsOwnTapsAndNotTheActivitysOrASiblings() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        val sibling = ButtonFragmentA()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.containerB, sibling).commitNow()

        tap(activity, scoped.button)
        assertEquals("42", taps.single().string("item_id"))

        taps.clear()
        tap(activity, sibling.button)
        assertNull("a sibling fragment's tap", taps.single()["item_id"])

        taps.clear()
        tap(activity, activity.ownButton)
        assertNull("the Activity's own tap", taps.single()["item_id"])
    }

    @Test
    fun anActivitysScopeReachesItsFragmentsAndTheFragmentWinsAKeyClash() {
        val activity = launch(ScopedHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        val plain = ButtonFragmentA()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()
        activity.supportFragmentManager.beginTransaction().add(activity.containerB, plain).commitNow()

        tap(activity, activity.ownButton)
        assertEquals("b", taps.single().string("variant"))
        assertEquals("activity", taps.single().string("source"))

        taps.clear()
        tap(activity, scoped.button)
        assertEquals("b", taps.single().string("variant"))
        assertEquals("the inner surface wins", "fragment", taps.single().string("source"))
        assertEquals("42", taps.single().string("item_id"))

        taps.clear()
        tap(activity, plain.button)
        assertEquals("activity", taps.single().string("source"))
        assertNull(taps.single()["item_id"])
    }

    @Test
    fun aChildFragmentsScopeMergesOverItsParentFragments() {
        val activity = launch(OwnHostActivity::class.java).get()
        val parent = ScopedNestingFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, parent).commitNow()

        tap(activity, parent.child!!.button)
        assertEquals("home", taps.single().string("list"))
        assertEquals("the child wins", "fragment", taps.single().string("source"))
        assertEquals("42", taps.single().string("item_id"))

        taps.clear()
        tap(activity, parent.button)
        assertEquals("parent", taps.single().string("source"))
        assertNull("the child's scope stays in the child", taps.single()["item_id"])
    }

    @Test
    fun aChildsFirstScreenViewedCarriesItsParentFragmentsScope() {
        // The child is committed in the parent's onViewCreated, so it resumes inside the parent's
        // resume dispatch, before the capture has seen the parent resume.
        val activity = launch(ScopedHostActivity::class.java).get()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, ScopedNestingFragment()).commitNow()

        val (name, properties) = screens.first { it.first == "ScopedButtonFragment" }
        assertEquals("ScopedButtonFragment", name)
        assertEquals("42", properties.string("item_id"))
        assertEquals("home", properties.string("list"))
        assertEquals("b", properties.string("variant"))
    }

    @Test
    fun aChildsScreenViewedAfterARemountOfBothViewsStillCarriesItsParentFragmentsScope() {
        // detach()+attach() replaces the parent's frame with an empty one (endMounting), and the
        // parent's onCreate does not run again to re-declare; the child reports before the parent's
        // own resume writes its scope back.
        val activity = launch(ScopedHostActivity::class.java).get()
        val parent = ScopedNestingFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, parent).commitNow()
        activity.supportFragmentManager.beginTransaction().detach(parent).commitNow()
        screens.clear()
        activity.supportFragmentManager.beginTransaction().attach(parent).commitNow()
        drainMainLooper()

        val (_, properties) = screens.first { it.first == "ScopedButtonFragment" }
        assertEquals("42", properties.string("item_id"))
        assertEquals("home", properties.string("list"))
    }

    @Test
    fun anUpdateFromABackgroundThreadIsAppliedOnTheMainThread() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        Thread { scoped.scope.update(mapOf("item_id" to "bg")) }.apply { start(); join() }
        tap(activity, scoped.button)
        assertEquals("not yet: it is posted", "42", taps.single().string("item_id"))

        taps.clear()
        drainMainLooper()
        tap(activity, scoped.button)
        assertEquals("bg", taps.single().string("item_id"))
    }

    @Test
    fun aPostedUpdateThatALaterCallOvertookIsDropped() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        Thread { scoped.scope.update(mapOf("item_id" to "stale")) }.apply { start(); join() }
        scoped.scope.update(mapOf("item_id" to "fresh"))
        drainMainLooper()

        tap(activity, scoped.button)
        assertEquals("the last call wins", "fresh", taps.single().string("item_id"))
    }

    @Test
    fun anUpdateWhileTheViewIsDestroyedReachesTheRebuiltView() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()
        activity.supportFragmentManager.beginTransaction().detach(scoped).commitNow()

        scoped.scope.update(mapOf("item_id" to "7"))
        activity.supportFragmentManager.beginTransaction().attach(scoped).commitNow()
        drainMainLooper()

        tap(activity, scoped.button)
        assertEquals("7", taps.single().string("item_id"))
    }

    @Test
    fun declaringOffTheMainThreadFailsBeforeAnythingIsDeclared() {
        val activity = launch(OwnHostActivity::class.java).get()
        val fragment = ButtonFragmentA()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, fragment).commitNow()

        var failure: Throwable? = null
        Thread { failure = runCatching { fragment.autographScope(scopeStack, mapOf("item_id" to "x")) }.exceptionOrNull() }
            .apply { start(); join() }
        assertTrue("threw: $failure", failure is IllegalStateException)
        assertFalse(SurfaceScopes.hasDeclarations(fragment))
    }

    @Test
    fun aCompositionHostedInTheFragmentSeesItsScope() {
        // A Compose provider nests its root frame under the surface hosting it; a frame pushed by hand
        // with that parent stands in for it, so the lineage is what is asserted.
        val activity = launch(ScopedHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()
        val provider = scopeStack.push(parent = scoped.requireView().autographScopeOrigin())
        assertEquals("42", scopeStack.current(provider).scope.string("item_id"))
        assertEquals("b", scopeStack.current(provider).scope.string("variant"))

        val inner = scopeStack.push(scope = mapOf("item_id" to JsonPrimitive("inner")), parent = provider)
        assertEquals("a nested AutographScope still wins", "inner", scopeStack.current(inner).scope.string("item_id"))
    }

    @Test
    fun updateRevisesTheScopeOfALiveSurfaceAtOnce() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        scoped.scope.update(mapOf("item_id" to "43"))
        tap(activity, scoped.button)
        assertEquals("43", taps.single().string("item_id"))
        assertNull("update replaces, it does not merge", taps.single()["source"])
        assertEquals("ScopedButtonFragment", taps.single().string("screen"))
    }

    @Test
    fun aSecondCallReplacesTheDeclarationInsteadOfStackingOne() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        val again = scoped.autographScope(scopeStack, mapOf("item_id" to "44"))
        assertSame(scoped.scope.declaration, again.declaration)
        tap(activity, scoped.button)
        // Two frames would be ambiguous siblings, and the scope would drop; one revised frame keeps it.
        assertEquals("44", taps.single().string("item_id"))
    }

    @Test
    fun theScopeSurvivesARebuildOfTheFragmentsView() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        activity.supportFragmentManager.beginTransaction().detach(scoped).commitNow()
        activity.supportFragmentManager.beginTransaction().attach(scoped).commitNow()
        drainMainLooper()

        tap(activity, scoped.button)
        assertEquals("42", taps.single().string("item_id"))
    }

    @Test
    fun aDeclarationMadeInOnCreateRidesOnTheFragmentsScreenViewed() {
        launch(OwnHostActivity::class.java).get().let { activity ->
            activity.supportFragmentManager.beginTransaction().add(activity.containerA, ScopedButtonFragment()).commitNow()
        }
        val (name, properties) = screens.last()
        assertEquals("ScopedButtonFragment", name)
        assertEquals("42", properties.string("item_id"))
    }

    @Test
    fun aDestroyedFragmentTakesItsDeclarationWithIt() {
        val activity = launch(OwnHostActivity::class.java).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()
        assertTrue(SurfaceScopes.hasDeclarations(scoped))

        activity.supportFragmentManager.beginTransaction().remove(scoped).commitNow()
        assertFalse(SurfaceScopes.hasDeclarations(scoped))
        scoped.scope.update(mapOf("item_id" to "late")) // a no-op, and no crash

        val next = ButtonFragmentA()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, next).commitNow()
        tap(activity, next.button)
        assertNull(taps.single()["item_id"])
    }

    @Test
    fun withoutTheScreenCaptureTheScopeIsAbsentNotAppWide() {
        val activity = launch(OwnHostActivity::class.java, withScreenCapture = false).get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        tap(activity, scoped.button)
        assertNull(taps.single()["item_id"])
        assertEquals(JsonObject(emptyMap()), scopeStack.current().scope)
    }

    @Test
    fun aCaptureInstalledLaterPicksTheDeclarationUpAtTheNextResume() {
        val controller = launch(OwnHostActivity::class.java, withScreenCapture = false)
        val activity = controller.get()
        val scoped = ScopedButtonFragment()
        activity.supportFragmentManager.beginTransaction().add(activity.containerA, scoped).commitNow()

        installScreenCapture()
        controller.pause().resume()
        drainMainLooper()

        tap(activity, scoped.button)
        assertEquals("42", taps.single().string("item_id"))
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun installScreenCapture() {
        screenCapture = installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { "Main" },
            fragmentScreenName = { it.javaClass.simpleName },
        )
    }

    private fun <A : FragmentActivity> launch(activityClass: Class<A>, withScreenCapture: Boolean = true): ActivityController<A> {
        if (withScreenCapture) installScreenCapture()
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

private fun Map<String, JsonElement>.string(key: String): String? = this[key]?.jsonPrimitive?.content
