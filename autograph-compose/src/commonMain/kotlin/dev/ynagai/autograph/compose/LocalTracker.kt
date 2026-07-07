package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ynagai.autograph.Tracker
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
 */
@Composable
public fun AutographProvider(
    tracker: Tracker,
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
    CompositionLocalProvider(LocalTracker provides tracker, content = content)
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

    override fun track(name: String, properties: JsonObject): Unit = warn()
    override fun screen(name: String, properties: JsonObject): Unit = warn()
    override fun identify(userId: String, traits: JsonObject): Unit = warn()
}
