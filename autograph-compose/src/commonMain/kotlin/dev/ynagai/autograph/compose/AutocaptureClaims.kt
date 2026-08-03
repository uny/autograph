package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Which modifier registered a claim.
 *
 * The two INSTRUMENTED kinds behave identically on Android, which reads
 * [AutographInstrumentedKey] off the semantics ancestry and never consults these bounds at all. They
 * differ only for [ElementResolver.ios.kt], and only in one respect: whether the claim's element is
 * *known to be clickable*. [INSTRUMENTED_CLICK] is registered by [trackClick], which supplies the
 * `clickable` itself, so its element always is. [INSTRUMENTED_IMPRESSION] is registered by
 * [trackImpression], which supplies none — its element is clickable only if the caller separately
 * made it so, and usually isn't. That distinction is what lets the resolver tell a claim that
 * describes the tapped clickable from one that describes a non-interactive descendant of it; see
 * `instrumentedElementIs` for why geometry alone cannot (#153), and what it still does not settle.
 */
internal enum class AutocaptureClaimKind { IGNORED, INSTRUMENTED_CLICK, INSTRUMENTED_IMPRESSION }

/**
 * On-screen bounds of [autographIgnore]/[trackClick]/[trackImpression] elements, tracked positionally
 * rather than via the semantics tree.
 *
 * Android's [ElementResolver] doesn't need this — it hit-tests the semantics tree directly and reads
 * [AutographIgnoredKey]/[AutographInstrumentedKey] straight off the tapped node's ancestry. iOS has no
 * way to read a custom semantics key back off a tapped element (see ElementResolver.ios.kt: the UIKit
 * accessibility bridge only carries the fixed UIAccessibility properties — label, traits, identifier,
 * frame — not arbitrary Compose semantics keys), so its resolver consults this instead: is the tap
 * position inside any ignored/instrumented element's last-known bounds. Bounds are captured in
 * WINDOW space ([boundsInWindow], not `boundsInRoot`) to match [ElementResolver.ios.kt]'s resolver,
 * which converts the tap position via `root.localToWindow(position)` before comparing against these —
 * the two must share a coordinate space regardless of where [autocaptureTaps] sits relative to the
 * window (safe-area insets, nested embedding, etc.).
 */
internal class AutocaptureClaims {
    val ignored = mutableStateMapOf<Any, Rect>()

    /** Claims from [trackClick] — see [AutocaptureClaimKind] for why these are kept apart. */
    val instrumentedClick = mutableStateMapOf<Any, Rect>()

    /** Claims from [trackImpression] — see [AutocaptureClaimKind] for why these are kept apart. */
    val instrumentedImpression = mutableStateMapOf<Any, Rect>()

    private fun mapFor(kind: AutocaptureClaimKind) = when (kind) {
        AutocaptureClaimKind.IGNORED -> ignored
        AutocaptureClaimKind.INSTRUMENTED_CLICK -> instrumentedClick
        AutocaptureClaimKind.INSTRUMENTED_IMPRESSION -> instrumentedImpression
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
    return onGloballyPositioned { claims.put(key, kind, it.boundsInWindow()) }
}
