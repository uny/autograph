package dev.ynagai.autograph.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private object SilentTracker : Tracker {
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
    override fun screen(name: String, properties: Map<String, JsonElement>) = Unit
    override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
}

/** A lifecycle the test drives by hand, standing in for a pager page's or an Activity's. */
private class HostLifecycle(initial: Lifecycle.State) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    init { registry.currentState = initial }
    override val lifecycle: Lifecycle get() = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}

/**
 * The composition's frames follow the selection of the surface showing it — the fix for #228, in
 * which a `TrackedScreen` inside a demoted `ViewPager2` page or a paused Activity kept naming the
 * ambient screen. `LocalLifecycleOwner` is what every host demotes through, so the test drives one
 * by hand: the stack is what the provider must leave in each state, read ambiently, exactly as a
 * host app enriching its own events or the iOS native tap capture reads it.
 */
@OptIn(ExperimentalTestApi::class)
class ProviderSelectionUiTest {

    @Test
    fun aScreenDeclaredInsideAPausedHostIsAbsentAmbientlyAndBackOnResume() = runComposeUiTest {
        val stack = ScopeStack()
        val host = HostLifecycle(Lifecycle.State.RESUMED)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutographProvider(SilentTracker, scopeStack = stack) {
                    TrackedScreen("Main") {}
                }
            }
        }
        waitForIdle()
        assertEquals("Main", stack.current().screen)

        // A permission prompt, a translucent Activity on top: RESUMED → STARTED, nothing disposed.
        host.moveTo(Lifecycle.State.STARTED)
        assertNull(stack.current().screen, "off display, the declaration is silent")

        host.moveTo(Lifecycle.State.RESUMED)
        assertEquals("Main", stack.current().screen, "and back without recomposing anything")
    }

    @Test
    fun aScreenComposedInsideAHostThatIsNotYetResumedStartsSilent() = runComposeUiTest {
        // The measured shape: a pager pre-composes its off-screen page at STARTED, so the page's
        // TrackedScreen is pushed while its host is already demoted. Born active, it named the
        // ambient screen — "the page most recently composed, not the one on display".
        val stack = ScopeStack()
        val host = HostLifecycle(Lifecycle.State.STARTED)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutographProvider(SilentTracker, scopeStack = stack) {
                    TrackedScreen("PageB") {}
                }
            }
        }
        waitForIdle()
        assertNull(stack.current().screen, "a page nobody has seen yet must not name the screen")

        host.moveTo(Lifecycle.State.RESUMED)
        assertEquals("PageB", stack.current().screen, "selected, it does")
    }

    @Test
    fun aPagesOwnScopeFollowsTheSameSelection() = runComposeUiTest {
        // Not only the screen: an AutographScope in a demoted page must not attribute either — the
        // whole composition goes off display with its host.
        val stack = ScopeStack()
        val host = HostLifecycle(Lifecycle.State.RESUMED)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutographProvider(SilentTracker, scopeStack = stack) {
                    AutographScope("tab" to "home") { TrackedScreen("Home") {} }
                }
            }
        }
        waitForIdle()
        assertEquals("home", stack.current().scope["tab"]?.toString()?.trim('"'))

        host.moveTo(Lifecycle.State.STARTED)
        assertNull(stack.current().scope["tab"])
    }
}
