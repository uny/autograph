@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.compose

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.context.autographScopeOrigin

/**
 * Walks up from the composition's host view to the nearest view a native surface has claimed —
 * the fragment's root, or the Activity's decor — which `installAutographNativeScreenCapture` tags
 * with that surface's frame. The walk is a few parents deep and runs per tap; see [ProviderOrigin]
 * for why it is not done once.
 */
@Composable
internal actual fun rememberHostSurfaceLookup(): () -> ScopeHandle? {
    val view = LocalView.current
    return remember(view) { { view.autographScopeOrigin() } }
}

@Composable
internal actual fun KeepLinkedToHost(stack: ScopeStack, origin: ProviderOrigin) {
    val view = LocalView.current
    DisposableEffect(view, stack, origin) {
        val link = Runnable { origin.link(stack) }
        view.post(link)
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post(link)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            view.removeCallbacks(link)
        }
    }
}
