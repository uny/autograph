package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView

/**
 * Hit-tests the semantics tree via `RootForTest` — the same opt-in public entry point Compose's
 * own `androidx.compose.ui.test` and third-party autocapture SDKs (PostHog, Embrace) use, so this
 * doesn't reach into any internal Compose type.
 *
 * Uses [androidx.compose.ui.semantics.SemanticsOwner.getUnmergedRootSemanticsNode], not the merged
 * tree ([androidx.compose.ui.semantics.SemanticsOwner.getRootSemanticsNode]): [resolveAutocaptureTarget]
 * walks node-by-node expecting each [AutocaptureNode] to reflect only that node's own semantics —
 * on the merged tree a descendant's [AutographInstrumentedKey]/[AutographScopeKey]/testTag folds
 * into its clickable ancestor's `config`, which would corrupt the dedup walk, the identifier pick,
 * and which element a scope is read as sitting on. See [toAutocaptureNode].
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun rememberElementResolver(): ElementResolver {
    val view = LocalView.current
    return remember(view) {
        ElementResolver { root, position ->
            val rootForTest = view as? RootForTest ?: return@ElementResolver null
            val windowPosition = root.localToWindow(position)
            val hit = findDeepestHit(
                root = rootForTest.semanticsOwner.unmergedRootSemanticsNode,
                point = windowPosition,
                bounds = { it.boundsInWindow },
                children = { it.children },
            ) ?: return@ElementResolver null
            resolveAutocaptureTarget(hit.selfAndAncestors().map { it.toAutocaptureNode() })
        }
    }
}
