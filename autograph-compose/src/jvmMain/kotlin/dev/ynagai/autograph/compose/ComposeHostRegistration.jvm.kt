package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable

/**
 * No-op: there is no second capture pipeline here sharing a hit-testing tree with Compose. See the
 * expect declaration for why iOS needs a boundary and this target does not.
 */
@Composable
internal actual fun RegisterComposeHostForNativeCapture(): Unit = Unit
