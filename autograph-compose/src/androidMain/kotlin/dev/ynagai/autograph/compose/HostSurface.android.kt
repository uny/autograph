@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.compose

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.autographScopeOrigin
import dev.ynagai.autograph.context.autographScopeOwner

/**
 * Walks up from the composition's host view's **parent** to the nearest view a native surface has
 * claimed — the fragment's root, or the Activity's decor — which `installAutographNativeScreenCapture`
 * tags with that surface's frame. From the parent, because the host view itself carries this
 * composition's own claim ([ClaimCompositionHost]). The walk is a few parents deep and runs per
 * tap; see [ProviderOrigin] for why it is not done once.
 */
@Composable
internal actual fun rememberHostSurfaceLookup(): () -> ScopeHandle? {
    val view = LocalView.current
    return remember(view) { { (view.parent as? View)?.autographScopeOrigin() } }
}

@Composable
internal actual fun ClaimCompositionHost(origin: ProviderOrigin) {
    val view = LocalView.current
    DisposableEffect(view, origin) {
        // The frame is pushed by ProviderFrame's own effect, which ran first (it composed first).
        val frame = origin.frame
        view.autographScopeOwner = frame
        onDispose {
            // Only our own claim: a view recycled under a new composition may already carry its.
            if (view.autographScopeOwner === frame) view.autographScopeOwner = null
        }
    }
}
