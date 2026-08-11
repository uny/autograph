package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Carries an element's autocapture scope down the semantics tree. See [autocaptureScope]. */
internal val AutographScopeKey: SemanticsPropertyKey<JsonObject> = SemanticsPropertyKey("AutographScope")

/**
 * Attaches [properties] to autocaptured taps that land in this element's subtree — the per-element
 * counterpart of [AutographScope], for the case that composable cannot serve: sibling scopes mounted
 * at the same time, such as a list's rows each carrying their own `article_id`.
 *
 * ```kotlin
 * LazyColumn {
 *     items(articles) { article ->
 *         Row(
 *             Modifier
 *                 .autocaptureScope("article_id" to article.id)
 *                 .clickable { open(article) },
 *         ) { ArticleRow(article) }
 *     }
 * }
 * ```
 *
 * **This affects autocapture only** — unlike [AutographScope], which reaches every event emitted
 * from its content. A `Modifier` cannot install a `CompositionLocal` for the element's children, so
 * `Modifier.trackClick` / `trackImpression` and plain `LocalTracker.current.track(...)` calls inside
 * this subtree do *not* pick these properties up; pass them explicitly, or wrap the content in
 * [AutographScope] as well. This is a companion to [AutographScope], not a replacement for it.
 *
 * Scopes compose along the tapped element's ancestry: an enclosing `autocaptureScope` contributes to
 * a nested one, the inner winning a key clash, and [AutographScope] frames enclosing the element
 * contribute underneath both. Screen and section from [TrackedScreen] still win over all of them, as
 * they do for any other event — but only where one is actually active. With no screen resolved
 * anywhere (no [TrackedScreen], no prior `TrackScreenView`), a `screen` key you put in a scope
 * yourself is left standing, exactly as it would be if you passed it to `track` at a call site: this
 * scope occupies that same slot — and `section` is guarded independently, so it behaves the same way
 * wherever no section is set. Don't name a scope key `screen` or `section`.
 *
 * **Declare it once per element.** That composition is across the ancestry, not within one modifier
 * chain: `Modifier.autocaptureScope(a).autocaptureScope(b)` does *not* merge — Compose collapses
 * duplicate semantics on a single layout node to the first value, so `b` is dropped whole, exactly
 * as a repeated `testTag` or `contentDescription` is. Pass the keys in one call, or put the second
 * scope on a wrapping element, where the ancestry merge does apply.
 *
 * A wrapper works as well as the clickable element itself, including for a `clickable` drawn
 * *outside* the wrapper's own bounds — an overhanging badge, an `offset` decoration. The hit test
 * reaches such an element because Compose routes the real pointer to it, and the wrapper is on its
 * ancestry either way. (Before [#126](https://github.com/uny/autograph/issues/126) it did not: the
 * walk stopped above any child whose parent's bounds missed the tap, so those taps were dropped.)
 *
 * **Android only.** Resolution reads the marker back off the tapped element's ancestry in the
 * semantics tree — the same chain the tap's `target` is picked from. That is the guarantee worth
 * stating: the scope always describes *the element that was actually hit*, because the two are read
 * together. Where the hit test itself misattributes — `Modifier.clip` cannot be expressed as a rect,
 * so a tap in the corner of a rounded clip may land on the wrong element, see [AutocaptureConfig] —
 * the scope follows the target and is wrong with it, rather than contradicting it. Resolving the
 * scope any other way (a positional registry of last-known bounds, or mount order) admits the worse
 * failure instead: a correct `target` carrying a *neighbouring* element's scope, silently, whenever
 * bounds go stale mid-animation.
 *
 * **On iOS this modifier contributes nothing, and that is a limit of Compose Multiplatform's current
 * surface rather than unfinished work here.** The only supported route to a tapped element there is
 * the UIKit accessibility tree Compose Multiplatform bridges its semantics into — no `SemanticsOwner`
 * is reachable from application code — and that bridge carries just the fixed UIAccessibility
 * properties: label, traits, identifier, frame. None of the custom semantics this modifier writes
 * crosses it. So the scope would have to be correlated with the tapped element through one of those
 * four instead, and each is either unsound or already occupied:
 *
 * - **Geometry** — a registry of each scope's on-screen bounds, matched against the tapped element's
 *   — does not establish identity, however fresh the bounds are kept. Take two exactly coincident
 *   clickables, an unscoped selection overlay over a scoped card. Compose routes the pointer to the
 *   overlay, so naming the overlay is the *correct* answer for `target`; but the two rectangles are
 *   equal, so the sole geometric match for the scope is the card's. The tap would report the right
 *   element carrying its neighbour's scope — and the failure lands precisely when the hit test got
 *   the target right, which leaves this library no signal of its own to catch it with. It needs no
 *   stale bounds, no animation and no tolerance: it is simply what equal rectangles cannot prove.
 *   The same blindness already applies to iOS [autographIgnore] / [trackClick] claims, where it costs
 *   a *suppressed* event; here it would cost a wrong property, and this library treats a wrong scope
 *   as worse than no scope precisely because it lands in analytics data as if it were true.
 * - **The identifier** is the one bridged property that is both identity-bearing and never read out
 *   to a VoiceOver user — but it is the same slot the tap's own `target` is picked from, and the
 *   canonical usage above puts this modifier on the *clickable's own* chain. Two semantics values on
 *   one layout node collapse to the first, exactly as described above, so either the scope is
 *   dropped or the element's `target` is.
 * - The **label** is read aloud, and the **traits** are a fixed bitmask with nowhere to put a value.
 *
 * So an autocaptured iOS tap carries no element scope, and [AutographScope] siblings there stay
 * ambiguous and drop per `ScopeStack`: a *missing* property rather than a wrong one, which is the
 * trade this library takes on purpose. Expect an `article_id` on Android and none on iOS for the
 * same tap. **Where per-element properties are needed on iOS, instrument the element explicitly** —
 * [trackClick] carries them on both platforms. It brings its own `clickable`, so it replaces the
 * element's click handler rather than joining it: `Modifier.trackClick(name, properties) { open() }`
 * in place of `Modifier.clickable { open() }`. The element is then reported under *that* name rather
 * than autocaptured — explicit instrumentation replaces the autocaptured event, it does not add to
 * it — and you name each element yourself, which is the cost. Reopening this needs Compose
 * Multiplatform to grow one of: custom semantics that cross the bridge, a stable node identity
 * readable from both sides, or a supported semantics hit-test on iOS. See
 * [#68](https://github.com/uny/autograph/issues/68).
 */
public fun Modifier.autocaptureScope(vararg properties: Pair<String, String>): Modifier =
    autocaptureScope(JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }))

/**
 * [autocaptureScope] overload taking a [JsonObject], for scope values that aren't strings (numbers,
 * booleans, nested objects) or that are already assembled as a [JsonObject].
 */
public fun Modifier.autocaptureScope(properties: JsonObject): Modifier =
    this then AutocaptureScopeElement(properties)

/**
 * A node of its own rather than `semantics { this[AutographScopeKey] = properties }`, purely so that
 * an unchanged scope survives recomposition without work.
 *
 * `Modifier.semantics {}` compares its lambda by *reference*, and a lambda that captures
 * [properties] is a fresh instance on every call — so the element would never equal the previous
 * one, and every recomposition of the element would invalidate its layout node's semantics config.
 * The repo's other semantics markers ([autographIgnore], [trackClick]'s instrumented flag) dodge
 * this without meaning to: their lambdas capture nothing, so the compiler hands out a singleton and
 * they compare equal. This modifier is aimed squarely at list rows, where that difference lands.
 *
 * The load-bearing detail is that this is a plain extension, not a `@Composable` one: the Compose
 * compiler memoizes a lambda written inside a composable body when it captures only stable values,
 * so `semantics {}` spelled out at a call site would have compared equal after all. Written here it
 * cannot be memoized. Measured over ten recompositions with the scope unchanged: this node applies
 * its semantics 0 times, the lambda form 10.
 *
 * A `data class` element compares [properties] by value instead, and [JsonObject] is structurally
 * equal, so re-emitting the same scope is a no-op. Two of these on one modifier chain still collapse
 * to the outermost, exactly as two `semantics {}` blocks would — see [autocaptureScope].
 */
private data class AutocaptureScopeElement(
    val properties: JsonObject,
) : ModifierNodeElement<AutocaptureScopeNode>() {
    override fun create(): AutocaptureScopeNode = AutocaptureScopeNode(properties)

    // No invalidateSemantics() here: Compose's own autoInvalidateUpdatedNode already marks a
    // SemanticsModifierNode's layout node invalidated after update(), which is why Compose's
    // AppendedSemanticsElement.update() doesn't call it either. Calling it by hand would also be
    // reaching for the coordinator at a point where the node is not guaranteed attached.
    // `aChangedScopeReachesTheSemanticsTreeOnRecomposition` is what holds this honest.
    override fun update(node: AutocaptureScopeNode) {
        node.properties = properties
    }
}

private class AutocaptureScopeNode(var properties: JsonObject) :
    Modifier.Node(),
    SemanticsModifierNode,
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AutographScopeKey] = properties
    }

    // --- SPIKE (#185) ---
    //
    // Compose's own pointer dispatch reaches every PointerInputModifierNode on the hit path, and
    // Initial propagates ancestor→descendant. So a scope on the tapped element's ancestry records
    // itself *during* the tap, rather than being read back off the tapped element afterwards — which
    // is what the UIKit accessibility bridge cannot support (see this file's kdoc).
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        currentValueOf(LocalAutocaptureClaims)?.recordScope(properties)
    }

    override fun onCancelPointerInput() = Unit

    // This node observes; it must never take a hit away from a sibling underneath it. Without this,
    // a scope-only wrapper becomes a hit-test target of its own and Compose stops descending to
    // siblings below it, which would change routing rather than observe it.
    override fun sharePointerInputWithSiblings(): Boolean = false

    // For F4: a clickable child drawn outside this wrapper's own bounds. Without this the wrapper is
    // not on the hit path for such a pointer and its scope is lost, though Android's semantics
    // ancestry keeps it.
    override fun interceptOutOfBoundsChildEvents(): Boolean = true
}

/** The scope this node declares directly, or empty. Hit-testing convenience; see [autocaptureScope]. */
internal fun SemanticsConfiguration.autocaptureScopeOrEmpty(): JsonObject =
    getOrNull(AutographScopeKey) ?: EmptyJsonObject
