package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack

/**
 * No surface claims a composition's host here — there is no native screen capture that tags view
 * trees on this platform — so a tap resolves from the composition's own frame, unlinked: every frame
 * under no boundary applies, and the composition's declarations apply with the frame's own bit. See
 * the expect declaration and [ProviderOrigin].
 */
@Composable
internal actual fun rememberHostSurfaceLookup(): () -> ScopeHandle? = remember { { null } }

@Composable
internal actual fun KeepLinkedToHost(stack: ScopeStack, origin: ProviderOrigin): Unit = Unit
