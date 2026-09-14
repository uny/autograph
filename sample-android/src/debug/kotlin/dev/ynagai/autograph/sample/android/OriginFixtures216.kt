@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.sample.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.TrackScreenViews
import dev.ynagai.autograph.compose.TrackedScreen
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------------------------
// Fixtures for OriginOnDeviceTest: the on-device port of ComposeTapOriginTest's framework-dependent
// cases (#216). Debug source set, not androidTest: the hosts must be in the app's own manifest for
// ActivityScenario to launch them in the app's process (see src/debug/AndroidManifest.xml).
// ---------------------------------------------------------------------------------------------

/** Shared state between the fixtures and the test: the stack under test, and every tap it reported. */
object Rig {
    lateinit var tracker: Tracker
    lateinit var scopeStack: ScopeStack
    val taps = mutableListOf<Pair<String?, Map<String, JsonElement>>>()

    val OWN = View.generateViewId()
    val CHROME = androidx.fragment.R.id.visible_removing_fragment_view_tag  // a LIBRARY id: generated ids have no entry name, android.* ids are excluded
    val LEFT = View.generateViewId()
    val RIGHT = View.generateViewId()
    val INTEROP = androidx.fragment.R.id.fragment_container_view_tag        // same, as the Robolectric fixture uses
    val PAGER = View.generateViewId()
}

@Composable
private fun Tappable(tag: String) {
    AutographProvider(Rig.tracker, AutocaptureConfig(), Rig.scopeStack) {
        Box(Modifier.fillMaxSize().testTag(tag).clickable {}) {}
    }
}

@Composable
private fun DeclaringTappable(screen: String, tag: String) {
    AutographProvider(Rig.tracker, AutocaptureConfig(), Rig.scopeStack) {
        TrackedScreen(screen) { Box(Modifier.fillMaxSize().testTag(tag).clickable {}) {} }
    }
}

/**
 * Non-overlapping version of OwnComposeActivity: own composition (top), native chrome (middle), and
 * TWO side-by-side fragment containers (bottom) so sibling surfaces can both be tapped by a finger.
 */
class OriginActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ownCompose = ComposeView(this).apply {
            id = Rig.OWN
            setContent { DeclaringTappable("Main", "own") }
        }
        val chrome = Button(this).apply { id = Rig.CHROME; text = "chrome"; isClickable = true }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(FrameLayout(context).apply { id = Rig.LEFT }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(FrameLayout(context).apply { id = Rig.RIGHT }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ownCompose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
            },
        )
    }
}

class SilentFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { Tappable("silent") } }
}

open class DeclaringFragment(private val screen: String, private val tag: String) : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val screen = this.screen
        val tag = this.tag
        return ComposeView(requireContext()).apply { setContent { DeclaringTappable(screen, tag) } }
    }
}

// The declared names differ from the fragments' simpleNames on purpose: the test installs
// `fragmentScreenName = { it.javaClass.simpleName }`, so with equal names a tap could not tell a
// Compose declaration from a native fragment frame that should have been excluded.
class PageA : DeclaringFragment("ScreenA", "a")
class PageB : DeclaringFragment("ScreenB", "b")
class PageC : DeclaringFragment("ScreenC", "c")

class InteropFragment2 : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(Rig.tracker, AutocaptureConfig(), Rig.scopeStack) {
                    TrackedScreen("Detail") {
                        AndroidView(factory = { ctx ->
                            Button(ctx).apply {
                                id = Rig.INTEROP
                                text = "interop"
                                isClickable = true
                            }
                        })
                    }
                }
            }
        }
}

class NavFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                AutographProvider(Rig.tracker, AutocaptureConfig(), Rig.scopeStack) {
                    val nav = rememberNavController()
                    nav.TrackScreenViews()
                    NavHost(nav, startDestination = "home") {
                        composable("home") { Box(Modifier.fillMaxSize().testTag("nav").clickable {}) {} }
                    }
                }
            }
        }
}

/** A REAL ViewPager2 + FragmentStateAdapter: the demotion the Robolectric fixture does not run. */
class PagerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pager = ViewPager2(this).apply {
            id = Rig.PAGER
            offscreenPageLimit = 1
            adapter = object : FragmentStateAdapter(this@PagerActivity) {
                override fun getItemCount() = 3
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> PageA(); 1 -> PageB(); else -> PageC()
                }
            }
        }
        setContentView(pager)
    }
}

