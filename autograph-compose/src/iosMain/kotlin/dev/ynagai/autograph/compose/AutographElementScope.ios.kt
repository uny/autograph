package dev.ynagai.autograph.compose

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
 * Over the ceiling the marker is dropped whole rather than truncated: half a JSON object does not
 * parse, so truncating would produce a wrapper that publishes an accessibility container and a
 * reserved identifier while contributing no scope, which is the cost without the benefit. Dropping it
 * degrades to exactly what happens with no wrapper at all.
 */
private const val MAX_SCOPE_PAYLOAD_LENGTH = 2048

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
 */
@OptIn(AutographInternalApi::class)
internal actual fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier {
    val payload = Json.encodeToString(JsonObject.serializer(), properties)
    if (payload.length > MAX_SCOPE_PAYLOAD_LENGTH) return this
    return this then IosScopeMarkerElement(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX + payload)
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
