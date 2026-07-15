package dev.ynagai.autograph.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject

/**
 * The ambient [Tracker] for the composition. Provide it with [AutographProvider];
 * until then, events are dropped and a single warning is printed.
 */
public val LocalTracker: androidx.compose.runtime.ProvidableCompositionLocal<Tracker> =
    staticCompositionLocalOf { MissingTracker }

/**
 * Provides [tracker] to the composition via [LocalTracker] and wires application
 * lifecycle events (foreground/background) into the tracker's session bookkeeping.
 *
 * Pass [autocapture] to also report taps app-wide without instrumenting every element with
 * [trackClick] — opt-in, since observing every tap is a meaningfully different privacy posture
 * than explicit instrumentation. See [AutocaptureConfig].
 */
@Composable
public fun AutographProvider(
    tracker: Tracker,
    autocapture: AutocaptureConfig? = null,
    content: @Composable () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tracker.notifyForeground()
                Lifecycle.Event.ON_STOP -> tracker.notifyBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Scope screen history and the ambient scope stack to this tracker so neither previous_screen
    // nor scope/screen context leaks across trackers; both reset when the tracker is replaced (e.g.
    // after logout).
    val screenHistory = remember(tracker) { ScreenHistory() }
    val scopeStack = remember(tracker) { ScopeStack() }
    CompositionLocalProvider(
        LocalTracker provides tracker,
        LocalScreenHistory provides screenHistory,
        LocalScopeStack provides scopeStack,
    ) {
        if (autocapture != null) {
            // Only provided when autocapture is on: registerAutocaptureClaim no-ops without it, so
            // autographIgnore()/trackClick()/trackImpression() don't pay for position tracking otherwise.
            val claims = remember { AutocaptureClaims() }
            CompositionLocalProvider(LocalAutocaptureClaims provides claims) {
                Box(Modifier.fillMaxSize().autocaptureTaps(tracker, screenHistory, scopeStack, autocapture)) {
                    content()
                }
            }
        } else {
            content()
        }
    }
}

private object MissingTracker : Tracker {
    private var warned = false

    private fun warn() {
        if (!warned) {
            warned = true
            println(
                "Autograph: no Tracker provided — events are being dropped. " +
                    "Wrap your UI in AutographProvider(tracker) { ... }.",
            )
        }
    }

    override fun track(name: String, properties: JsonObject, target: String?): Unit = warn()
    override fun screen(name: String, properties: JsonObject): Unit = warn()
    override fun identify(userId: String, traits: JsonObject): Unit = warn()
}
