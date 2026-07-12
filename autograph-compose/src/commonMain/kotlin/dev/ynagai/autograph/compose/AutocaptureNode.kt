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
    val skipped: Boolean,
)

/**
 * Walks [chain] — the hit node, then its ancestors, innermost first — to find the identifier
 * autocapture should attribute a tap to: the nearest clickable node's [AutocaptureNode.identifier].
 * Returns null (skip entirely) if any node from the hit node up to that clickable is [skipped] —
 * either explicitly via [autographIgnore] or because it's already instrumented via [trackClick] /
 * [trackImpression] and would otherwise be double-reported.
 */
internal fun resolveAutocaptureTarget(chain: Sequence<AutocaptureNode>): String? {
    for (node in chain) {
        if (node.skipped) return null
        if (node.clickable) return node.identifier
    }
    return null
}

/** Identifier priority: [testTag] first (explicit, stable), then [role], then [label] (a11y text). */
internal fun identifierFrom(testTag: String?, role: String?, label: String?): String? =
    testTag ?: role ?: label
