package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject

/**
 * Depth-first search for the most specific (innermost) node whose [bounds] contain [point],
 * preferring later (visually on top) siblings when bounds overlap. Generic over the platform's UI
 * tree node type ([T]) — e.g. Android's `SemanticsNode` — so the geometry itself is testable
 * without any platform tree.
 */
internal fun <T> findDeepestHit(root: T, point: Offset, bounds: (T) -> Rect, children: (T) -> List<T>): T? {
    if (!bounds(root).contains(point)) return null
    for (child in children(root).asReversed()) {
        findDeepestHit(child, point, bounds, children)?.let { return it }
    }
    return root
}

/**
 * One node along the path from a tapped element up to the composition root — enough for
 * [resolveAutocaptureTarget] to decide whether/how to attribute a tap without depending on any
 * platform UI-tree type. Built by each platform's [ElementResolver] from its own hit-test result.
 */
internal data class AutocaptureNode(
    val identifier: String?,
    val clickable: Boolean,
    val ignored: Boolean,
    val instrumented: Boolean,
    val scope: JsonObject = EmptyJsonObject,
)

/**
 * What a resolved tap is reported as: the element's [identifier], plus the [scope] the tapped
 * element's own ancestry declared through [autocaptureScope].
 *
 * The two travel together because they must come from the *same* hit test — deriving the scope
 * separately (from mount order, or from a positional registry consulted after the fact) is what
 * lets a tap be reported against one element with another element's scope.
 */
internal data class AutocaptureTarget(
    val identifier: String,
    val scope: JsonObject = EmptyJsonObject,
)

/**
 * Walks [chain] — the hit node, then its ancestors, innermost first — to find the identifier
 * autocapture should attribute a tap to: the nearest clickable node's [AutocaptureNode.identifier].
 * [ignored] is subtree-wide ([autographIgnore] on ANY node from the hit node up to the composition
 * root excludes the whole tap, even above the clickable that would otherwise be picked), whereas
 * [instrumented] only vetoes the walk when it reaches the node it would otherwise return — already
 * instrumented via [trackClick] / [trackImpression] and would otherwise be double-reported.
 *
 * [AutocaptureNode.scope] is subtree-wide like [ignored], and for the same reason: [autocaptureScope]
 * says "taps under here carry this", so every node on the chain contributes, including one *below*
 * the clickable that gets reported. Because the chain is a single ancestry path, its scopes are never
 * ambiguous the way simultaneously-mounted `AutographScope` siblings are — they nest by construction,
 * and merge outer→inner with the inner winning a key clash, exactly as nested scopes compose
 * everywhere else.
 */
internal fun resolveAutocaptureTarget(chain: Sequence<AutocaptureNode>): AutocaptureTarget? {
    val nodes = chain.toList()
    if (nodes.any { it.ignored }) return null
    val nearestClickable = nodes.firstOrNull { it.clickable } ?: return null
    if (nearestClickable.instrumented) return null
    val identifier = nearestClickable.identifier ?: return null
    // asReversed() is root→hit node, so a deeper frame's entry lands later and wins the clash.
    val scope = nodes.asReversed().fold(EmptyJsonObject) { acc, node ->
        when {
            node.scope.isEmpty() -> acc
            acc.isEmpty() -> node.scope
            else -> JsonObject(acc + node.scope)
        }
    }
    return AutocaptureTarget(identifier, scope)
}

/**
 * Identifier priority: [testTag] first (explicit, stable), then [role], then [label] (a11y text).
 *
 * A blank value is treated as absent so it falls through the chain rather than reporting an empty
 * target (a `testTag("")` most often arrives from a template or a nil-coalesced binding, not a
 * deliberate name). Non-blank values pass through byte-for-byte — never trimmed. Mirrors the iOS
 * native decision in #79 (blank accessibility identifiers are dropped there too).
 */
internal fun identifierFrom(testTag: String?, role: String?, label: String?): String? =
    testTag?.takeIf { it.isNotBlank() }
        ?: role?.takeIf { it.isNotBlank() }
        ?: label?.takeIf { it.isNotBlank() }
