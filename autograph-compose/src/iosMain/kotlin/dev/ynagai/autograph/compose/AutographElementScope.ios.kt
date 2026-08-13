package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.testTag
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.uikit.AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ceiling on the encoded scope, in characters, counted after the prefix.
 *
 * The payload is copied through Compose's semantics and then UIKit's accessibility tree, and parsed
 * **synchronously on the main thread inside a tap handler** every time a scoped element is tapped, so
 * its size is a latency budget rather than a storage one. Well above any plausible scope — a handful
 * of ids — and far below anything that would be felt.
 *
 * **Applied on both sides, and the reader's side is the one that has to hold.** This writer is not the
 * only writer of a reserved identifier: the identifier slot belongs to the host app, so anything in the
 * process — an app view, a third-party SDK behind a `UIKitView` — can put the prefix on a node the walk
 * reaches. A ceiling enforced only here would be enforced only where it was never needed; `scopeOnPath`
 * applies it too, which is what actually bounds the per-tap parse and what keeps an arbitrarily large
 * foreign payload out of the event.
 *
 * Over the ceiling the marker is dropped whole rather than truncated: half a JSON object does not
 * parse, so truncating would produce a wrapper that publishes an accessibility container and a
 * reserved identifier while contributing no scope, which is the cost without the benefit. Dropping it
 * degrades to exactly what happens with no wrapper at all — silently, which is why crossing it prints
 * [warnScopePayloadTooLarge].
 *
 * The number itself is **not measured**. It was chosen as an order of magnitude above any scope this
 * API is for and an order of magnitude below a payload that could plausibly be felt in a tap handler,
 * and nothing about the parse cost at either end has been timed. Treat it as a guard against the
 * pathological, not as a tuned budget: if a real scope ever approaches it, measure before raising it.
 */
internal const val MAX_SCOPE_PAYLOAD_LENGTH = 2048

/**
 * Printed once per process, the first time a scope is dropped for size.
 *
 * To the console rather than `AutographConfig.logger`, following [LocalTracker]'s missing-tracker
 * warning: this is a develop-time report that a call site is wrong, not a diagnostic of a running
 * tracker, and it has to work whether or not a tracker was ever provided.
 *
 * Once per *process*, not once per node: the scope wrapper's whole reason to exist is lists, so a
 * single oversized row composable would otherwise print once per visible row and again on every
 * scroll. One line naming the size and the ceiling is enough to act on, and the alternative floods
 * the log it is trying to be seen in.
 */
private var scopePayloadTooLargeWarned = false

private fun warnScopePayloadTooLarge(length: Int) {
    if (scopePayloadTooLargeWarned) return
    scopePayloadTooLargeWarned = true
    println(
        "Autograph: an AutographElementScope was dropped — its encoded properties are $length " +
            "characters, over the $MAX_SCOPE_PAYLOAD_LENGTH-character ceiling. Taps inside it are " +
            "still reported, but without the scope. Scopes are for identifiers, not payloads. " +
            "(Printed once per process; other oversized scopes are dropped silently.)",
    )
}

/**
 * Publishes the scope as an accessibility container carrying a reserved identifier — the one route a
 * per-element value has to a tapped element on Compose Multiplatform iOS.
 *
 * `isTraversalGroup` is what makes it work and is not decoration: measured by control, the same
 * reserved `testTag` *without* it is bridged as a flat sibling of the clickable rather than its
 * ancestor, and the walk then has no ancestry to read the scope off. It is also the whole
 * accessibility cost of this API — see [AutographElementScope]'s kdoc for what was measured.
 *
 * `testTag` rather than a custom semantics key because the UIKit bridge carries only label, traits,
 * identifier and frame; `testTag` is the one of those that is identity-bearing and never spoken.
 *
 * No escaping beyond JSON's own: the payload is a complete JSON document, so quotes, control
 * characters and non-ASCII are already handled, and the reader strips a fixed prefix and parses the
 * remainder. Normalizing keys or values would risk collapsing two scopes the app meant to keep apart.
 *
 * The encode and the size check are `remember`ed on the scope itself. This is the one platform that
 * serializes on the composition path, and the wrapper's target is list rows, so doing it per
 * recomposition would put a JSON encode on every frame of a scroll for every visible scoped row —
 * the same cost on the encode side that [IosScopeMarkerElement] exists to avoid on the semantics one.
 */
@OptIn(AutographInternalApi::class)
@Composable
internal actual fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier {
    val identifier = remember(properties) {
        // An empty scope publishes nothing, for the reason the oversized branch below drops the marker
        // whole: a container with no readable scope is the whole accessibility cost of this API — a
        // rotor stop, an extra node UI tests traversing descendants will count — with none of its
        // benefit, since `scopeOnPath` folds an empty payload away to nothing anyway. Reachable without
        // anyone writing `AutographElementScope { }` on purpose: a scope assembled from values that all
        // turned out absent for this row.
        if (properties.isEmpty()) return@remember null
        val payload = Json.encodeToString(JsonObject.serializer(), properties)
        if (payload.length > MAX_SCOPE_PAYLOAD_LENGTH) {
            warnScopePayloadTooLarge(payload.length)
            null
        } else {
            AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX + payload
        }
    } ?: return this
    return this then IosScopeMarkerElement(identifier)
}

/**
 * A value-equal element rather than `Modifier.semantics {}`, for the reason [autocaptureScope]'s own
 * node documents: a `semantics` lambda capturing the scope compares unequal on every recomposition
 * and re-applies its config each time. [identifier] is a [String], so re-emitting an unchanged scope
 * is a no-op here.
 */
private data class IosScopeMarkerElement(
    val identifier: String,
) : ModifierNodeElement<IosScopeMarkerNode>() {
    override fun create(): IosScopeMarkerNode = IosScopeMarkerNode(identifier)

    override fun update(node: IosScopeMarkerNode) {
        node.identifier = identifier
    }
}

private class IosScopeMarkerNode(var identifier: String) : Modifier.Node(), SemanticsModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        isTraversalGroup = true
        testTag = identifier
    }
}
