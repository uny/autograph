package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView

/**
 * Hit-tests the semantics tree via `RootForTest` — the same opt-in public entry point Compose's
 * own `androidx.compose.ui.test` and third-party autocapture SDKs (PostHog, Embrace) use, so this
 * doesn't reach into any internal Compose type. The walk itself is [resolveTapAt], in commonMain, so
 * `compose.uiTest` on the JVM exercises this exact sequence against a live composition.
 *
 * Uses [androidx.compose.ui.semantics.SemanticsOwner.getUnmergedRootSemanticsNode], not the merged
 * tree ([androidx.compose.ui.semantics.SemanticsOwner.getRootSemanticsNode]): [resolveAutocaptureTarget]
 * walks node-by-node expecting each [AutocaptureNode] to reflect only that node's own semantics —
 * on the merged tree a descendant's [AutographInstrumentedKey]/[AutographScopeKey]/testTag folds
 * into its clickable ancestor's `config`, which would corrupt the dedup walk, the identifier pick,
 * and which element a scope is read as sitting on. See [toAutocaptureNode].
 *
 * Converts with `localToRoot`, not `localToWindow`, because [resolveTapAt] hit-tests
 * `boundsInRoot`/`touchBoundsInRoot` — the expanded minimum touch target is published in root space
 * only. The two spaces coincide only when the composition sits at the window origin, so this line and
 * the walk have to agree; `autograph-compose` declares no Android host-test source set, so nothing
 * here runs in a test. What keeps the conversion and the searched tree in the same coordinate system
 * is that both come from this composition: [rootCoordinates][androidx.compose.ui.layout.LayoutCoordinates]
 * is captured by `onGloballyPositioned` on the very modifier chain this resolver is remembered in, so
 * it belongs to the same `SemanticsOwner` as [LocalView]. A `Popup` or `Dialog` composes into its own
 * view and its own owner, and installs its own observer along with it.
 */
@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun rememberElementResolver(): ElementResolver {
    val view = LocalView.current
    return remember(view) {
        ElementResolver { root, position ->
            val rootForTest = view as? RootForTest ?: return@ElementResolver null
            rootForTest.semanticsOwner.unmergedRootSemanticsNode.resolveTapAt(root.localToRoot(position))
        }
    }
}
