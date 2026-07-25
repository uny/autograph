package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/**
 * Resolves a tap at [position] (in [root]'s local coordinate space) to an autocapture target — its
 * identifier and the [autocaptureScope] its ancestry declared — or null if the tap didn't land on a
 * clickable, non-ignored element.
 *
 * Implemented per-platform: Android hit-tests the semantics tree via `RootForTest`; iOS hit-tests
 * the UIKit accessibility bridge (`UIView.accessibilityElements`) instead, since there's no
 * supported way to reach a `SemanticsOwner` from application code on iOS — see ElementResolver.ios.kt
 * for why. JVM currently provides a no-op stub (taps are silently not captured on that target).
 *
 * The scope rides on the resolver's result rather than being looked up afterwards so that it comes
 * from the same hit test the identifier did; see [AutocaptureTarget]. Only Android can supply one
 * today — the UIKit bridge carries no custom semantics — so iOS returns an empty scope and its taps
 * keep resolving exactly as they did before [autocaptureScope] existed.
 */
internal fun interface ElementResolver {
    fun resolve(root: LayoutCoordinates, position: Offset): AutocaptureTarget?
}

/** Creates an [ElementResolver] bound to the current platform's UI tree. */
@Composable
internal expect fun rememberElementResolver(): ElementResolver
