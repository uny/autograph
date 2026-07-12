package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformAutocaptureTestHost(content: @Composable () -> Unit): Unit = content()
