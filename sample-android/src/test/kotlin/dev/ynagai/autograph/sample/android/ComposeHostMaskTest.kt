@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.sample.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.ComposeView
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** A native fragment with a plain view — a screen the native capture names by itself. */
class NativeScreenFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = View(requireContext())
}

/** Hosts Compose content that declares a screen. */
class DeclaringComposeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AutographProvider(HybridFixtures.tracker, AutocaptureConfig(), HybridFixtures.scopeStack) {
                TrackedScreen("ComposeScreen") { Box(androidx.compose.ui.Modifier) {} }
            }
        }
    }
}

/** Hosts Compose content that declares nothing — the #216 shape. */
class SilentComposeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AutographProvider(HybridFixtures.tracker, AutocaptureConfig(), HybridFixtures.scopeStack) {
                Box(androidx.compose.ui.Modifier) {}
            }
        }
    }
}

/** The fragments compose before the test can hand them anything, so the shared state lives here. */
object HybridFixtures {
    lateinit var tracker: Tracker
    lateinit var scopeStack: ScopeStack
}

class ShellActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, NativeScreenFragment(), "native")
            .commitNow()
    }
}

/**
 * The property `autograph-android` cannot test on its own: the screen **mask** a native Compose host
 * pushes must not bury a screen the Compose content inside it actually declared.
 *
 * The mask exists because a Compose host is excluded from native screen capture (the Compose pipeline
 * is supposed to report it), so without one an unnamed host inherits the frame of the screen
 * underneath and every captured tap names the screen the user just left (#216). The host's frame is
 * reserved at `onFragmentAttached` — before the fragment's view attaches, which is when
 * `AbstractComposeView` creates its composition — precisely so that a `TrackedScreen` inside the host
 * lands *above* it and wins. That ordering is the thing under test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeHostMaskTest {

    private class RecordingTracker : Tracker {
        val screens = mutableListOf<String>()
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            screens += name
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }

    private val tracker = RecordingTracker()
    private val scopeStack = ScopeStack()

    private fun start(): FragmentActivity {
        HybridFixtures.tracker = tracker
        HybridFixtures.scopeStack = scopeStack
        installAutographNativeScreenCapture(
            application = RuntimeEnvironment.getApplication(),
            tracker = tracker,
            scopeStack = scopeStack,
            activityScreenName = { it.javaClass.simpleName },
            fragmentScreenName = { it.javaClass.simpleName },
        )
        return Robolectric.buildActivity(ShellActivity::class.java).setup().get()
    }

    @Test
    fun aDeclaredComposeScreenWinsOverTheHostsMask() {
        val activity = start()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, DeclaringComposeFragment(), "compose")
            .addToBackStack(null).commit()
        fm.executePendingTransactions()
        // TrackScreenView emits from a LaunchedEffect, so the composition's dispatcher has to run.
        Shadows.shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()

        assertEquals("ComposeScreen", scopeStack.current().screen)
        assertFalse("a declared screen is not a mask", scopeStack.current().screenMasked)
        assertEquals(listOf("NativeScreenFragment", "ComposeScreen"), tracker.screens)
    }

    @Test
    fun aComposeHostThatDeclaresNothingCarriesNoScreen() {
        val activity = start()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(android.R.id.content, SilentComposeFragment(), "compose")
            .addToBackStack(null).commit()
        fm.executePendingTransactions()
        Shadows.shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()

        // Not "NativeScreenFragment": the screen underneath is only paused, so its frame is still live.
        assertNull(scopeStack.current().screen)
        // And it is a MASK, not merely an absent screen — the distinction the Compose tap observer
        // acts on. Asserting the null alone would also pass for an implementation that deselected the
        // frame beneath instead of masking, and that one reinstates the stale screen from history.
        assertTrue(scopeStack.current().screenMasked)
        assertEquals(listOf("NativeScreenFragment"), tracker.screens)
    }
}
