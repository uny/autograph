package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

@Composable
internal actual fun currentAutocaptureWindow(): Any? = null

// Unmeasured here; see Modifier.autographAutocaptureRoot.
internal actual val autocaptureRootSupported: Boolean = false
