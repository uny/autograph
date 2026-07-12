package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/**
 * Resolves a tap at [position] (in [root]'s local coordinate space) to an autocapture target
 * identifier, or null if the tap didn't land on a clickable, non-ignored element.
 *
 * Implemented per-platform: Android hit-tests the semantics tree via `RootForTest`; iOS hit-tests
 * the UIKit accessibility bridge (`UIView.accessibilityElements`) instead, since there's no
 * supported way to reach a `SemanticsOwner` from application code on iOS — see ElementResolver.ios.kt
 * for why. JVM currently provides a no-op stub (taps are silently not captured on that target).
 */
internal fun interface ElementResolver {
    fun resolve(root: LayoutCoordinates, position: Offset): String?
}

/** Creates an [ElementResolver] bound to the current platform's UI tree. */
@Composable
internal expect fun rememberElementResolver(): ElementResolver
