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
import dev.ynagai.autograph.uikit.anyAccessibilityDescendant
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
    // ancestor container overlapping the tap. See instrumentedElementIs for how that comparison
    // accounts for the minimum touch target, and why the two claim kinds read it differently.
    if (claims != null) {
        val nearestClickableBounds = nearestClickable.accessibilityBoundsInWindowPx(view, scale)
        if (nearestClickableBounds != null &&
            claims.instrumentedElementIs(nearestClickable, nearestClickableBounds, minimumTouchTargetPx, view, scale)
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
 * Whether the element resolved for this tap — [nearestClickable], whose accessibility frame is
 * [nearestClickableBounds] — is itself explicitly instrumented, and so must not be autocaptured on
 * top of the event its own modifier already fires.
 *
 * Both claim kinds answer this with the two comparisons [isTheElementBehind] makes — the click kind
 * by calling it, the impression kind by spelling them out so the ambiguous one can be qualified. The
 * expanded half is not a proof of identity: expansion is not injective, so a claim can expand onto a
 * rect belonging to a different element, and the two kinds differ in whether that is reachable:
 *
 * - [AutocaptureClaims.instrumentedClick] comes from [trackClick], which supplies the `clickable`
 *   itself. Its element is therefore clickable by construction, so a claim whose expansion lands on
 *   the resolved clickable describes that clickable. Taken at face value.
 * - [AutocaptureClaims.instrumentedImpression] comes from [trackImpression], which supplies no
 *   `clickable` at all. Its element is usually *not* interactive, and Compose Multiplatform publishes
 *   such an element as its own accessibility node nested inside whatever clickable encloses it. A
 *   sub-minimum one centred in a clickable exactly at the minimum touch target then expands onto that
 *   clickable's frame exactly, and vetoing on it drops an event the clickable was entitled to
 *   (#153 — measured: `(16, 636, 370x24)` expanding onto a host at `(16, 624, 370x48)`, to the point).
 *   So an expanded match is only honoured once no strict descendant of [nearestClickable] is found
 *   publishing the claim *unexpanded*, which is how such an element appears.
 *
 * The descendant search is deliberately confined to the impression kind. Compose Multiplatform
 * publishes child `Text`s as separate accessibility descendants **even inside a merged clickable**
 * (measured: an uninstrumented `Text` inside a `clickable` Box is published in its own right), so
 * applying it to click claims would stop vetoing a sub-minimum [trackClick] *container* whose child
 * exactly fills it — reopening #151 for that shape.
 *
 * Two residuals, both unmeasured and both confined to the impression kind:
 *
 * - [trackImpression] on a small element that the caller separately made clickable *and* that has a
 *   descendant exactly filling it. The descendant search suppresses the veto and the element
 *   double-reports. The plain sub-minimum `trackImpression` + `clickable` shape, with no such
 *   descendant, is measured and covered.
 * - The unexpanded branch below is left at face value, and it is **not** proof of identity either: a
 *   non-interactive [trackImpression] element coincident with the clickable enclosing it (a `Box`
 *   wrapping a single `trackImpression` `Text` with no padding, say) publishes the same frame as that
 *   clickable, so the claim equals [nearestClickableBounds] with no expansion involved and the veto
 *   drops the host's tap — #153's failure by a route the descendant search never sees. This predates
 *   #151, which only added the expanded half; qualifying it the same way would trade the drop for a
 *   duplicate on the first residual above, and which trade is right is a question for a device, not
 *   for this comment. Tracked separately (#158); the shapes measured for #153 are unaffected.
 */
@OptIn(AutographInternalApi::class)
private fun AutocaptureClaims.instrumentedElementIs(
    nearestClickable: Any,
    nearestClickableBounds: AxRect,
    minimumTouchTargetPx: Size,
    view: UIView,
    scale: Float,
): Boolean {
    if (instrumentedClick.values.any { it.isTheElementBehind(nearestClickableBounds, minimumTouchTargetPx) }) {
        return true
    }
    return instrumentedImpression.values.any { claim ->
        when {
            // [isTheElementBehind]'s first half, deliberately unqualified: expansion is not involved,
            // so #153's non-injectivity cannot be what produced this match. It is still not proof of
            // identity — a coincident descendant publishes the same rect and is vetoed wrongly. See
            // the kdoc's second residual (#158) for why that is left standing rather than fixed here.
            claim.approximatelyEquals(nearestClickableBounds) -> true
            !claim.expandedToAtLeast(minimumTouchTargetPx).approximatelyEquals(nearestClickableBounds) -> false
            // Only reached when the match came from the expansion, which is the ambiguous case.
            else -> !nearestClickable.anyAccessibilityDescendant(view, scale) { claim.approximatelyEquals(it) }
        }
    }
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
 * Known residual, narrower than the case fixed here, leaving a double report standing because the
 * expansion derived here isn't the one Compose published: a **scaled** element, for which Compose
 * qualifies the touch target on the MEASURED size while the claim carries the drawn rect — the
 * distinction `SemanticsHitPath.kt`'s `minTargetDistanceSquared` calls load-bearing. Measured
 * (iPhone 17 Pro): a `trackClick` `Text` under `scale(0.5f)` published `(62.7, 664, 93x24)` — the
 * expanded measured rect halved — while its claim was the drawn rect, whose own expansion is
 * `93x48`. No match, so both the explicit event and an `Element Clicked` fire. Tracked as #159, and
 * best decided together with #158 — qualifying the unexpanded branch to close that one trades a drop
 * for a duplicate on exactly this shape.
 *
 * A second residual was documented here and is **refuted by measurement**: an ancestor clipping the
 * expanded touch target does *not* break the match. `clipToBounds` is a draw-time clip and does not
 * clip the published accessibility frame — a `trackClick` `Text` inside a 24dp `clipToBounds` host
 * (host `(16, 624, 370x24)`) published the full expansion at `(16, 612, 193.7x48)`, overflowing its
 * clipping parent, and reported exactly once.
 *
 * The third — expansion is not injective, so an element below the minimum can expand onto a rect
 * belonging to something else — **does occur**, and [instrumentedElementIs] is what confines it. It
 * does not arise when the instrumented element is itself clickable: in the canonical shape (a 24dp
 * `trackClick` box centred in its own 48dp `clickable`, what Material builds by construction),
 * tapping the outer ring fired exactly one event, the inner's explicit one, because the inner's
 * expanded touch target covers the whole outer box, so Compose routes the tap to the inner and the
 * outer never receives it. That argument needs the inner to be clickable, and [trackImpression]
 * instruments without making it so — which is #153. Configurations beyond these — both elements
 * below the minimum, or non-concentric — were not measured.
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
