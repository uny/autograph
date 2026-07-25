package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject

/**
 * The element a tap at [point] should be attributed to: the innermost — and among equals, the
 * visually topmost — node that is itself [clickable] and whose OWN [bounds] contain the point.
 *
 * Unlike [findDeepestHit] this does **not** stop at a subtree whose parent misses the point, because
 * Compose does not either: a `clickable` drawn outside its parent (an overhanging badge, a
 * `Modifier.offset` decoration, `requiredSize` past the parent's constraints) still receives the real
 * pointer. What keeps that from misattributing is the other half of the rule — a node only takes the
 * tap if the point is inside *that node*, so a subtree is entered but wins nothing unless it actually
 * contains a clickable under the point. A non-clickable decoration overhanging into a neighbouring
 * row therefore yields nothing and the neighbour keeps its own tap.
 *
 * This approximates Compose's pointer routing over the unmerged semantics tree; it does not
 * reproduce it. Three known divergences, all pre-existing and none introduced here: an element's
 * touch target is expanded past its bounds below `minimumInteractiveComponentSize`
 * ([#127](https://github.com/uny/autograph/issues/127)); `clickable(enabled = false)` still publishes
 * the click action, so a disabled element takes the tap and is reported
 * ([#128](https://github.com/uny/autograph/issues/128)) — it is left taking it deliberately, since it
 * really does consume the pointer and skipping it would report an element the tap never reached; and
 * a bare `semantics { onClick { } }` carries no pointer input at all yet is indistinguishable from
 * `Modifier.clickable` here.
 *
 * Generic over the platform's UI tree node type ([T]) — e.g. `SemanticsNode` — so the geometry is
 * testable without any platform tree.
 */
internal fun <T> findClickableHit(
    root: T,
    point: Offset,
    bounds: (T) -> Rect,
    children: (T) -> List<T>,
    clickable: (T) -> Boolean,
): T? {
    for (child in children(root).asReversed()) {
        findClickableHit(child, point, bounds, children, clickable)?.let { return it }
    }
    return if (clickable(root) && bounds(root).contains(point)) root else null
}

/**
 * Depth-first search for the most specific (innermost) node whose [bounds] contain [point],
 * preferring later (visually on top) siblings when bounds overlap. Generic over the platform's UI
 * tree node type ([T]) — e.g. Android's `SemanticsNode` — so the geometry itself is testable
 * without any platform tree.
 *
 * Refuses to descend past a node whose own bounds miss the point. Run from the node
 * [findClickableHit] picked, that prune is what bounds the chain [resolveAutocaptureTarget] then
 * walks: every node it returns below that element is one the point is genuinely inside, so a scope
 * or an [autographIgnore] collected from *below* the reported element belongs to the tap. Run from
 * the root it would instead model drawing, and an overhanging decoration would hand its own row's
 * tap to whichever element the walk then resolved up to — which is why the entry point is
 * [findClickableHit] and not this.
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
