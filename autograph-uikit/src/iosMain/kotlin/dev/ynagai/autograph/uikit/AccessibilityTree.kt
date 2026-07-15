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
 * [positionInWindowPx], or null if [node] itself doesn't contain it.
 *
 * The full root-to-leaf path is returned, not just the leaf, so callers can inspect ancestry — pick
 * the nearest clickable ancestor of the hit node, or detect that the path crosses a view owned by
 * another capture pipeline.
 *
 * **Overlap tie-break, and its limit.** Children are searched in reverse order, so among *subviews* a
 * later sibling — the one drawn on top — wins an overlap. That is only a true z-order tie-break within
 * a single group: [accessibilityChildren] returns `accessibilityElements + subviews`, a concatenation
 * whose across-group order has no relation to what is drawn on top, and reversing it searches every
 * subview before every accessibility element. So a node that exposes an on-top overlay through
 * `accessibilityElements` while the covered content is a plain subview resolves a tap to the covered
 * subview instead of the overlay. This is long-standing behavior, unchanged by the extraction —
 * documented rather than fixed, since this walk is now shared API and its callers should know the
 * edge of the contract they depend on.
 *
 * [view] supplies the coordinate space and [scale] the point-to-pixel ratio — both are handed to
 * [accessibilityBoundsInWindowPx] unchanged, so its precondition on [scale] applies here too.
 */
@AutographInternalApi
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
 * **[scale] must be `UIScreen.mainScreen.scale`** — not `view.window?.screen?.scale`. The source
 * coordinate space below is hard-wired to `UIScreen.mainScreen`, so [scale] is the other half of one
 * conversion and the two have to name the same screen. Passing another screen's scale converts out of
 * mainScreen's space but multiplies by a different ratio: every frame lands at the wrong size, no
 * frame contains the tap, and the walk returns null for every tap — silently capturing nothing. The
 * argument exists to make the unit conversion visible and testable at the call site, not to let
 * callers choose a screen.
 *
 * That mainScreen is hard-wired at all is a pre-existing limitation (unchanged here): it is the wrong
 * screen for content on an external display. Fixing that means sourcing *both* halves from the same
 * `view.window?.screen`, which is a behavior change and deliberately out of scope for this extraction.
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
