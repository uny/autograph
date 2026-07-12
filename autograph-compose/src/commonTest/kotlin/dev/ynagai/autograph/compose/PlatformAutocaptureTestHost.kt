package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

/**
 * Wraps [content] with whatever ambient state a platform's [rememberElementResolver] needs to run
 * without crashing under `compose.uiTest` — e.g. iOS's `LocalUIView`, which the test scene doesn't
 * back with a real `UIView` the way a `ComposeUIViewController`-hosted app does. A no-op on
 * platforms whose resolver doesn't read platform-specific ambient state (Android, JVM).
 */
@Composable
internal expect fun PlatformAutocaptureTestHost(content: @Composable () -> Unit)
