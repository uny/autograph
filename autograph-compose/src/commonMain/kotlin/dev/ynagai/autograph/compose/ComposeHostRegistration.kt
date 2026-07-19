package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

/**
 * Marks this composition's host as owned by the Compose capture pipeline, for as long as it exists.
 *
 * Only iOS does anything here. There, a native (UIKit/SwiftUI) capture pipeline can be running in the
 * same app, and both pipelines hit-test the *same* accessibility tree — so without a boundary a tap on
 * Compose content is visible to both and gets reported twice.
 *
 * **This must be called independently of whether autocapture is enabled**, which is why it is its own
 * effect rather than part of the autocapture branch in [AutographProvider]. The invariant the native
 * side relies on is *content under a Compose host belongs to the Compose pipeline exclusively* — not
 * *content the Compose pipeline reported*. A hybrid app can legitimately run Compose with autocapture
 * off and the native pipeline on; if registration were conditional on that flag, the native walk would
 * descend into Compose's bridged accessibility tree and capture elements the developer had excluded
 * with [autographIgnore]. The exclusion lives in Compose-side state the native pipeline cannot see, so
 * nothing downstream would catch it.
 *
 * Android needs none of this: its native pipeline, if any, has no equivalent shared tree to collide
 * over — `SemanticsOwner` hit-testing is Compose's own.
 */
@Composable
internal expect fun RegisterComposeHostForNativeCapture()
