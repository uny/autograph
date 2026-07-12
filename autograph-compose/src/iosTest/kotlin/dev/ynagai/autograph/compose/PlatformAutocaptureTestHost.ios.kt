package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.uikit.LocalUIView
import platform.UIKit.UIView

/**
 * `compose.uiTest`'s iOS scene doesn't back onto a real `ComposeUIViewController`, so `LocalUIView`
 * is left unprovided — this provides a bare, unattached [UIView] instead so [rememberElementResolver]
 * doesn't crash reading it. Compose never actually populates this view's (or its subviews')
 * `accessibilityElements` (nothing renders into it), so autocapture won't resolve a target through
 * it — these tests only cover [AutographProvider]'s composition/click-delivery wiring, not resolver
 * hit-testing (that's covered on-device instead — see ElementResolver.ios.kt's kdoc).
 */
@Composable
internal actual fun PlatformAutocaptureTestHost(content: @Composable () -> Unit) {
    val view = remember { UIView() }
    CompositionLocalProvider(LocalUIView provides view, content = content)
}
