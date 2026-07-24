package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

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
)

/**
 * Walks [chain] — the hit node, then its ancestors, innermost first — to find the identifier
 * autocapture should attribute a tap to: the nearest clickable node's [AutocaptureNode.identifier].
 * [ignored] is subtree-wide ([autographIgnore] on ANY node from the hit node up to the composition
 * root excludes the whole tap, even above the clickable that would otherwise be picked), whereas
 * [instrumented] only vetoes the walk when it reaches the node it would otherwise return — already
 * instrumented via [trackClick] / [trackImpression] and would otherwise be double-reported.
 */
internal fun resolveAutocaptureTarget(chain: Sequence<AutocaptureNode>): String? {
    val nodes = chain.toList()
    if (nodes.any { it.ignored }) return null
    val nearestClickable = nodes.firstOrNull { it.clickable } ?: return null
    return if (nearestClickable.instrumented) null else nearestClickable.identifier
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
