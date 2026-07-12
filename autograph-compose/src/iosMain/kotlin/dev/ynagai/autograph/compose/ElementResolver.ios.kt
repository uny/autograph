package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Autocapture's iOS resolver — walking `LocalUIView.current.accessibilityElements`, the native
 * UIKit accessibility tree Compose Multiplatform bridges its semantics tree into — is planned as
 * a follow-up; for now taps are observed but never resolve to a target on iOS.
 */
@Composable
internal actual fun rememberElementResolver(): ElementResolver = remember { ElementResolver { _, _ -> null } }
