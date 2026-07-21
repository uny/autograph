package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.uikit.LocalUIView
import dev.ynagai.autograph.uikit.AutographComposeHosts
import dev.ynagai.autograph.AutographInternalApi

/**
 * Registers this composition's host `UIView` with [AutographComposeHosts], so the native pipeline
 * drops any tap whose hit path crosses it.
 *
 * [LocalUIView] is the view Compose Multiplatform hosts the composition in, and the native walk
 * reaches Compose's bridged accessibility elements by descending through its `subviews` — so it is on
 * the hit path for every tap on Compose content, which is exactly what [AutographComposeHosts]
 * needs. (It is not itself the accessibility root: Compose attaches that to a sibling subview several
 * levels down. Registering an ancestor is the point — the leaf of a hit path is typically a bridged
 * element, and the host appears among its ancestors.)
 *
 * Unregistering on dispose keeps the boundary from outliving the screen, though the registry holds
 * views weakly and would drop it either way once deallocated.
 */
@OptIn(AutographInternalApi::class, InternalComposeApi::class)
@Composable
internal actual fun RegisterComposeHostForNativeCapture() {
    // The read has to be optional, and Compose gives exactly one way to make it so.
    //
    // [LocalUIView] has no default and throws when unprovided, and not every scene that can host a
    // composition provides it: `compose.uiTest`'s iOS scene does not, which is how this surfaced —
    // five of this module's own UI tests died on it. That is a scenario users share, not just a quirk
    // of our harness, since a user's Compose UI test wrapping their UI in `AutographProvider` hits the
    // same scene. Reading unguarded would widen a crash from "apps using autocapture" (where
    // `rememberElementResolver` already reads it) to "every app using this provider".
    //
    // `try`/`catch` around `LocalUIView.current` does not compile — "Try catch is not supported around
    // composable function invocations" — so the read goes through `Composer.consume`, an ordinary
    // method call that a `catch` may legally wrap. That costs an `@InternalComposeApi` opt-in. The
    // tradeoff was taken deliberately: `currentComposer`/`consume` are the pair every compiler-
    // generated call site already uses, so they are stable in practice despite the annotation, and if
    // they do change the breakage is a compile error on a Compose upgrade — visible, not a silent
    // runtime regression in a privacy boundary.
    //
    // Skipping when absent is correct rather than a workaround: no host `UIView` means no UIKit window,
    // so no native capture pipeline shares an accessibility tree with this composition and there is no
    // boundary to draw. The registry records a fact about a view; without one there is nothing to say.
    val composer = currentComposer
    val view = try {
        composer.consume(LocalUIView)
    } catch (_: IllegalStateException) {
        null
    }
    if (view != null) {
        DisposableEffect(view) {
            AutographComposeHosts.register(view)
            onDispose { AutographComposeHosts.unregister(view) }
        }
    }
}
