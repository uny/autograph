package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker

/**
 * Observes taps app-wide in [PointerEventPass.Final] — after any child `clickable` had a chance
 * to consume them in the main pass — and reports the tapped element via [Tracker.track] as
 * [AutocaptureConfig.eventName]. Never consumes anything itself, and only reports a tap that a
 * descendant actually consumed (so taps on non-interactive background are ignored); [resolver]
 * separately vetoes elements marked [autographIgnore] or already instrumented via [trackClick]/
 * [trackImpression].
 *
 * Screen is attributed from [screenHistory]'s most recently viewed screen (the same value used
 * for `previous_screen`) — section-level [ScreenContext] isn't available here since this observer
 * sits above any nested [TrackedScreen].
 */
@Composable
internal fun Modifier.autocaptureTaps(
    tracker: Tracker,
    screenHistory: ScreenHistory,
    config: AutocaptureConfig,
): Modifier {
    val resolver = rememberElementResolver()
    // remember, not a bare local var: the pointerInput coroutine below is long-lived and only
    // restarts when tracker/config/resolver change identity, so a plain var reassigned by
    // onGloballyPositioned on every recomposition would go stale relative to it — this shared,
    // stable holder keeps both reading the same up-to-date coordinates.
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { rootCoordinates = it }
        .pointerInput(tracker, config, resolver) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type != PointerEventType.Release) continue
                    // Filter directly by isConsumed: on a multi-touch Release the consumed pointer
                    // isn't necessarily changes[0].
                    val change = event.changes.firstOrNull { it.isConsumed } ?: continue
                    val root = rootCoordinates ?: continue
                    reportTapIfResolvable(tracker, screenHistory, config) { resolver.resolve(root, change.position) }
                }
            }
        }
}

/**
 * Calls [resolve] and, if it returns a non-null target, reports it via [tracker]. Any exception
 * from [resolve] or [tracker] is swallowed — a single bad resolve/track must not permanently kill
 * the caller's `while(true)` tap-observation loop for the rest of the composition's lifetime.
 */
internal fun reportTapIfResolvable(
    tracker: Tracker,
    screenHistory: ScreenHistory,
    config: AutocaptureConfig,
    resolve: () -> String?,
) {
    try {
        val target = resolve() ?: return
        val screenContext = screenHistory.lastScreen?.let { ScreenContext(it) }
        tracker.track(config.eventName, withScreenContext(EmptyJsonObject, screenContext), target)
    } catch (e: Exception) {
        // Swallowed: see kdoc above.
    }
}
