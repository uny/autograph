package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
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
 * Resolves a tap at [windowPosition] against this (unmerged) semantics tree, in two stages:
 *
 * 1. [findClickableHit] picks the element the tap is attributed to — the first node that is itself
 *    clickable and whose own bounds contain the point, taking the visually topmost branch first and
 *    the innermost such node within it.
 * 2. [findDeepestHit] runs from *that* element to find how far below it the point still lands, so
 *    that an [autocaptureScope] or [autographIgnore] declared on a leaf inside the reported element
 *    keeps contributing (it is subtree-wide by design — see [resolveAutocaptureTarget]).
 *
 * The chain [resolveAutocaptureTarget] then walks therefore has the reported element in the middle:
 * below it only nodes the point is inside, above it the element's own ancestry. Its "nearest
 * clickable" is necessarily the element stage 1 picked — anything clickable below it and under the
 * point would have been returned by stage 1 first, being deeper.
 *
 * Lives here, next to the mapping it feeds, so that the Android resolver and `compose.uiTest` on the
 * JVM run the *same* sequence rather than two copies that can drift apart.
 */
internal fun SemanticsNode.resolveTapAt(windowPosition: Offset): AutocaptureTarget? {
    val hit = findClickableHit(
        root = this,
        point = windowPosition,
        bounds = { it.boundsInWindow },
        children = { it.children },
        clickable = { it.isClickable() },
    ) ?: return null
    val deepest = findDeepestHit(
        root = hit,
        point = windowPosition,
        bounds = { it.boundsInWindow },
        children = { it.children },
    ) ?: hit
    return resolveAutocaptureTarget(deepest.selfAndAncestors().map { it.toAutocaptureNode() })
}

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
    scope = config.autocaptureScopeOrEmpty(),
)
