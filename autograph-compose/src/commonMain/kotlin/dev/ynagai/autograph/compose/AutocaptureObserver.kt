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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * Precedence is applied by `AmbientContext.enrich` itself, so this path cannot drift from it.
 *
 * The stack's own [ScopeStack.screenHistory] supplies a screen fallback for a bare [TrackScreenView]
 * that pushes no frame. Reading it off the stack rather than taking it as a separate argument means
 * the two cannot be handed a mismatched pair.
 */
@Composable
internal fun Modifier.autocaptureTaps(
    tracker: Tracker,
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
        // Keyed on scopeStack too: this coroutine is long-lived and captures the stack (and, through
        // it, the screen history), so a caller that swaps the stack alone would otherwise leave it
        // attributing taps against the one nobody writes to any more. Same reasoning as the nav
        // listener's key in TrackScreenViews — the two sites capture the same thing and should not
        // disagree about it.
        .pointerInput(tracker, scopeStack, config, resolver) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type != PointerEventType.Release) continue
                    // Filter directly by isConsumed: on a multi-touch Release the consumed pointer
                    // isn't necessarily changes[0].
                    val change = event.changes.firstOrNull { it.isConsumed } ?: continue
                    val root = rootCoordinates ?: continue
                    reportTapIfResolvable(tracker, scopeStack, config) { resolver.resolve(root, change.position) }
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
    scopeStack: ScopeStack,
    config: AutocaptureConfig,
    resolve: () -> String?,
) {
    try {
        val target = resolve() ?: return
        val ctx = scopeStack.current()
        // Delegate the precedence to enrich itself — scope underneath (an autocaptured tap has no
        // call-site properties), then screen/section on top as reserved keys — so this path cannot
        // drift from the contract it claims to share. Re-implementing it here previously dropped an
        // ambient section whenever no screen resolved, which enrich would have written.
        var properties = ctx.enrich(EmptyJsonObject)
        // The one addition enrich can't know about: a bare TrackScreenView pushes no frame, so fall
        // back to the most recently viewed screen. An ambient frame's screen always wins.
        if (ctx.screen == null) {
            scopeStack.screenHistory.lastScreen?.let {
                properties = JsonObject(properties + ("screen" to JsonPrimitive(it)))
            }
        }
        tracker.track(config.eventName, properties, target)
    } catch (e: Exception) {
        // Swallowed: see kdoc above.
    }
}
