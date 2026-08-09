package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
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
 *    consults [AutocaptureClaims] instead, which [autographIgnore]/[trackClick] populate
 *    positionally ([trackImpression] deliberately registers nothing — see [AutocaptureClaimKind]).
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
    if (claims != null && claims.ignored.values.any { it.drawn.contains(position) }) return null
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
    // container wrapping an unrelated Button) must not suppress it. iOS has no ancestor-chain to
    // consult, so approximate "is nearestClickable itself the instrumented element" by comparing an
    // instrumented claim's rect against nearestClickable's own bounds (self-registration via
    // trackClick puts a claim keyed at the exact element's own boundsInWindow()) rather than the raw
    // tap position, which would also match any larger ancestor container overlapping the tap.
    //
    // Only [trackClick] registers such a claim, and that is what makes the approximation sound: its
    // element is a `clickable` by construction, so a claim matching the resolved clickable describes
    // that clickable. [trackImpression] registers none — see [AutocaptureClaimKind] for why an
    // impression claim was indistinguishable from the clickable enclosing it, and #158.
    if (claims != null) {
        val nearestClickableBounds = nearestClickable.accessibilityBoundsInWindowPx(view, scale)
        if (nearestClickableBounds != null &&
            claims.instrumentedClick.values.any {
                it.isTheElementBehind(nearestClickableBounds, minimumTouchTargetPx)
            }
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
 * Deriving that second rect and requiring equality is what shipped, and #179 is why it is gone: the
 * expansion is clamped against neighbouring layout, so the published frame is not a function of the
 * claim and no derived rect equals it in general. The match is now made per axis by
 * [admitsTheExpansionOf] — exact on an axis that already meets the minimum, and anywhere between the
 * claim and the minimum on one that falls short. That KDoc has the measurement and the residual.
 *
 * What has not changed is what the check is for: keeping a merely-similar ancestor container from
 * counting as this element (see the call site). The exact-match axis is what still does that work.
 *
 * The expansion is **not** the plain minimum, though, and that was #159: Compose qualifies the touch
 * target on the element's MEASURED size — the distinction `SemanticsHitPath.kt`'s
 * `minTargetDistanceSquared` calls load-bearing — and then draws the result through whatever
 * transform the element sits under, while [AutocaptureClaimBounds.drawn] is already transformed. So
 * the minimum is scaled by the claim's own [AutocaptureClaimBounds.drawScale] before the expansion,
 * which is the identity whenever there is no transform. Measured (iPhone 17 Pro): a `trackClick`
 * `Text` under `scale(0.5f)` drawn at `246x36px` from a measured `492x72px` published
 * `(171, 1236, 417, 1308)` — 246x72px, the 144px minimum halved on the axis that needed expanding,
 * to the pixel; before the scale, the derived `246x144` matched nothing and the element reported
 * twice.
 *
 * A residual was documented here and is **refuted by measurement**: an ancestor clipping the
 * expanded touch target does *not* break the match. `clipToBounds` is a draw-time clip and does not
 * clip the published accessibility frame — a `trackClick` `Text` inside a 24dp `clipToBounds` host
 * (host `(16, 624, 370x24)`) published the full expansion at `(16, 612, 193.7x48)`, overflowing its
 * clipping parent, and reported exactly once.
 *
 * The other — expansion is not injective, so an element below the minimum can expand onto a rect
 * belonging to something else — does **not** arise for the claims that reach here, because
 * [trackClick] makes its element a `clickable`. In the canonical shape (a 24dp `trackClick` box
 * centred in its own 48dp `clickable`, what Material builds by construction) tapping the outer ring
 * fired exactly one event, the inner's explicit one, because the inner's expanded touch target covers
 * the whole outer box, so Compose routes the tap to the inner and the outer never receives it. That
 * argument needs the instrumented element to be clickable; [trackImpression] instrumented without
 * making it so, which is why its claims were removed rather than qualified (#153, #158 — see
 * [AutocaptureClaimKind]). Configurations beyond these — both elements below the minimum, or
 * non-concentric — were not measured.
 *
 * [minimumTouchTargetPx] defaults to [Size.Zero] (no expansion) only so tests that predate this can
 * state the unexpanded case directly.
 */
@OptIn(AutographInternalApi::class)
private fun AutocaptureClaimBounds.isTheElementBehind(other: AxRect, minimumTouchTargetPx: Size): Boolean {
    val minimum = minimumTouchTargetPx.drawnLike(this)
    return admitsTheExpansionOf(drawn.left, drawn.right, other.left, other.right, minimum.width) &&
        admitsTheExpansionOf(drawn.top, drawn.bottom, other.top, other.bottom, minimum.height)
}

/**
 * One axis of [isTheElementBehind]: whether the published span `[publishedStart, publishedEnd]` is
 * the claim's span `[claimStart, claimEnd]` as Compose may have expanded it toward [minimum].
 *
 * The tolerance exists because the two spans come from independent measurement paths across the
 * Compose/UIKit divide — `boundsInWindow()` at claim registration vs `accessibilityFrame` +
 * `convertRect` + scale — and exact equality is too strict for that. A real ancestor container
 * differs by far more than float noise. Both are window-space pixels, so they compare directly.
 *
 * **An axis that already meets the minimum must match exactly.** There is no expansion left for
 * Compose to apply there, so any difference describes a different element. This is the axis that
 * keeps a merely-similar ancestor container out, and it is why the band below can be as loose as it
 * is: a container overlapping the tap almost always differs on the axis that needed no expanding.
 *
 * **An axis that falls short is admitted anywhere between the two.** The published span must contain
 * the claim's and must not exceed [minimum]. It is *not* required to be the claim's span expanded
 * symmetrically, and that is the whole of #179: Compose clamps the expansion against neighbouring
 * layout, so where it lands is not a function of the claim. Measured on device (iPhone 17 Pro,
 * `scale = 3`), a natural-height `trackClick` `Text` directly above another clickable registered
 * `(48, 540, 945, 612)` — 72px tall — and published `(48, 504, 945, 612)`: 108px, grown 36px upward
 * and not at all downward, its bottom edge exactly the neighbour's top. The symmetric expansion this
 * used to derive, `(48, 504, 945, 648)`, shares only the top edge, so it matched nothing and the
 * element was reported twice. Give the same `Text` room — the repository sample's
 * `Arrangement.spacedBy(12.dp)` — and Compose applies the minimum at *layout* time instead: the
 * claim itself is then 144px and takes the exact-match path above, never reaching this branch.
 *
 * The residual is the shape this cannot tell apart: a claim below the minimum on **both** axes,
 * inside an unrelated clickable that is itself within the minimum of it on both axes and contains
 * it. Then the container's frame sits in the band and would veto the container's own tap. It needs
 * a claim narrower AND shorter than the minimum, which the axis rule above cannot filter, and it is
 * not reachable through the shapes this project has measured: [trackClick] makes its element a
 * `clickable`, so its expanded touch target covers the container and Compose routes the tap to the
 * claim's own element rather than the container (see [isTheElementBehind]'s note on #153/#158).
 * Narrower than what shipped before this, but no longer nothing — worth stating rather than implying
 * the match is exact.
 */
private fun admitsTheExpansionOf(
    claimStart: Float,
    claimEnd: Float,
    publishedStart: Float,
    publishedEnd: Float,
    minimum: Float,
    tolerance: Float = 1f,
): Boolean {
    if (claimEnd - claimStart >= minimum - tolerance) {
        return abs(publishedStart - claimStart) < tolerance && abs(publishedEnd - claimEnd) < tolerance
    }
    return publishedStart <= claimStart + tolerance &&
        publishedEnd >= claimEnd - tolerance &&
        publishedEnd - publishedStart <= minimum + tolerance
}

/**
 * This size mapped through the transform [claim]'s element was drawn under. The identity whenever the
 * element carries no transform, which is every claim but #159's — including a clipped one, since
 * [AutocaptureClaimBounds.drawScale] is measured off the element's corners rather than off its
 * clipped [AutocaptureClaimBounds.drawn] rect (that KDoc has the measurement, and why deriving the
 * scale from `drawn` instead would read a clip as a scale and shrink a clipped element's derived
 * touch target below the one it matched on before #159).
 *
 * Only a scale is recovered, and only an axis-aligned one — see [AutocaptureClaimBounds.drawScale]
 * for the rotation case, which leaves this branch's derived rect wrong exactly as it was before any
 * scale was recovered (a double report, for an element below the minimum).
 */
private fun Size.drawnLike(claim: AutocaptureClaimBounds): Size =
    Size(width * claim.drawScale.width, height * claim.drawScale.height)

