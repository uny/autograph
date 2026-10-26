package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

// A Dialog or Popup composes into a view of its own (DialogLayout / PopupLayout), and that view is
// also what the resolver hit-tests (ElementResolver.android.kt), so it is the right identity for
// "which observer sees this tap".
@Composable
internal actual fun currentAutocaptureWindow(): Any? = LocalView.current

internal actual val autocaptureRootSupported: Boolean = true
