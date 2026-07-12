package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.uikit.LocalUIView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityFrame
import platform.UIKit.accessibilityTraits
import platform.darwin.NSObject

/**
 * Hit-tests the UIKit accessibility tree Compose Multiplatform builds from its semantics tree —
 * the standard, public `UIView.accessibilityElements`/`UIAccessibilityElement.accessibilityElements`
 * container API, walked recursively — rather than Compose's own `SemanticsOwner` (unlike Android:
 * iOS has no supported route to a `SemanticsOwner` from application code; every path to it —
 * `LocalComposeScene`, the `ComposeRootRegistry` `compose-ui-test` itself relies on, the Kotlin class
 * backing each accessibility element — is `internal` or `private` to the Compose UI library).
 *
 * Confirmed on-device (`ComposeUIViewController` hosted in a real `.app`, installed and launched via
 * `xcrun simctl`, Compose Multiplatform 1.11.1) that this tree is populated on the very first layout
 * pass, VoiceOver on or off — an earlier attempt at this same approach concluded otherwise because it
 * only read `LocalUIView.current.accessibilityElements()` directly, which is empty: Compose attaches
 * the real accessibility root to a *sibling* subview several levels down
 * (`ComposeContainerView.subviews[2]` — `OverlayInputView` — in the traced case, though that index
 * isn't a contract worth hard-coding), not to the view `LocalUIView` itself returns. Walking
 * `subviews` alongside `accessibilityElements` at every `UIView` node (below) finds it regardless of
 * exactly which subview it lives under.
 *
 * What's NOT reachable this way: custom semantics keys. [AutographIgnoredKey]/[AutographInstrumentedKey]
 * have no UIAccessibility equivalent, so unlike Android this resolver can't read them off the hit
 * node's ancestry — [autocaptureTaps] instead consults [AutocaptureClaims], which
 * [autographIgnore]/[trackClick]/[trackImpression] populate positionally.
 *
 * Because that check is purely "is the tap position inside some registered rect" rather than "is a
 * registered rect an ancestor of the hit node" (which is what Android's [resolveAutocaptureTarget]
 * does), it's blind to ancestry: a tap can be suppressed by an [autographIgnore]'d/instrumented
 * element that isn't actually on the hit path, merely overlapping it at that position (e.g. during a
 * scroll/transition, or a stale entry for a composable that's still composed but visually covered).
 * Android can't have this failure mode since it only ever looks at the hit node's own ancestor chain.
 *
 * `accessibilityFrame` is documented by Apple as screen coordinates; converted back to the root
 * view's local space via `UIView.convertRect(_:fromCoordinateSpace:)` (confirmed on-device to
 * exactly match the element's declared local size/position, once scaled — see
 * [accessibilityLocalBounds]) to compare against [Offset]s from the pointer-input tree — those
 * arrive local to the tapped node, not to [view], so [rememberElementResolver] converts via
 * `root.localToWindow(position)` first (mirroring Android's `resolveAutocaptureTarget`, which
 * converts the same way via `root.localToWindow(position)` against `boundsInWindow`); skipping
 * that conversion would misattribute or miss every tap whenever the `Modifier.autocaptureTaps`
 * node isn't flush with [view]'s own origin.
 */
@OptIn(ExperimentalForeignApi::class)
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
 */
@OptIn(ExperimentalForeignApi::class)
internal fun resolveIosElement(view: UIView, claims: AutocaptureClaims?, position: Offset): String? {
    if (claims != null && claims.ignored.values.any { it.contains(position) }) return null
    val path = deepestAccessibilityHitPath(view, view, position) ?: return null
    val nearestClickable = path.asReversed().firstOrNull { it.isAccessibilityButton() } ?: return null
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
        val nearestClickableBounds = nearestClickable.accessibilityLocalBounds(view)
        if (nearestClickableBounds != null && claims.instrumented.values.any { it.approximatelyEquals(nearestClickableBounds) }) return null
    }
    // No `label` argument: UIKit exposes no way to tell an explicit `contentDescription`-derived
    // accessibilityLabel apart from one Compose Multiplatform's UIKit bridge synthesizes from
    // SemanticsProperties.Text (the on-screen text) when no contentDescription is set — falling back
    // to it here would silently defeat the "never read displayed text" guarantee documented above
    // and honored by Android's resolveAutocaptureTarget, which only ever reads ContentDescription.
    return identifierFrom(testTag = nearestClickable.accessibilityIdentifierOrNull(), role = null, label = null)
}

/**
 * Bounds equality with tolerance: [nearestClickable]'s own bounds come from two different
 * measurement paths for the same physical element — Compose's `boundsInWindow()` (claim
 * registration) vs UIKit's `accessibilityFrame` + `convertRect` + scale (this resolver) — so exact
 * equality is too strict, but a real ancestor container's bounds differ by more than float noise.
 */
private fun Rect.approximatelyEquals(other: Rect, tolerance: Float = 1f): Boolean =
    (left - other.left).let { it > -tolerance && it < tolerance } &&
        (top - other.top).let { it > -tolerance && it < tolerance } &&
        (right - other.right).let { it > -tolerance && it < tolerance } &&
        (bottom - other.bottom).let { it > -tolerance && it < tolerance }

/**
 * Depth-first search mirroring [findDeepestHit]/[AutocaptureNode]'s Android counterpart, but over
 * the UIKit accessibility tree: returns the path from [node] down to the deepest descendant whose
 * [accessibilityFrame] contains [position], or null if [node] itself doesn't contain it. Preferring
 * later (visually on top) children when bounds overlap, same as Android.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun deepestAccessibilityHitPath(node: Any, view: UIView, position: Offset): List<Any>? {
    val bounds = node.accessibilityLocalBounds(view) ?: return null
    if (!bounds.contains(position)) return null
    val children = node.accessibilityChildren()
    for (child in children.asReversed()) {
        deepestAccessibilityHitPath(child, view, position)?.let { return listOf(node) + it }
    }
    return listOf(node)
}

@OptIn(ExperimentalForeignApi::class)
internal fun Any.accessibilityLocalBounds(view: UIView): Rect? {
    val screenFrame = (this as? NSObject)?.accessibilityFrame() ?: return null
    val localFrame = view.convertRect(screenFrame, fromCoordinateSpace = UIScreen.mainScreen.coordinateSpace)
    // UIKit's convertRect returns points; Compose's Offset/LayoutCoordinates (what [position] and
    // AutocaptureClaims bounds are expressed in) are in raw pixels — scale by the screen's point-to-pixel
    // ratio or every containment check here silently fails on any non-1x screen (confirmed on-device: a
    // 3x simulator reported a 48pt leaf frame against a 72px tap position, so `contains` was never true).
    val scale = UIScreen.mainScreen.scale.toFloat()
    return localFrame.useContents {
        Rect(
            origin.x.toFloat() * scale,
            origin.y.toFloat() * scale,
            (origin.x.toFloat() + size.width.toFloat()) * scale,
            (origin.y.toFloat() + size.height.toFloat()) * scale,
        )
    }
}

/**
 * Compose's accessibility root isn't necessarily attached to the receiver itself — it can live on a
 * sibling subview several levels down (see kdoc above) — so descendants are the union of both
 * `accessibilityElements()` (how the tree actually links together once inside it) and `subviews`
 * (how to reach into it from an arbitrary starting [UIView]).
 */
internal fun Any.accessibilityChildren(): List<Any> {
    val axChildren = (this as? NSObject)?.accessibilityElements()?.filterNotNull() ?: emptyList()
    val subviewChildren = (this as? UIView)?.subviews?.filterNotNull() ?: emptyList()
    return axChildren + subviewChildren
}

internal fun Any.isAccessibilityButton(): Boolean =
    (((this as? NSObject)?.accessibilityTraits() ?: 0uL) and UIAccessibilityTraitButton) != 0uL

private fun Any.accessibilityIdentifierOrNull(): String? =
    (this as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier
