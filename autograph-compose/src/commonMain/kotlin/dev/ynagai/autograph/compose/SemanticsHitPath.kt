package dev.ynagai.autograph.compose

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
    clickable = config.getOrNull(SemanticsActions.OnClick) != null,
    ignored = config.isAutocaptureIgnored(),
    instrumented = config.isAutocaptureInstrumented(),
    scope = config.autocaptureScopeOrEmpty(),
)
