@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.autographScopeOrigin

/**
 * Walks up from the composition's host view to the nearest view a native surface has claimed —
 * the fragment's root, or the Activity's content — which `installAutographNativeScreenCapture`
 * tags with that surface's frame. The walk is a few parents deep and runs per tap; see
 * [ProviderOrigin] for why it is not done once.
 */
@Composable
internal actual fun rememberHostSurfaceLookup(): () -> ScopeHandle? {
    val view = LocalView.current
    return remember(view) { { view.autographScopeOrigin() } }
}
