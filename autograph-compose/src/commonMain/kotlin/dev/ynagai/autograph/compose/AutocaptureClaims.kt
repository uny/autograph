package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject

/**
 * What iOS autocapture consults instead of the semantics tree: the bounds of [autographIgnore]
 * subtrees, and whether a [trackClick] handler ran during the pointer dispatch being resolved.
 *
 * Android's [ElementResolver] needs neither — it hit-tests the semantics tree directly and reads
 * [AutographIgnoredKey]/[AutographInstrumentedKey] straight off the tapped node's ancestry. iOS has no
 * way to read a custom semantics key back off a tapped element (see ElementResolver.ios.kt: the UIKit
 * accessibility bridge only carries the fixed UIAccessibility properties — label, traits, identifier,
 * frame — not arbitrary Compose semantics keys), so its resolver consults this.
 *
 * The two halves answer their question by different means, deliberately:
 *
 * - **[ignored] is positional.** [autographIgnore] marks a subtree; nothing about it *runs*, so there
 *   is no execution to observe and geometry is the only signal available. Bounds are captured in
 *   WINDOW space ([boundsInWindow], not `boundsInRoot`) to match `ElementResolver.ios.kt`, which
 *   converts the tap via `root.localToWindow(position)` before comparing — the two must share a
 *   coordinate space regardless of where [autocaptureTaps] sits relative to the window (safe-area
 *   insets, nested embedding, etc.).
 * - **The instrumented half is executional.** It used to be positional too, and that was wrong: it
 *   compared a [trackClick] element's registered rect against the accessibility frame Compose
 *   publishes for it, and Compose clamps a small element's touch-target expansion against
 *   *neighbouring layout*, so the published frame is not a function of the claim at all. Measured on
 *   device, a sub-48dp `trackClick` in an unspaced `Column` registered a 72px-tall rect and published
 *   a 108px one, grown 36px upward and not at all downward — its bottom edge exactly the next
 *   element's top. No rect derived from the claim alone can equal that in general, and every
 *   tightening of the rule traded the duplicate for a silently dropped event
 *   ([#179](https://github.com/uny/autograph/issues/179), and #151/#153/#158/#159 before it — all
 *   five were bugs in that one geometric apparatus). So the geometry is gone, and the question
 *   "is the tapped element already instrumented" is answered by the only thing that actually knows:
 *   whether [trackClick]'s handler ran.
 */
internal class AutocaptureClaims {
    /** Window-space bounds of live [autographIgnore] subtrees, keyed per call-site instance. */
    val ignored = mutableStateMapOf<Any, Rect>()

    // --- Execution-based deduplication (read only by ElementResolver.ios.kt) ---
    //
    // Deliberately NOT snapshot state: nothing recomposes on it, and routing it through the snapshot
    // system would add invalidation for no reader. Plain fields are sound here because every access is
    // confined to the UI thread within one pointer dispatch — `awaitPointerEventScope` is documented
    // as un-dispatched and synchronously resumed, so the observer's Initial/Final code runs inline in
    // the pointer pipeline, and `markInstrumentedClickExecuted` is called straight from `clickable`'s
    // callback on the Main pass of that same dispatch.

    /**
     * Identity of the dispatch currently being observed, or null between dispatches.
     *
     * A token rather than a boolean so [closeTapGeneration] can refuse to close a generation it did
     * not open. The observer's `pointerInput` coroutine restarts whenever its keys change; without the
     * token, a cancelled coroutine's `finally` could close the generation its replacement had just
     * opened, and the mark that followed would be silently discarded.
     */
    private var generation: Any? = null

    private var instrumentedClickExecuted = false

    // --- SPIKE (#185): scope recorded during pointer dispatch, not read back off the a11y bridge ---
    //
    // Ancestor→descendant is the order PointerEventPass.Initial propagates in, so entries land
    // outermost-first and a later one wins a key clash — the same outer→inner precedence
    // resolveAutocaptureTarget applies to the semantics chain on Android.

    private val scopeChain = mutableListOf<JsonObject>()

    /** Records one [autocaptureScope]'s properties, if a dispatch is currently being observed. */
    fun recordScope(properties: JsonObject) {
        if (generation != null && properties.isNotEmpty()) scopeChain.add(properties)
    }

    /** The scopes recorded on the hit path during the dispatch being resolved, merged inner-wins. */
    fun scopeThisGeneration(): JsonObject =
        scopeChain.fold(EmptyJsonObject) { acc, scope -> if (acc.isEmpty()) scope else JsonObject(acc + scope) }

    /** Opens a generation for one pointer dispatch and returns the token identifying it. */
    fun openTapGeneration(): Any = Any().also {
        generation = it
        instrumentedClickExecuted = false
        scopeChain.clear()
    }

    /**
     * Drops the execution evidence while leaving the generation open — for when the observer can see
     * that a mark cannot be attributed to the pointer it is about to report. Forgetting evidence can
     * only cost a duplicate; keeping evidence it cannot attribute could cost the event itself.
     */
    fun clearTapExecution() {
        instrumentedClickExecuted = false
    }

    /** Closes [token]'s generation. A stale token is ignored — see [generation]. */
    fun closeTapGeneration(token: Any) {
        if (generation === token) {
            generation = null
            instrumentedClickExecuted = false
            scopeChain.clear()
        }
    }

    /**
     * Records that a [trackClick] handler ran, if a dispatch is currently being observed.
     *
     * The no-op outside a generation is what confines this to real taps. A VoiceOver double-tap, an
     * `Enter` activation, and any dialog/popup route that never reaches [autocaptureTaps]'s
     * `pointerInput` all invoke the same handler; each marks nothing, so none of them can suppress a
     * later tap. That is also why the generation is scoped to a single dispatch rather than being a
     * persistent "last activated" flag.
     */
    fun markInstrumentedClickExecuted() {
        if (generation != null) instrumentedClickExecuted = true
    }

    /** Whether a [trackClick] handler ran during the dispatch being resolved. */
    fun instrumentedClickExecutedThisGeneration(): Boolean = instrumentedClickExecuted
}

/** The ambient [AutocaptureClaims], or null outside [AutographProvider] / when autocapture is disabled. */
internal val LocalAutocaptureClaims = staticCompositionLocalOf<AutocaptureClaims?> { null }

/**
 * Registers this element's on-screen bounds into the ambient [AutocaptureClaims.ignored], keyed by a
 * per-call-site-instance identity so the entry is removed on disposal without disturbing other
 * elements' entries. No-op when there's no ambient [AutocaptureClaims] (autocapture disabled, or
 * outside [AutographProvider]) — cheap to call unconditionally from [autographIgnore].
 *
 * [trackClick] does not use this. It reports its execution instead — see [AutocaptureClaims].
 */
@Composable
internal fun Modifier.registerIgnoredBounds(): Modifier {
    val claims = LocalAutocaptureClaims.current ?: return this
    val key = remember { Any() }
    DisposableEffect(claims, key) { onDispose { claims.ignored.remove(key) } }
    return onGloballyPositioned { claims.ignored[key] = it.boundsInWindow() }
}
