package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * The tapped node, then its ancestors, in the shape [resolveAutocaptureTarget] consumes.
 *
 * Lives in commonMain even though only [ElementResolver.android.kt] hit-tests a semantics tree today
 * (iOS has no supported route to a `SemanticsOwner` from application code, so it goes through the
 * UIKit accessibility bridge instead). `SemanticsNode` itself is a common Compose type, and keeping
 * the mapping here is what lets `compose.uiTest` exercise the *real* read path on the JVM — fetch a
 * node from a live composition and run these functions over it — rather than leaving it covered only
 * by hand-built [AutocaptureNode] fixtures that cannot notice a semantics key that stopped arriving.
 */
internal fun SemanticsNode.selfAndAncestors(): Sequence<SemanticsNode> = generateSequence(this) { it.parent }

/**
 * Resolves a tap at [rootPosition] against this (unmerged) semantics tree, in two stages:
 *
 * 1. [findClickableHit] picks the element the tap is attributed to, ranking every clickable the point
 *    reaches — through its own bounds or through its expanded minimum touch target — by the rules
 *    observed from Compose's own hit test.
 * 2. [findDeepestHit] runs from *that* element to find how far below it the point still lands, so
 *    that an [autocaptureScope] or [autographIgnore] declared on a leaf inside the reported element
 *    keeps contributing (it is subtree-wide by design — see [resolveAutocaptureTarget]).
 *
 * The chain [resolveAutocaptureTarget] then walks therefore has the reported element in the middle:
 * below it only nodes the point is inside, above it the element's own ancestry. Its "nearest
 * clickable" is necessarily the element stage 1 picked — anything clickable below it and under the
 * point would have been returned by stage 1 first, being deeper. Stage 2 keeps testing *drawn*
 * bounds, so when the tap landed in the reported element's expanded margin it descends nowhere and
 * only that element's own ancestry contributes: a scope on a leaf inside a small icon applies to taps
 * on the icon, not to taps beside it.
 *
 * The position is in the composition root's coordinate space, matching [SemanticsNode.boundsInRoot]
 * and [SemanticsNode.touchBoundsInRoot] — the latter is published in root space only, which is why
 * the walk works there rather than in window space.
 *
 * Lives here, next to the mapping it feeds, so that the Android resolver and `compose.uiTest` on the
 * JVM run the *same* sequence rather than two copies that can drift apart.
 */
internal fun SemanticsNode.resolveTapAt(rootPosition: Offset): AutocaptureTarget? {
    val hit = findClickableHit(
        root = this,
        point = rootPosition,
        bounds = { it.boundsInRoot },
        minTargetDistanceSquared = { node, point -> node.minTargetDistanceSquared(point) },
        children = { it.children },
        clickable = { it.isClickable() },
    ) ?: return null
    val deepest = findDeepestHit(
        root = hit,
        point = rootPosition,
        bounds = { it.boundsInRoot },
        children = { it.children },
    ) ?: hit
    return resolveAutocaptureTarget(deepest.selfAndAncestors().map { it.toAutocaptureNode() })
}

/**
 * How far outside this node's drawn rect [point] fell, squared and measured in the node's OWN local
 * space, or null if the point is not in its expanded minimum touch target at all.
 *
 * Three things here follow Compose rather than being choices. Each was checked against the element
 * whose `onClick` actually fired, over the fixtures named below and the sweeps in the tests:
 * - **Only a node below the minimum touch target on some axis qualifies.** Compose compares the
 *   coordinator's *measured* size to `ViewConfiguration.minimumTouchTargetSize` and gives up
 *   entirely when neither axis falls short, so a 120dp clickable is not hit at even one pixel outside
 *   its bounds while a 16dp one is. Reading the measured [SemanticsNode.size] rather than comparing
 *   rect widths is load-bearing: `touchBoundsInRoot` and `boundsInRoot` also differ when the node is
 *   scaled or ancestor-clipped, which would otherwise let a large transformed element claim taps
 *   beside it.
 * - **The far edge of the expanded rect is inclusive**, unlike [Rect.contains]. A 16dp element at
 *   x 60..76 fires at exactly x = 92 and not at 92.001.
 * - **The distance is measured in the node's own space.** Compose transforms the pointer into the
 *   candidate's coordinates before measuring, so ranking two differently scaled candidates by their
 *   root-space distances inverts the winner (measured: a 3x-scaled 16dp element against a plain one).
 *   Each axis is scaled by the ratio of the measured extent to the drawn one. That ratio also absorbs
 *   an ancestor clip, which is not a transform — and it should, because `touchBoundsInRoot` is grown
 *   from the clipped rect too, so the two share a centre (measured on a half-clipped element).
 *
 * [SemanticsNode.boundsInRoot] is the drawn rect, transformed and ancestor-clipped. It is empty both
 * for a node clipped entirely out of view — which Compose still routes to through the expanded target
 * when nothing else is hit — and for one measured to zero on an axis; the untransformed rect stands
 * in for both, which is exact when nothing is scaled.
 *
 * Reconstructing the transform from axis-aligned rectangles cannot be exact. Measured, the residue is
 * confined to an element that is BOTH scaled and ancestor-clipped, where the expanded region itself
 * lands in the wrong place: over one such fixture it costs phantom and dropped taps, and no
 * misattribution — the direction this library errs in deliberately.
 */
private fun SemanticsNode.minTargetDistanceSquared(point: Offset): Float? {
    val minimum = layoutInfo.viewConfiguration.minimumTouchTargetSize
    val density = layoutInfo.density
    val qualifies = size.width < with(density) { minimum.width.toPx() } ||
        size.height < with(density) { minimum.height.toPx() }
    if (!qualifies || !touchBoundsInRoot.containsInclusive(point)) return null
    val drawn = if (boundsInRoot.isEmpty) {
        Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))
    } else {
        boundsInRoot
    }
    var dx = maxOf(drawn.left - point.x, 0f, point.x - drawn.right)
    var dy = maxOf(drawn.top - point.y, 0f, point.y - drawn.bottom)
    if (size.width > 0 && drawn.width > 0) dx *= size.width / drawn.width
    if (size.height > 0 && drawn.height > 0) dy *= size.height / drawn.height
    return dx * dx + dy * dy
}

/** [Rect.contains] excludes the right and bottom edges; Compose's minimum-target test includes them. */
private fun Rect.containsInclusive(point: Offset): Boolean =
    point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

/**
 * Whether this node takes a tap. Shared by the hit test's first stage and [toAutocaptureNode] so the
 * element stage 1 stops at is the same one [resolveAutocaptureTarget] then reports — reading the
 * click action differently in the two places would break that correspondence silently.
 */
private fun SemanticsNode.isClickable(): Boolean = config.getOrNull(SemanticsActions.OnClick) != null

/**
 * Reads one node's own autocapture-relevant semantics. Must be applied to nodes of the **unmerged**
 * tree: on the merged tree a descendant's testTag/[AutographInstrumentedKey]/[AutographScopeKey]
 * folds into its clickable ancestor's `config`, which would both corrupt the identifier pick and
 * make a scope declared on a leaf appear to sit on the element above it.
 */
internal fun SemanticsNode.toAutocaptureNode(): AutocaptureNode = AutocaptureNode(
    identifier = identifierFrom(
        testTag = config.getOrNull(SemanticsProperties.TestTag),
        role = config.getOrNull(SemanticsProperties.Role)?.toString(),
        label = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
    ),
    clickable = isClickable(),
    ignored = config.isAutocaptureIgnored(),
    instrumented = config.isAutocaptureInstrumented(),
    // `clickable(enabled = false)` publishes Disabled alongside the click action, which is what lets
    // resolveAutocaptureTarget tell a real click from one that never fired.
    disabled = config.getOrNull(SemanticsProperties.Disabled) != null,
    scope = config.autocaptureScopeOrEmpty(),
)
