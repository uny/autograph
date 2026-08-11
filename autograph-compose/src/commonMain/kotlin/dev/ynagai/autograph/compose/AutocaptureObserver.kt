package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Observes taps app-wide in [PointerEventPass.Final] — after any child `clickable` had a chance
 * to consume them in the main pass — and reports the tapped element via [Tracker.track] as
 * [AutocaptureConfig.eventName]. Never consumes anything itself, and only reports a tap that a
 * descendant actually consumed (so taps on non-interactive background are ignored); [resolver]
 * separately vetoes elements marked [autographIgnore] or already instrumented via [trackClick].
 *
 * It also brackets each dispatch with an [AutocaptureClaims] generation, which is how the iOS resolver
 * learns whether a [trackClick] handler ran for the tap it is resolving — see [AutocaptureClaims] for
 * why that replaced comparing rectangles, and the loop below for the guards that keep the evidence
 * attributable. On Android the generation is opened and closed but never read: that resolver has the
 * semantics ancestry and needs no such inference.
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
    val claims = LocalAutocaptureClaims.current
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
        .pointerInput(tracker, scopeStack, config, resolver, claims) {
            awaitPointerEventScope {
                while (true) {
                    // Two passes of the SAME dispatch. Initial resumes before any child handles the
                    // event, so the generation is already open when a trackClick's `clickable` runs
                    // its handler on the Main pass in between; Final is where this observer decides
                    // whether to report. Deliberately not filtered by type here — pairing every
                    // Initial with its Final keeps the loop in step without assuming anything about
                    // which event types reach it.
                    val initial = awaitPointerEvent(PointerEventPass.Initial)
                    val token = claims?.openTapGeneration()
                    try {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (!executionEvidenceIsAttributable(initial, event)) claims?.clearTapExecution()
                        if (event.type != PointerEventType.Release) continue
                        // Filter directly by isConsumed: on a multi-touch Release the consumed pointer
                        // isn't necessarily changes[0].
                        val change = event.changes.firstOrNull { it.isConsumed } ?: continue
                        val root = rootCoordinates ?: continue
                        reportTapIfResolvable(tracker, scopeStack, config) { resolver.resolve(root, change.position) }
                    } finally {
                        // Runs on `continue` and on cancellation alike, so evidence never outlives the
                        // dispatch that produced it. Token-qualified: see AutocaptureClaims.generation.
                        if (token != null) claims.closeTapGeneration(token)
                    }
                }
            }
        }
}

/**
 * Whether a [trackClick] execution marked between [initial] and [final] can be attributed to the
 * pointer [autocaptureTaps] is about to report.
 *
 * Both conditions fail open — a `false` here only discards evidence, which costs a duplicate event,
 * while wrongly returning `true` costs the event itself. A silently dropped tap is the one outcome
 * autocapture must never produce ([#179](https://github.com/uny/autograph/issues/179)), so anything
 * short of certainty answers `false`.
 *
 * - **Same dispatch.** One dispatch's passes hand back one [PointerEvent] instance and separate
 *   dispatches hand back separate ones, so a mismatch means this `Final` does not belong to the
 *   `Initial` the generation was opened for — the marks in it describe some other dispatch. That is
 *   a Compose implementation detail rather than an API promise, and both halves are load-bearing in
 *   opposite directions, so `PointerEventIdentityTest` measures them against real dispatches instead
 *   of asserting them here: were instances per-pass, this would answer `false` for every tap and
 *   suppression would vanish entirely; were they recycled across dispatches, a desynced loop would
 *   trust a stale mark and drop an unrelated element's tap.
 * - **At most one consumed change.** With two, which of them the mark belongs to is undecidable, and
 *   guessing wrong suppresses an unrelated element's tap.
 *
 * That second condition counts `isConsumed` rather than `changes.size`, and the difference is not
 * cosmetic: a [PointerEvent] carries every active pointer, so a second finger merely resting on the
 * screen inflates `changes` without consuming anything. Keying on size would drop suppression for the
 * whole of a two-finger-down gesture — a duplicate rather than a loss, but a needless one, and absent
 * before this mechanism existed.
 */
internal fun executionEvidenceIsAttributable(initial: PointerEvent, final: PointerEvent): Boolean =
    final === initial && final.changes.count { it.isConsumed } <= 1

/**
 * Calls [resolve] and, if it returns a non-null target, reports it via [tracker]. Any exception
 * from [resolve] or [tracker] is swallowed — a single bad resolve/track must not permanently kill
 * the caller's `while(true)` tap-observation loop for the rest of the composition's lifetime.
 */
internal fun reportTapIfResolvable(
    tracker: Tracker,
    scopeStack: ScopeStack,
    config: AutocaptureConfig,
    resolve: () -> AutocaptureTarget?,
) {
    try {
        val target = resolve() ?: return
        val ctx = scopeStack.current()
        // Delegate the precedence to enrich itself — ambient scope underneath, then screen/section
        // on top as reserved keys — so this path cannot drift from the contract it claims to share.
        // Re-implementing it here previously dropped an ambient section whenever no screen resolved,
        // which enrich would have written.
        //
        // The element's own scope goes in as enrich's argument, i.e. the slot an explicit call site's
        // properties occupy, which lands it exactly where it belongs in that precedence: it refines
        // the ambient scope (the tapped element is more specific than the screen it sits on) while
        // the reserved screen/section keys still win over it.
        var properties = ctx.enrich(target.scope)
        // The one addition enrich can't know about: a bare TrackScreenView pushes no frame, so fall
        // back to the most recently viewed screen. An ambient frame's screen always wins.
        if (ctx.screen == null) {
            scopeStack.screenHistory.lastScreen?.let {
                properties = JsonObject(properties + ("screen" to JsonPrimitive(it)))
            }
        }
        tracker.track(config.eventName, properties, target.identifier)
    } catch (e: Exception) {
        // Swallowed: see kdoc above.
    }
}
