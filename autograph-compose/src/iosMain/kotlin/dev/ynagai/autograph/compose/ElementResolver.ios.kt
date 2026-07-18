package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.uikit.LocalUIView
import dev.ynagai.autograph.uikit.AutographInternalApi
import dev.ynagai.autograph.uikit.AxPoint
import dev.ynagai.autograph.uikit.AxRect
import dev.ynagai.autograph.uikit.accessibilityBoundsInWindowPx
import dev.ynagai.autograph.uikit.accessibilityIdentifierOrNull
import dev.ynagai.autograph.uikit.deepestAccessibilityHitPath
import dev.ynagai.autograph.uikit.nearestAccessibilityClickable
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import kotlin.math.abs

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
    return remember(view, claims) {
        ElementResolver { root, position -> resolveIosElement(view, claims, root.localToWindow(position)) }
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
internal fun resolveIosElement(view: UIView, claims: AutocaptureClaims?, position: Offset): String? {
    if (claims != null && claims.ignored.values.any { it.contains(position) }) return null
    val scale = UIScreen.mainScreen.scale.toFloat()
    val path = deepestAccessibilityHitPath(view, view, AxPoint(position.x, position.y), scale) ?: return null
    val nearestClickable = path.nearestAccessibilityClickable() ?: return null
    // Unlike `ignored` (deliberately ancestor-wide, matching Android's resolveAutocaptureTarget,
    // which suppresses on ANY ancestor's ignored flag), `instrumented` on Android suppresses only
    // when the resolved nearestClickable ITSELF is instrumented — an instrumented ANCESTOR (e.g. a
    // trackImpression container wrapping an unrelated Button) must not suppress it. iOS has no
    // ancestor-chain to consult, so approximate "is nearestClickable itself the instrumented
    // element" by comparing an instrumented claim's rect against nearestClickable's own bounds
    // (self-registration via trackClick/trackImpression puts a claim keyed at the exact element's
    // own boundsInWindow()) rather than the raw tap position, which would also match any larger
    // ancestor container overlapping the tap.
    if (claims != null) {
        val nearestClickableBounds = nearestClickable.accessibilityBoundsInWindowPx(view, scale)
        if (nearestClickableBounds != null && claims.instrumented.values.any { it.approximatelyEquals(nearestClickableBounds) }) return null
    }
    // No `label` argument: the accessibility label is never read — see accessibilityIdentifierOrNull's
    // kdoc in autograph-uikit for why falling back to it would defeat the "never read displayed text"
    // guarantee that Android's resolveAutocaptureTarget honors by only ever reading ContentDescription.
    return identifierFrom(testTag = nearestClickable.accessibilityIdentifierOrNull(), role = null, label = null)
}

/**
 * Bounds equality with tolerance, across the Compose/UIKit divide: [nearestClickable]'s own bounds
 * come from two different measurement paths for the same physical element — Compose's
 * `boundsInWindow()` (claim registration, a Compose [Rect]) vs the accessibility tree's
 * `accessibilityFrame` + `convertRect` + scale (an [AxRect]) — so exact equality is too strict, but a
 * real ancestor container's bounds differ by more than float noise. Both are window-space pixels, so
 * they're directly comparable.
 */
@OptIn(AutographInternalApi::class)
private fun Rect.approximatelyEquals(other: AxRect, tolerance: Float = 1f): Boolean =
    abs(left - other.left) < tolerance &&
        abs(top - other.top) < tolerance &&
        abs(right - other.right) < tolerance &&
        abs(bottom - other.bottom) < tolerance
