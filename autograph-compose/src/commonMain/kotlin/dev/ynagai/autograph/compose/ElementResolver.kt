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
 * from the same hit test the identifier did; see [AutocaptureTarget]. Both platforms supply one, by
 * different routes: Android reads the custom semantics key off the hit node's ancestry, while the
 * UIKit bridge carries no custom semantics at all and iOS reads a reserved accessibility identifier
 * off a wrapper on the same hit path instead — see `AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX`. What matters
 * to this interface is that neither route derives the scope from anything but the path the
 * identifier came from.
 */
internal fun interface ElementResolver {
    fun resolve(root: LayoutCoordinates, position: Offset): AutocaptureTarget?
}

/** Creates an [ElementResolver] bound to the current platform's UI tree. */
@Composable
internal expect fun rememberElementResolver(): ElementResolver
