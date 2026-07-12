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
import platform.UIKit.accessibilityLabel
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
 * `accessibilityFrame` is documented by Apple as screen coordinates; converted back to the root
 * view's local space via `UIView.convertRect(_:fromCoordinateSpace:)` (confirmed on-device to
 * exactly match the element's declared local size/position, once scaled — see
 * [accessibilityLocalBounds]) to compare against [Offset]s from the pointer-input tree, which are
 * already local to that same root.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberElementResolver(): ElementResolver {
    val view = LocalUIView.current
    val claims = LocalAutocaptureClaims.current
    return remember(view, claims) {
        ElementResolver { _, position ->
            if (claims != null && claims.ignored.values.any { it.contains(position) }) return@ElementResolver null
            val path = deepestAccessibilityHitPath(view, view, position) ?: return@ElementResolver null
            val nearestClickable = path.asReversed().firstOrNull { it.isAccessibilityButton() } ?: return@ElementResolver null
            if (claims != null && claims.instrumented.values.any { it.contains(position) }) return@ElementResolver null
            identifierFrom(testTag = nearestClickable.accessibilityIdentifierOrNull(), role = null, label = nearestClickable.accessibilityLabelOrNull())
        }
    }
}

/**
 * Depth-first search mirroring [findDeepestHit]/[AutocaptureNode]'s Android counterpart, but over
 * the UIKit accessibility tree: returns the path from [node] down to the deepest descendant whose
 * [accessibilityFrame] contains [position], or null if [node] itself doesn't contain it. Preferring
 * later (visually on top) children when bounds overlap, same as Android.
 */
@OptIn(ExperimentalForeignApi::class)
private fun deepestAccessibilityHitPath(node: Any, view: UIView, position: Offset): List<Any>? {
    val bounds = node.accessibilityLocalBounds(view) ?: return null
    if (!bounds.contains(position)) return null
    val children = node.accessibilityChildren()
    for (child in children.asReversed()) {
        deepestAccessibilityHitPath(child, view, position)?.let { return listOf(node) + it }
    }
    return listOf(node)
}

@OptIn(ExperimentalForeignApi::class)
private fun Any.accessibilityLocalBounds(view: UIView): Rect? {
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
private fun Any.accessibilityChildren(): List<Any> {
    val axChildren = (this as? NSObject)?.accessibilityElements()?.filterNotNull() ?: emptyList()
    val subviewChildren = (this as? UIView)?.subviews?.filterNotNull() ?: emptyList()
    return axChildren + subviewChildren
}

private fun Any.isAccessibilityButton(): Boolean =
    (((this as? NSObject)?.accessibilityTraits() ?: 0uL) and UIAccessibilityTraitButton) != 0uL

private fun Any.accessibilityIdentifierOrNull(): String? =
    (this as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier

private fun Any.accessibilityLabelOrNull(): String? = (this as? NSObject)?.accessibilityLabel()
