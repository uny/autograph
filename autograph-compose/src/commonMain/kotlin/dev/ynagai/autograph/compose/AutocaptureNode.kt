package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject

/**
 * The element a tap at [point] should be attributed to: the first node that is itself [clickable]
 * and whose OWN [bounds] contain the point, searched children-before-self and taking each sibling's
 * whole subtree in visual order, topmost first.
 *
 * So the topmost branch wins outright — even where a branch beneath it holds a *deeper* clickable —
 * and within the winning branch the innermost clickable takes the tap. Depth decides only inside one
 * branch; between branches, stacking does. That is the order Compose routes a pointer in, and it is
 * why an element covered by a clickable overlay does not receive the tap however deep it sits.
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
 * reproduce it. Two known divergences in this walk, both pre-existing: an element's
 * touch target is expanded past its bounds below `minimumInteractiveComponentSize`
 * ([#127](https://github.com/uny/autograph/issues/127)), and a bare `semantics { onClick { } }`
 * carries no pointer input at all yet is indistinguishable from `Modifier.clickable` here, so it
 * takes a tap that Compose routes straight through it.
 *
 * A `clickable(enabled = false)` is deliberately still allowed to take the tap: it consumes the
 * pointer exactly as Compose does, so letting the walk fall through it would report an element the
 * tap never reached. [resolveAutocaptureTarget] vetoes it afterwards instead, so the tap resolves to
 * nothing ([#128](https://github.com/uny/autograph/issues/128)).
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
 * platform UI-tree type.
 *
 * Built from a semantics hit test, which today means the Android [ElementResolver] and the
 * `compose.uiTest` suite that shares its path. iOS resolves over the UIKit accessibility bridge and
 * never builds one of these, so every statement here about what a node does — including the
 * pointer-consumption reasoning behind [resolveAutocaptureTarget]'s vetoes — describes the Android
 * read path, not autocapture on every platform.
 */
internal data class AutocaptureNode(
    val identifier: String?,
    val clickable: Boolean,
    val ignored: Boolean,
    val instrumented: Boolean,
    val disabled: Boolean,
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
 * [disabled] vetoes on the returned node only, like [instrumented] and for a measured reason.
 * `clickable(enabled = false)` publishes the click action alongside `Disabled`, so a disabled
 * element is picked as the nearest clickable and would otherwise be reported as a click that never
 * fired. It still has to *take* the tap in the hit test — it consumes the pointer exactly as Compose
 * does, blocking both the sibling beneath it and its own enabled clickable ancestor (measured: in
 * `Box(clickable) { Box(clickable(enabled = false)) }` Compose fires nothing) — so vetoing here
 * rather than skipping it in the walk is what keeps the answer a drop instead of naming an element
 * the tap never reached. Confining the veto to the returned node is equally deliberate: a disabled
 * *ancestor* does not block an enabled clickable child (measured), so it must not suppress one.
 *
 * The veto reads a semantics property, not the pointer input behind it, so it costs one shape it
 * cannot tell apart: a live `Modifier.clickable` whose semantics were hand-marked `disabled()` does
 * fire and is dropped here. That is the divergence from Compose this introduces, and the direction
 * to err in — the alternative reports taps that never happened.
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
    if (nearestClickable.instrumented || nearestClickable.disabled) return null
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
