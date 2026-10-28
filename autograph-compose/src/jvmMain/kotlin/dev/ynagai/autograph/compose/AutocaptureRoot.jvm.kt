package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

@Composable
internal actual fun currentAutocaptureWindow(): Any? = null

// Desktop autocapture resolves nothing, so there is nothing to attach; see Modifier.autographAutocaptureRoot.
internal actual val autocaptureRootSupported: Boolean = false
