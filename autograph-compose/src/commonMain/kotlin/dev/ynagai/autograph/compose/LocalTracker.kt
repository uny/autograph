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
 *
 * Pass [scopeStack] only in a **hybrid app** — one where part of the UI is Compose and part is
 * native (UIKit/SwiftUI, or the Android View system) — and the native side runs its own capture
 * pipeline. Both sides must then read and write the *same* stack: `TrackedScreen` and
 * [AutographScope] push their frames into it here, and the native pipeline resolves a tap's screen
 * and scope by reading it. Left null, this provider owns a private stack, which is correct for a
 * Compose-only app but would leave the two sides attributing events against separate, half-empty
 * contexts. Owning the stack outside the composition is also what lets it outlive a recomposition
 * while still being replaceable on logout — scope it to the same lifetime as [tracker].
 */
@Composable
public fun AutographProvider(
    tracker: Tracker,
    autocapture: AutocaptureConfig? = null,
    scopeStack: ScopeStack? = null,
    content: @Composable () -> Unit,
) {
    // Outside the `autocapture != null` branch below, and deliberately so: this marks the host as
    // Compose-owned for a native capture pipeline running alongside, and that boundary has to hold
    // even when this provider captures nothing itself. Gate it on the flag and a hybrid app running
    // Compose-without-autocapture plus native-with-capture would have the native walk descend into
    // Compose's bridged tree and report elements excluded with autographIgnore(). See
    // RegisterComposeHostForNativeCapture.
    RegisterComposeHostForNativeCapture()
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
    // Scope the ambient scope stack to this tracker so neither previous_screen nor scope/screen
    // context leaks across trackers; both reset when the tracker is replaced (e.g. after logout).
    // Screen history rides along on the stack (see ScopeStack.screenHistory), so it inherits that
    // scoping — and, when the caller supplies a stack shared with a native pipeline, that pipeline's
    // screen views and this composition's stay on one continuous previous_screen chain.
    // A caller-supplied stack is used as-is: it is shared with a native pipeline that already holds
    // it, so replacing it here would strand the frames that pipeline reads. Such a caller takes on
    // the lifetime obligation above.
    val effectiveScopeStack = remember(tracker, scopeStack) { scopeStack ?: ScopeStack() }
    CompositionLocalProvider(
        LocalTracker provides tracker,
        LocalScopeStack provides effectiveScopeStack,
    ) {
        if (autocapture != null) {
            // Only provided when autocapture is on: registerAutocaptureClaim no-ops without it, so
            // autographIgnore()/trackClick()/trackImpression() don't pay for position tracking otherwise.
            val claims = remember { AutocaptureClaims() }
            CompositionLocalProvider(LocalAutocaptureClaims provides claims) {
                Box(Modifier.fillMaxSize().autocaptureTaps(tracker, effectiveScopeStack, autocapture)) {
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
