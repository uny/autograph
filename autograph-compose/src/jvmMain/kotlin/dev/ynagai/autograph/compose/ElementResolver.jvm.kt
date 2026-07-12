package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Autocapture isn't implemented on desktop yet — taps are observed but never resolve to a target. */
@Composable
internal actual fun rememberElementResolver(): ElementResolver = remember { ElementResolver { _, _ -> null } }
