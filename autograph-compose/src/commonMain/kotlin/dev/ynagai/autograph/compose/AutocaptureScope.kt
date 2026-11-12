package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject

/** Carries an element's autocapture scope down the semantics tree. See [AutocaptureScopeElement]. */
internal val AutographScopeKey: SemanticsPropertyKey<JsonObject> = SemanticsPropertyKey("AutographScope")

/**
 * The semantics marker behind [AutographElementScope] on Android: it writes the scope's properties
 * under [AutographScopeKey], and the hit test reads them back off the tapped element's ancestry.
 *
 * It used to be public as `Modifier.autocaptureScope`, which put this node on the clickable's own
 * modifier chain. That was removed before 1.0 (#185, #283), because iOS cannot support that shape:
 * iOS carries the scope in the bridged accessibility identifier, which is the same slot the tap's own
 * `target` is read from, and Compose collapses two semantics values on one layout node to the first.
 * The scope needs a layout node of its own, and only a wrapper guarantees it one. On Android
 * [AutographElementScope] applies this very node, on a wrapper of its own.
 *
 * **What resolution guarantees, on Android.** The scope is read off the same semantics ancestry the
 * tap's `target` is picked from, so it always describes *the element that was actually hit*. Where
 * the hit test itself misattributes (`Modifier.clip` cannot be expressed as a rect, so a tap in the
 * corner of a rounded clip may land on the wrong element, see [AutocaptureConfig]), the scope follows
 * the target and is wrong with it, rather than contradicting it. Resolving the scope any other way (a
 * positional registry of last-known bounds, or mount order) admits a worse failure: a correct
 * `target` carrying a *neighbouring* element's scope, silently.
 *
 * Two designs that do **not** work, kept here because both look reasonable:
 *
 * - **Geometry.** A registry of each scope's on-screen bounds, matched against the tapped element's,
 *   does not establish identity, however fresh the bounds are kept. Take two exactly coincident
 *   clickables, an unscoped selection overlay over a scoped card. Compose routes the pointer to the
 *   overlay, so naming the overlay is the *correct* answer for `target`; but the two rectangles are
 *   equal, so the only geometric match for the scope is the card's. The tap would report the right
 *   element carrying its neighbour's scope, and the failure lands exactly when the hit test got the
 *   target right, which leaves this library no signal of its own to catch it with.
 * - **The identifier, on the element's own node.** It is the one bridged iOS property that is both
 *   identity-bearing and never read out to a VoiceOver user, but it is the slot the tap's `target`
 *   comes from, and two semantics values on one layout node collapse to the first. So either the scope
 *   is dropped or the element's `target` is. The label is read aloud, and the traits are a fixed
 *   bitmask with nowhere to put a value.
 *
 * **Why an element of its own** rather than `semantics { this[AutographScopeKey] = properties }`:
 * so that an unchanged scope survives recomposition without work.
 *
 * `Modifier.semantics {}` compares its lambda by *reference*, and a lambda that captures
 * [properties] is a fresh instance on every call — so the element would never equal the previous
 * one, and every recomposition of the element would invalidate its layout node's semantics config.
 * The repo's other semantics markers ([autographIgnore], [trackClick]'s instrumented flag) dodge
 * this without meaning to: their lambdas capture nothing, so the compiler hands out a singleton and
 * they compare equal. This marker is aimed squarely at list rows, where that difference lands.
 *
 * (The Compose compiler memoizes a lambda written inside a composable body when it captures only
 * stable values, so the lambda form only bites when it is built in a plain function, as the removed
 * `Modifier.autocaptureScope` was. A value-equal element does not depend on where it is built.)
 * Measured over ten recompositions with the scope unchanged: this node applies its semantics 0 times,
 * the lambda form built in a plain function 10.
 *
 * A `data class` element compares [properties] by value instead, and [JsonObject] is structurally
 * equal, so re-emitting the same scope is a no-op. Two of these on one layout node collapse to the
 * outermost, exactly as two `semantics {}` blocks would; nested scopes on *different* layout nodes
 * merge along the ancestry, the inner winning a key clash.
 */
internal data class AutocaptureScopeElement(
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

internal class AutocaptureScopeNode(var properties: JsonObject) : Modifier.Node(), SemanticsModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AutographScopeKey] = properties
    }
}

/** The scope this node declares directly, or empty. Hit-testing convenience; see [AutocaptureScopeElement]. */
internal fun SemanticsConfiguration.autocaptureScopeOrEmpty(): JsonObject =
    getOrNull(AutographScopeKey) ?: EmptyJsonObject
