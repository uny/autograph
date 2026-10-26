package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.uikit.LocalUIView

// The view the resolver walks from (ElementResolver.ios.kt). A Compose Dialog's content gets a view
// of its own; sample-ios's testTapsInsideADialogAreCapturedOnlyUnderTheModifier pins that, since the
// modifier would otherwise be a silent no-op inside every dialog.
@Composable
internal actual fun currentAutocaptureWindow(): Any? = LocalUIView.current

// Measured on the simulator under XCUITest (#210): a tap inside a Dialog resolves through the
// observer this attaches, and not without it.
internal actual val autocaptureRootSupported: Boolean = true
