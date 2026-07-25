package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
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
 * **Prefer the clickable element itself over a wrapper.** Putting this on a wrapper adds that
 * wrapper to the semantics tree, and the hit test does not descend past a node whose bounds miss the
 * tap — so a `clickable` drawn *outside* the wrapper (an overhanging badge, an `offset` decoration)
 * stops being autocaptured, even though its `onClick` still fires. A dropped tap, never a wrong one:
 * descending anyway was measured and hands an overhang's tap to the row it covers rather than the
 * row that was clicked. See [#126](https://github.com/uny/autograph/issues/126).
 *
 * **Android only, for now.** Resolution reads the marker back off the tapped element's ancestry in
 * the semantics tree — the same chain the tap's `target` is picked from. That is the guarantee worth
 * stating: the scope always describes *the element that was actually hit*, because the two are read
 * together. Where the hit test itself misattributes — `Modifier.clip` cannot be expressed as a rect,
 * so a tap in the corner of a rounded clip may land on the wrong element, see [AutocaptureConfig] —
 * the scope follows the target and is wrong with it, rather than contradicting it. Resolving the
 * scope any other way (a positional registry of last-known bounds, or mount order) admits the worse
 * failure instead: a correct `target` carrying a *neighbouring* element's scope, silently, whenever
 * bounds go stale mid-animation.
 *
 * Compose Multiplatform's
 * iOS accessibility bridge carries none of the custom semantics an element declares, so on iOS this
 * modifier currently contributes nothing and taps keep resolving as they did before it
 * ([AutographScope] siblings there stay ambiguous and drop, per `ScopeStack`). The failure is a
 * missing property rather than a wrong one, which is the tradeoff this library takes deliberately
 * — see [#68](https://github.com/uny/autograph/issues/68). Expect an `article_id` on Android and no
 * `article_id` on iOS for the same tap until that gap closes.
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
 * A `data class` element compares [properties] by value instead, and [JsonObject] is structurally
 * equal, so re-emitting the same scope is a no-op. Two of these on one modifier chain still collapse
 * to the outermost, exactly as two `semantics {}` blocks would — see [autocaptureScope].
 */
private data class AutocaptureScopeElement(
    val properties: JsonObject,
) : ModifierNodeElement<AutocaptureScopeNode>() {
    override fun create(): AutocaptureScopeNode = AutocaptureScopeNode(properties)

    override fun update(node: AutocaptureScopeNode) {
        node.properties = properties
        // The scope is read off the semantics tree, so a changed value has to reach it — a stale one
        // would report a real tap against the row's *previous* data, the one failure this design
        // exists to rule out.
        node.invalidateSemantics()
    }
}

private class AutocaptureScopeNode(var properties: JsonObject) : Modifier.Node(), SemanticsModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AutographScopeKey] = properties
    }
}

/** The scope this node declares directly, or empty. Hit-testing convenience; see [autocaptureScope]. */
internal fun SemanticsConfiguration.autocaptureScopeOrEmpty(): JsonObject =
    getOrNull(AutographScopeKey) ?: EmptyJsonObject
