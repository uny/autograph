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
import dev.ynagai.autograph.context.ScopeStack

/**
 * Observes taps app-wide in [PointerEventPass.Final] — after any child `clickable` had a chance
 * to consume them in the main pass — and reports the tapped element via [Tracker.track] as
 * [AutocaptureConfig.eventName]. Never consumes anything itself, and only reports a tap that a
 * descendant actually consumed (so taps on non-interactive background are ignored); [resolver]
 * separately vetoes elements marked [autographIgnore] or already instrumented via [trackClick]/
 * [trackImpression].
 *
 * Scope, screen, and section are read from the ambient [scopeStack] at tap time — the non-Compose
 * home that [AutographScope] and [TrackedScreen] mirror into. This observer sits at the provider
 * root, above any nested scope/screen, and holds the root [tracker] rather than the scope decorator,
 * so reading the stack is the only way it can attribute a tap to the scope/screen it happened under.
 * [screenHistory] supplies a screen fallback for a bare [TrackScreenView] that pushes no frame.
 */
@Composable
internal fun Modifier.autocaptureTaps(
    tracker: Tracker,
    screenHistory: ScreenHistory,
    scopeStack: ScopeStack,
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
                    reportTapIfResolvable(tracker, screenHistory, scopeStack, config) { resolver.resolve(root, change.position) }
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
    scopeStack: ScopeStack,
    config: AutocaptureConfig,
    resolve: () -> String?,
) {
    try {
        val target = resolve() ?: return
        val ctx = scopeStack.current()
        // Effective screen: the ambient TrackedScreen frame if any, else the most recently viewed
        // screen (preserves attribution for a bare TrackScreenView, which pushes no frame). Section
        // comes only from the ambient frame.
        val screen = ctx.screen ?: screenHistory.lastScreen
        val screenContext = screen?.let { ScreenContext(it, ctx.section) }
        // Scope sits underneath (there are no call-site properties for an autocaptured tap), then
        // screen/section are written on top as reserved keys — the precedence AmbientContext.enrich
        // encodes, with the screen fallback layered in.
        val base = if (ctx.scope.isEmpty()) EmptyJsonObject else ctx.scope
        tracker.track(config.eventName, withScreenContext(base, screenContext), target)
    } catch (e: Exception) {
        // Swallowed: see kdoc above.
    }
}
