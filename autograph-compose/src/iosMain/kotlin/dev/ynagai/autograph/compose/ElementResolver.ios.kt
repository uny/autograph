package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.uikit.LocalUIView
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.uikit.AxPoint
import dev.ynagai.autograph.uikit.AxRect
import dev.ynagai.autograph.uikit.accessibilityBoundsInWindowPx
import dev.ynagai.autograph.uikit.accessibilityIdentifierOrNull
import dev.ynagai.autograph.uikit.deepestAccessibilityHitPath
import dev.ynagai.autograph.uikit.isAccessibilityDisabled
import dev.ynagai.autograph.uikit.nearestAccessibilityClickable
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves a Compose tap to an element identifier by hit-testing the UIKit accessibility tree that
 * Compose Multiplatform bridges its semantics into, rather than Compose's own `SemanticsOwner`
 * (unlike Android — iOS has no supported route to a `SemanticsOwner` from application code). The walk
 * itself lives in `autograph-uikit`, which documents that mechanism, its on-device evidence, and its
 * coordinate space; this file is only the Compose adapter over it.
 *
 * The adapter's two jobs:
 *
 * 1. **Coordinate conversion.** The walk works in window-space pixels, which is exactly what Compose
 *    produces: [rememberElementResolver] converts the tap via `root.localToWindow(position)` (like
 *    Android's `resolveAutocaptureTarget` does against `boundsInWindow`), and `Offset`/
 *    `LayoutCoordinates` are already pixels. So the conversion here is a straight re-wrap into
 *    [AxPoint] — no scaling, no origin shift.
 * 2. **Claims.** Custom semantics keys don't survive the UIKit bridge, so unlike Android this resolver
 *    can't read [AutographIgnoredKey]/[AutographInstrumentedKey] off the hit node's ancestry. It
 *    consults [AutocaptureClaims] instead, which [autographIgnore]/[trackClick]/[trackImpression]
 *    populate positionally.
 *
 * Because the claims check is "is the tap position inside some registered rect" rather than "is a
 * registered rect an ancestor of the hit node" (what Android's [resolveAutocaptureTarget] does), it's
 * blind to ancestry: a tap can be suppressed by an [autographIgnore]'d/instrumented element that
 * isn't on the hit path, merely overlapping it at that position (e.g. mid-scroll/transition, or a
 * stale entry for a composable that's still composed but visually covered). Android can't have this
 * failure mode since it only ever looks at the hit node's own ancestor chain.
 */
@Composable
internal actual fun rememberElementResolver(): ElementResolver {
    val view = LocalUIView.current
    val claims = LocalAutocaptureClaims.current
    // Compose expands a small element's touch target to this before publishing its accessibility
    // frame, while the claim it registers stays the unexpanded layout bounds — so the match in
    // [resolveIosElement] needs the size to reconcile the two. Read here rather than there because
    // it is only reachable from a composition (#151).
    val minimumTouchTargetPx = with(LocalDensity.current) {
        val size = LocalViewConfiguration.current.minimumTouchTargetSize
        Size(size.width.toPx(), size.height.toPx())
    }
    return remember(view, claims, minimumTouchTargetPx) {
        ElementResolver { root, position ->
            // No scope: the UIKit bridge carries none of the custom semantics [autocaptureScope]
            // writes, so there is nothing to read back off the hit path here — and none of the
            // properties it does carry can stand in for one soundly, which is why this is left empty
            // rather than approximated. That modifier's kdoc is the canonical account (#68). An empty
            // scope leaves the tap attributed exactly as it was before it existed — the ambient
            // `ScopeStack` still contributes, and simultaneously-mounted siblings there still drop
            // rather than guess.
            resolveIosElement(view, claims, root.localToWindow(position), minimumTouchTargetPx)
                ?.let { AutocaptureTarget(it) }
        }
    }
}

/**
 * The non-Composable core of [rememberElementResolver]'s resolve callback, pulled out so it's
 * directly testable against a hand-built [UIView] tree and [AutocaptureClaims] fixture —
 * `compose.uiTest`'s iOS scene can't otherwise exercise this (see [PlatformAutocaptureTestHost.ios.kt]).
 *
 * [position] is window-relative and in pixels (see [rememberElementResolver]).
 */
@OptIn(AutographInternalApi::class)
internal fun resolveIosElement(
    view: UIView,
    claims: AutocaptureClaims?,
    position: Offset,
    minimumTouchTargetPx: Size = Size.Zero,
): String? {
    if (claims != null && claims.ignored.values.any { it.contains(position) }) return null
    val scale = UIScreen.mainScreen.scale.toFloat()
    val path = deepestAccessibilityHitPath(view, view, AxPoint(position.x, position.y), scale) ?: return null
    val nearestClickable = path.nearestAccessibilityClickable() ?: return null
    // A disabled element takes the hit and is vetoed here, exactly as Android's
    // resolveAutocaptureTarget vetoes SemanticsProperties.Disabled (#128) — measured on-device,
    // tapping one fires no handler at all, not even its enabled clickable parent's. Confined to the
    // resolved element, never asked of its ancestry. See isAccessibilityDisabled for both halves.
    if (nearestClickable.isAccessibilityDisabled()) return null
    // Unlike `ignored` (deliberately ancestor-wide, matching Android's resolveAutocaptureTarget,
    // which suppresses on ANY ancestor's ignored flag), `instrumented` on Android suppresses only
    // when the resolved nearestClickable ITSELF is instrumented — an instrumented ANCESTOR (e.g. a
    // trackImpression container wrapping an unrelated Button) must not suppress it. iOS has no
    // ancestor-chain to consult, so approximate "is nearestClickable itself the instrumented
    // element" by comparing an instrumented claim's rect against nearestClickable's own bounds
    // (self-registration via trackClick/trackImpression puts a claim keyed at the exact element's
    // own boundsInWindow()) rather than the raw tap position, which would also match any larger
    // ancestor container overlapping the tap. See isTheElementBehind for why that comparison also has
    // to account for the minimum touch target.
    if (claims != null) {
        val nearestClickableBounds = nearestClickable.accessibilityBoundsInWindowPx(view, scale)
        if (nearestClickableBounds != null &&
            claims.instrumented.values.any { it.isTheElementBehind(nearestClickableBounds, minimumTouchTargetPx) }
        ) {
            return null
        }
    }
    // No `label` argument: the accessibility label is never read — see accessibilityIdentifierOrNull's
    // kdoc in autograph-uikit for why falling back to it would defeat the "never read displayed text"
    // guarantee that Android's resolveAutocaptureTarget honors by only ever reading ContentDescription.
    return identifierFrom(testTag = nearestClickable.accessibilityIdentifierOrNull(), role = null, label = null)
}

/**
 * Whether [other] — the accessibility frame of the resolved element — is this instrumented claim's
 * own element, rather than some other element that merely overlaps the tap.
 *
 * The two rects describe the same physical element by different routes: Compose's `boundsInWindow()`
 * at claim registration vs the accessibility tree's `accessibilityFrame` + `convertRect` + scale. For
 * an element at or above the minimum touch target they agree to float noise, so plain
 * [approximatelyEquals] settles it.
 *
 * Below it they do not, and that was #151: Compose expands a small element's touch target — and with
 * it the accessibility frame it publishes — to [minimumTouchTargetPx], **centred on the element**,
 * while the registered claim stays the unexpanded layout bounds. Measured on device (iPhone 17 Pro,
 * `scale = 3`), a natural-height `Text` carrying [trackClick] registered
 * `(48, 1752, 1124, 1824)` — 72px tall — and published `(48, 1716, 1124, 1860)`: 144px, exactly
 * 48dp, symmetric about the same centre, with the already-wide-enough horizontal axis untouched. So
 * the claim is also compared against itself expanded that way.
 *
 * This stays rect *equality* against a second precisely-derived rect rather than becoming a
 * containment or tolerance widening, both of which would start matching a merely-similar ancestor
 * container — the failure the equality check exists to prevent (see the call site).
 *
 * Known residuals, all unmeasured and narrower than the case fixed here. Two leave a double report
 * standing, because the expansion derived here isn't the one Compose published: an ancestor clipping
 * the expanded touch target publishes neither the layout bounds nor the full expansion; and for a
 * scaled or clipped element Compose qualifies on the MEASURED size while the claim carries the drawn
 * rect — the distinction `SemanticsHitPath.kt`'s `minTargetDistanceSquared` calls load-bearing. One
 * goes the other way: expansion is not injective, so two concentric elements both below the minimum
 * expand to the SAME rect — a 24dp icon centred in its own 48dp clickable is the shape Material
 * builds by construction — and instrumenting the inner one suppresses a tap on the outer, which was
 * never the instrumented element. No rect-only discriminator separates that from a real match, and
 * containment or a wider tolerance would widen it rather than close it.
 *
 * [minimumTouchTargetPx] defaults to [Size.Zero] (no expansion) only so tests that predate this can
 * state the unexpanded case directly.
 */
@OptIn(AutographInternalApi::class)
private fun Rect.isTheElementBehind(other: AxRect, minimumTouchTargetPx: Size): Boolean =
    approximatelyEquals(other) || expandedToAtLeast(minimumTouchTargetPx).approximatelyEquals(other)

/** This rect grown about its own centre so that neither side is shorter than [size]. */
private fun Rect.expandedToAtLeast(size: Size): Rect {
    val halfWidth = max(width, size.width) / 2f
    val halfHeight = max(height, size.height) / 2f
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    return Rect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

/**
 * Bounds equality with tolerance, across the Compose/UIKit divide — exact equality is too strict for
 * two independent measurement paths, but a real ancestor container's bounds differ by more than float
 * noise. Both are window-space pixels, so they're directly comparable.
 */
@OptIn(AutographInternalApi::class)
private fun Rect.approximatelyEquals(other: AxRect, tolerance: Float = 1f): Boolean =
    abs(left - other.left) < tolerance &&
        abs(top - other.top) < tolerance &&
        abs(right - other.right) < tolerance &&
        abs(bottom - other.bottom) < tolerance
