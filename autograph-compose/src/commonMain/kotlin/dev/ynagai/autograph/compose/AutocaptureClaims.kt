package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs

/**
 * Which modifier registered a claim.
 *
 * [INSTRUMENTED_CLICK] is registered by [trackClick] alone. [trackImpression] deliberately registers
 * none: it reports a visibility event, never a click, so there is no click for autocapture to
 * duplicate and nothing to suppress. It used to register one, and geometry could not tell that claim
 * apart from the clickable enclosing it — the resolver either dropped the host's real tap or
 * double-reported, depending on which way the ambiguity was resolved (#153, #158; both shapes
 * measured byte-identical from the accessibility tree). Removing the claim removes the ambiguity
 * rather than picking a side of it.
 */
internal enum class AutocaptureClaimKind { IGNORED, INSTRUMENTED_CLICK }

/**
 * On-screen bounds of [autographIgnore]/[trackClick] elements, tracked positionally
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
    val ignored = mutableStateMapOf<Any, AutocaptureClaimBounds>()

    /** Claims from [trackClick] — see [AutocaptureClaimKind] for why [trackImpression] registers none. */
    val instrumentedClick = mutableStateMapOf<Any, AutocaptureClaimBounds>()

    private fun mapFor(kind: AutocaptureClaimKind) = when (kind) {
        AutocaptureClaimKind.IGNORED -> ignored
        AutocaptureClaimKind.INSTRUMENTED_CLICK -> instrumentedClick
    }

    fun put(key: Any, kind: AutocaptureClaimKind, bounds: AutocaptureClaimBounds) {
        mapFor(kind)[key] = bounds
    }

    fun remove(key: Any, kind: AutocaptureClaimKind) {
        mapFor(kind).remove(key)
    }
}

/**
 * One claim's geometry: where the element was [drawn] in window space, and the per-axis
 * [drawScale] it was drawn through.
 *
 * The pair is only read by `ElementResolver.ios.kt`, which has to reconcile a claim with the
 * accessibility frame Compose Multiplatform publishes for the same element — and for an element below
 * the minimum touch target that frame is the *measured* rect expanded to the minimum and then drawn
 * through the transform. So the resolver needs the transform, and [drawn] alone cannot recover it
 * after the fact (measured on device — a `scale(0.5f)` `Text` reported `drawn = 246x36px` against a
 * measured `492x72px`, and published its frame at `246x72px`, the minimum halved on the axis that
 * needed expanding). Android reads a semantics marker off the ancestry and consults neither
 * ([#159](https://github.com/uny/autograph/issues/159)).
 *
 * [drawn] stays `boundsInWindow()`, which an ancestor's clip shrinks. A claim whose element is partly
 * clipped therefore still carries a rect the published frame will not match — but that is confined to
 * [drawn]: [drawScale] is measured off the layout's corners rather than off [drawn], so a clip is
 * never mistaken for a scale and a clipped element's derived rect is exactly the one it had before
 * any scale was recovered (see [drawScale]).
 */
internal data class AutocaptureClaimBounds(val drawn: Rect, val drawScale: Size)

/** The ambient [AutocaptureClaims], or null outside [AutographProvider] / when autocapture is disabled. */
internal val LocalAutocaptureClaims = staticCompositionLocalOf<AutocaptureClaims?> { null }

/**
 * Registers this element's on-screen bounds into the ambient [AutocaptureClaims] as [kind], keyed by
 * a per-call-site-instance identity so the entry is removed on disposal without disturbing other
 * elements' entries. No-op when there's no ambient [AutocaptureClaims] (autocapture disabled, or
 * outside [AutographProvider]) — cheap to call unconditionally from [autographIgnore]/[trackClick].
 */
@Composable
internal fun Modifier.registerAutocaptureClaim(kind: AutocaptureClaimKind): Modifier {
    val claims = LocalAutocaptureClaims.current ?: return this
    val key = remember { Any() }
    DisposableEffect(claims, key, kind) { onDispose { claims.remove(key, kind) } }
    return onGloballyPositioned {
        claims.put(key, kind, AutocaptureClaimBounds(it.boundsInWindow(), it.drawScale()))
    }
}

/**
 * The per-axis scale this layout is drawn through: its window-space extent over the size it was
 * measured at.
 *
 * Taken from [LayoutCoordinates.localToWindow] on the layout's own corners, NOT from the ratio of
 * `boundsInWindow()` to [LayoutCoordinates.size]. `boundsInWindow()` is clipped — to the window and
 * to every clipping ancestor — so that ratio reports a clip as a scale, and the two are
 * indistinguishable once stored. Measured (`runComposeUiTest`, JVM): a 20dp-tall element centred in a
 * 10dp `clipToBounds` host reports `boundsInWindow()` 10dp tall against a measured 20dp — the same
 * `0.5` an actual `scale(0.5f)` reports — while `localToWindow` on its corners still spans the full
 * 20dp, giving `1.0`. That distinction is what keeps a clipped element's derived touch target the
 * plain minimum it was before #159, instead of a minimum shrunk by a scale that was never applied.
 *
 * An axis measured to zero has no ratio and reports `1`, leaving the minimum alone.
 *
 * Only a scale is recovered, and only an axis-aligned one: under a rotation the two corners span the
 * rotated diagonal rather than the element, so the ratio is not the scale — the same axis-aligned
 * assumption `AutocaptureNode.kt` documents for the Android hit test. That costs the resolver's
 * expansion branch alone, leaving a rotated element below the minimum touch target double-reporting
 * exactly as it did before any scale was recovered.
 */
private fun LayoutCoordinates.drawScale(): Size {
    val topLeft = localToWindow(Offset.Zero)
    val bottomRight = localToWindow(Offset(size.width.toFloat(), size.height.toFloat()))
    return Size(
        if (size.width > 0) abs(bottomRight.x - topLeft.x) / size.width else 1f,
        if (size.height > 0) abs(bottomRight.y - topLeft.y) / size.height else 1f,
    )
}
