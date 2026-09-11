package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.autograph.context.ScopeHandle

/**
 * No surface claims a composition's host here — there is no native screen capture that tags view
 * trees on this platform — so a tap resolves ambiently, as it did before origins existed. See the
 * expect declarations.
 */
@Composable
internal actual fun rememberHostSurfaceLookup(): () -> ScopeHandle? = remember { { null } }

@Composable
internal actual fun ClaimCompositionHost(origin: ProviderOrigin): Unit = Unit
