package dev.ynagai.autograph.uikit

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

/*
 * Hit-testing over the UIKit accessibility tree — the standard, public
 * `UIView.accessibilityElements` / `UIAccessibilityElement.accessibilityElements` container API,
 * walked recursively.
 *
 * This is the one mechanism that identifies a tapped element across every iOS UI framework Autograph
 * supports: UIKit and SwiftUI populate this tree natively, and Compose Multiplatform bridges its own
 * semantics tree into it (as `CMPAccessibilityElement`, a `UIAccessibilityElement` subclass). Hence
 * this module: `autograph-compose` resolves CMP taps through it, and a UIKit/SwiftUI-only app can
 * reach it without depending on Compose at all.
 *
 * Compose's `SemanticsOwner` is deliberately NOT used on iOS (unlike Android): there is no supported
 * route to one from application code — `LocalComposeScene`, the `ComposeRootRegistry` that
 * `compose-ui-test` itself relies on, and the Kotlin class backing each accessibility element are all
 * `internal` or `private` to the Compose UI library. The native accessibility tree is the escape
 * hatch, and it uses only public, documented UIKit API.
 *
 * Confirmed on-device (`ComposeUIViewController` hosted in a real `.app`, installed and launched via
 * `xcrun simctl`, Compose Multiplatform 1.11.1) that this tree is populated on the very first layout
 * pass, **VoiceOver on or off**. An earlier attempt concluded otherwise because it read
 * `LocalUIView.current.accessibilityElements()` directly, which is empty: Compose attaches the real
 * accessibility root to a *sibling* subview several levels down (`ComposeContainerView.subviews[2]` —
 * `OverlayInputView` — in the traced case, though that index isn't a contract worth hard-coding), not
 * to the view `LocalUIView` itself returns. Walking `subviews` alongside `accessibilityElements` at
 * every `UIView` node ([accessibilityChildren]) finds it regardless of which subview it lives under.
 * `AccessibilitySyncOptions` (the old opt-out config) was removed in CMP 1.8 — the bridge is
 * unconditionally on, not gated behind an active screen reader.
 *
 * **What is NOT reachable this way: custom semantics keys.** The bridge only carries the fixed
 * UIAccessibility properties — label, traits, identifier, frame. Anything a caller needs to know
 * beyond those (which subtree is excluded from capture, which element is already instrumented) has to
 * be tracked outside the tree; `autograph-compose` keeps a positional registry for exactly this
 * reason.
 */

/**
 * Returns the path from [node] down to the deepest descendant whose accessibility frame contains
 * [positionInWindowPx], or null if [node] itself doesn't contain it. Later (visually on top) children
 * win when bounds overlap.
 *
 * The full root-to-leaf path is returned, not just the leaf, so callers can inspect ancestry — pick
 * the nearest clickable ancestor of the hit node, or detect that the path crosses a view owned by
 * another capture pipeline.
 *
 * [view] supplies the coordinate space (see [accessibilityBoundsInWindowPx]); [scale] is the
 * point-to-pixel ratio, passed in rather than read from [UIScreen] so the unit conversion is visible
 * at the call site and testable.
 */
@AutographInternalApi
@OptIn(ExperimentalForeignApi::class)
public fun deepestAccessibilityHitPath(
    node: Any,
    view: UIView,
    positionInWindowPx: AxPoint,
    scale: Float,
): List<Any>? {
    val bounds = node.accessibilityBoundsInWindowPx(view, scale) ?: return null
    if (!bounds.contains(positionInWindowPx)) return null
    for (child in node.accessibilityChildren().asReversed()) {
        deepestAccessibilityHitPath(child, view, positionInWindowPx, scale)?.let { return listOf(node) + it }
    }
    return listOf(node)
}

/**
 * This element's `accessibilityFrame` (documented by Apple as screen coordinates) converted into
 * **window-space pixels** — see [AxRect] for that space and the two on-device bugs that motivate it.
 *
 * Conversion is into [view]'s *window's* coordinate space, not [view]'s own. Falls back to [view]
 * itself when it has no window yet (e.g. headless unit tests, which never attach one), where
 * window-relative and view-relative are the same thing anyway.
 *
 * [scale] is the point-to-pixel ratio (`UIScreen.scale`), taken as an argument rather than read
 * internally so callers convert deliberately. Note the conversion uses `UIScreen.mainScreen`'s
 * coordinate space as the source, which is not the right screen for content on an external display —
 * a pre-existing limitation, unchanged here.
 */
@AutographInternalApi
@OptIn(ExperimentalForeignApi::class)
public fun Any.accessibilityBoundsInWindowPx(view: UIView, scale: Float): AxRect? {
    val screenFrame = (this as? NSObject)?.accessibilityFrame() ?: return null
    val coordinateSpace = view.window ?: view
    val windowFrame = coordinateSpace.convertRect(screenFrame, fromCoordinateSpace = UIScreen.mainScreen.coordinateSpace)
    return windowFrame.useContents {
        AxRect(
            origin.x.toFloat() * scale,
            origin.y.toFloat() * scale,
            (origin.x.toFloat() + size.width.toFloat()) * scale,
            (origin.y.toFloat() + size.height.toFloat()) * scale,
        )
    }
}

/**
 * This element's accessibility descendants: the union of `accessibilityElements()` (how the tree
 * actually links together once inside it) and `subviews` (how to reach into it from an arbitrary
 * starting [UIView]).
 *
 * Both are needed because an accessibility root isn't necessarily attached to the view you start
 * from — see this file's leading documentation for the Compose case that proves it.
 */
@AutographInternalApi
public fun Any.accessibilityChildren(): List<Any> {
    val axChildren = (this as? NSObject)?.accessibilityElements()?.filterNotNull() ?: emptyList()
    val subviewChildren = (this as? UIView)?.subviews?.filterNotNull() ?: emptyList()
    return axChildren + subviewChildren
}

/** Whether this element exposes `UIAccessibilityTraitButton`, Autograph's clickability predicate. */
@AutographInternalApi
public fun Any.isAccessibilityButton(): Boolean =
    (((this as? NSObject)?.accessibilityTraits() ?: 0uL) and UIAccessibilityTraitButton) != 0uL

/**
 * This element's developer-set `accessibilityIdentifier`, or null.
 *
 * Deliberately the only identity source Autograph reads off this tree. `accessibilityLabel` is user-
 * facing text and is never read: UIKit gives no way to tell an explicit, developer-authored label
 * apart from one Compose Multiplatform synthesizes from the element's displayed text, so falling back
 * to it would silently defeat the "never capture displayed text" guarantee.
 */
@AutographInternalApi
public fun Any.accessibilityIdentifierOrNull(): String? =
    (this as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier
