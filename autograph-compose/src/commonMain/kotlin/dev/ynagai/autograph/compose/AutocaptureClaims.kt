package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Which kind of on-screen bounds [AutocaptureClaims] is tracking. */
internal enum class AutocaptureClaimKind { IGNORED, INSTRUMENTED }

/**
 * On-screen bounds of [autographIgnore]/[trackClick]/[trackImpression] elements, tracked positionally
 * rather than via the semantics tree.
 *
 * Android's [ElementResolver] doesn't need this — it hit-tests the semantics tree directly and reads
 * [AutographIgnoredKey]/[AutographInstrumentedKey] straight off the tapped node's ancestry. iOS has no
 * way to read a custom semantics key back off a tapped element (see ElementResolver.ios.kt: the UIKit
 * accessibility bridge only carries the fixed UIAccessibility properties — label, traits, identifier,
 * frame — not arbitrary Compose semantics keys), so its resolver consults this instead: is the tap
 * position inside any ignored/instrumented element's last-known bounds.
 */
internal class AutocaptureClaims {
    val ignored = mutableStateMapOf<Any, Rect>()
    val instrumented = mutableStateMapOf<Any, Rect>()

    private fun mapFor(kind: AutocaptureClaimKind) = when (kind) {
        AutocaptureClaimKind.IGNORED -> ignored
        AutocaptureClaimKind.INSTRUMENTED -> instrumented
    }

    fun put(key: Any, kind: AutocaptureClaimKind, bounds: Rect) {
        mapFor(kind)[key] = bounds
    }

    fun remove(key: Any, kind: AutocaptureClaimKind) {
        mapFor(kind).remove(key)
    }
}

/** The ambient [AutocaptureClaims], or null outside [AutographProvider] / when autocapture is disabled. */
internal val LocalAutocaptureClaims = staticCompositionLocalOf<AutocaptureClaims?> { null }

/**
 * Registers this element's on-screen bounds into the ambient [AutocaptureClaims] as [kind], keyed by
 * a per-call-site-instance identity so the entry is removed on disposal without disturbing other
 * elements' entries. No-op when there's no ambient [AutocaptureClaims] (autocapture disabled, or
 * outside [AutographProvider]) — cheap to call unconditionally from [autographIgnore]/[trackClick]/
 * [trackImpression].
 */
@Composable
internal fun Modifier.registerAutocaptureClaim(kind: AutocaptureClaimKind): Modifier {
    val claims = LocalAutocaptureClaims.current ?: return this
    val key = remember { Any() }
    DisposableEffect(claims, key, kind) { onDispose { claims.remove(key, kind) } }
    return onGloballyPositioned { claims.put(key, kind, it.boundsInRoot()) }
}
