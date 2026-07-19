package dev.ynagai.autograph.uikit

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
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
 * **Clickable branches win over the tie-break.** Before z-order is consulted at all, a branch that
 * yields a clickable ([isAccessibilityButton]) is preferred over one that yields none; the reverse
 * order above decides only among branches that tie on that. Without this the walk commits to the
 * first branch geometrically containing the point and never reconsiders, so a single empty view
 * covering the content swallows every tap. That is not hypothetical — measured on a real SwiftUI
 * `List`, where a full-screen `_UITouchPassthroughView` sits on top of the cells and, as its name
 * says, passes touches straight through to them. UIKit's own `hitTest` gets this right because such
 * views decline the hit; the accessibility tree carries no equivalent signal, so "did this branch
 * lead anywhere a tap can be attributed to" is the closest available stand-in.
 *
 * The trade this accepts: an opaque non-interactive overlay that genuinely *does* block touches — a
 * modal scrim over a button, say — is likewise invisible to this walk, and a tap on it now resolves
 * to the button beneath instead of to nothing. Both directions are wrong for some tree; this one is
 * wrong for the rarer one, and it fails toward reporting an event rather than toward the pipeline
 * being silently inert on every SwiftUI screen built out of `List`.
 *
 * Note this is decided per branch, not globally: a subtree with no clickable anywhere still resolves
 * exactly as before, so callers that don't care about clickability see no change.
 *
 * [view] supplies the coordinate space and [scale] the point-to-pixel ratio — both are handed to
 * [accessibilityBoundsInWindowPx] unchanged, so its precondition on [scale] applies here too.
 *
 * **Threading.** Main thread only: every property this reads ([accessibilityChildren],
 * `accessibilityFrame`, `subviews`) is main-thread-only UIKit API.
 *
 * **Termination.** The tree this walks is supplied by the host app, not by this module, and nothing
 * in the UIAccessibility contract forbids an element from listing an ancestor among its
 * `accessibilityElements`. Such a link makes the graph cyclic, and a naive descent would recurse
 * until the stack overflows. Two bounds prevent that: a branch that revisits a node already on the
 * path being built is abandoned (the parent then tries its next sibling), and descent stops at
 * [MAX_ACCESSIBILITY_TREE_DEPTH]. Both degrade to resolving a shallower element rather than
 * crashing — a missed leaf is a dropped event, an overflow takes the app down.
 */
@AutographInternalApi
public fun deepestAccessibilityHitPath(
    node: Any,
    view: UIView,
    positionInWindowPx: AxPoint,
    scale: Float,
): List<Any>? = deepestAccessibilityHitPath(node, view, positionInWindowPx, scale, ancestors = emptyList())

/**
 * Depth ceiling for [deepestAccessibilityHitPath]. Far above any real accessibility tree (UIKit
 * hierarchies run tens of levels, not hundreds), so it never truncates a genuine walk — it exists
 * only to bound a pathological or adversarial one that the cycle check can't catch, such as a chain
 * that generates a fresh element at every level.
 */
private const val MAX_ACCESSIBILITY_TREE_DEPTH = 256

/**
 * [ancestors] is the chain from the walk's starting node down to (not including) [node], carried so
 * the cycle check can ask whether [node] is already on it.
 *
 * Compared with `==`, not `===`, because Kotlin/Native does not canonicalize Objective-C wrappers:
 * fetching the same underlying element twice across the interop boundary yields two distinct Kotlin
 * objects. That is not theoretical here — [accessibilityChildren] reads `subviews`, and
 * `view.subviews.first() === view.subviews.first()` is *false*, so an identity check would silently
 * fail to detect the very cycles this guard exists for. `==` routes to `isEqual:`, whose NSObject
 * default is pointer equality on the underlying object, which is the comparison actually wanted.
 * (`UIView` and `UIAccessibilityElement` do not override `isEqual:`; if some element type did, the
 * cost would be abandoning a branch early — the same shallower-resolution degrade this walk already
 * accepts, not a failure to terminate.)
 */
@OptIn(AutographInternalApi::class)
private fun deepestAccessibilityHitPath(
    node: Any,
    view: UIView,
    positionInWindowPx: AxPoint,
    scale: Float,
    ancestors: List<Any>,
): List<Any>? {
    if (ancestors.size >= MAX_ACCESSIBILITY_TREE_DEPTH) return null
    if (ancestors.any { it == node }) return null
    val bounds = node.accessibilityBoundsInWindowPx(view, scale) ?: return null
    if (!bounds.contains(positionInWindowPx)) return null
    val pathToNode = ancestors + node
    // First branch that contains a clickable wins outright; otherwise the first branch that resolved
    // at all is kept as a fallback and the search continues, in case a later one does better. Reverse
    // order makes "first" mean topmost, so among branches that tie on clickability the z-order
    // tie-break above still decides. Only a fully clickable-free subtree is walked exhaustively, and
    // the depth and cycle bounds apply to that walk exactly as they do to a direct descent.
    var fallback: List<Any>? = null
    for (child in node.accessibilityChildren().asReversed()) {
        val branch = deepestAccessibilityHitPath(child, view, positionInWindowPx, scale, pathToNode) ?: continue
        if (branch.any { it.isAccessibilityButton() }) return listOf(node) + branch
        if (fallback == null) fallback = branch
    }
    return fallback?.let { listOf(node) + it } ?: listOf(node)
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

/**
 * The innermost element on this hit path that is clickable, or null if none is.
 *
 * Both iOS capture pipelines attribute a tap this way — `autograph-compose` for Compose
 * Multiplatform, [resolveNativeTapTarget] for UIKit/SwiftUI — and they must agree: the same element
 * has to resolve the same way no matter which pipeline observed the tap, or a hybrid app reports one
 * button under two names. Searching from the leaf upward is what makes a button inside a tappable row
 * attribute to the button rather than the row.
 *
 * This lives here, next to the predicate it applies, so that agreement is a single definition rather
 * than two call sites that happen to be written identically. The clickability predicate is expected
 * to change — [isAccessibilityButton] is deliberately narrow, and the documented `.onTapGesture`-on-
 * `Text` gap would be closed by widening it — and a widening applied to one copy only would silently
 * split the two pipelines apart.
 *
 * Expects the path in root-to-leaf order, as [deepestAccessibilityHitPath] returns it.
 */
@AutographInternalApi
public fun List<Any>.nearestAccessibilityClickable(): Any? =
    asReversed().firstOrNull { it.isAccessibilityButton() }

/** Whether this element exposes `UIAccessibilityTraitButton`, Autograph's clickability predicate. */
@AutographInternalApi
public fun Any.isAccessibilityButton(): Boolean =
    (((this as? NSObject)?.accessibilityTraits() ?: 0uL) and UIAccessibilityTraitButton) != 0uL

/**
 * This element's developer-set `accessibilityIdentifier`, or null — including when the identifier is
 * present but blank, which is treated as absent rather than reported as a target (see the body for
 * why, and for why it rejects without trimming).
 *
 * Deliberately the only identity source Autograph reads off this tree. `accessibilityLabel` is user-
 * facing text and is never read: UIKit gives no way to tell an explicit, developer-authored label
 * apart from one Compose Multiplatform synthesizes from the element's displayed text, so falling back
 * to it would silently defeat the "never capture displayed text" guarantee.
 *
 * **Why this needs two routes.** In Objective-C every `UIView` carries `accessibilityIdentifier` —
 * `UIView` adopts `UIAccessibilityIdentification` through a *category*. Kotlin/Native's cinterop does
 * not model protocol conformance added that way, so `UIView` (and therefore `UIButton`, and every
 * other UIKit control) is statically not a [UIAccessibilityIdentificationProtocol] and the cast below
 * fails at runtime — measured, not inferred. The property is still there; only the binding's view of
 * the type system is missing it, so it is reachable by asking the object itself.
 *
 * The protocol cast alone was enough while the sole caller was `autograph-compose`, because Compose
 * Multiplatform bridges its semantics as `UIAccessibilityElement` subclasses, which *do* conform in
 * the headers. Aiming this at a UIKit/SwiftUI tree (#62) makes the gap load-bearing: a `UIButton`
 * passes the clickability predicate and then yields no identifier, so every native tap is dropped and
 * the pipeline is silently inert.
 *
 * The fallback asks for the getter by selector, guarded by `respondsToSelector`, rather than trying
 * and recovering: an Objective-C exception crossing back into Kotlin is not a catchable Kotlin
 * exception, so a raise here takes the process down. The walk hands arbitrary objects to this
 * function, so asking first is the only safe order — and once `respondsToSelector` says yes,
 * `performSelector` cannot raise. (Key-value coding would reach the same property, but routes through
 * `valueForKey:`, which an object is free to override and reject a key from; the selector call has no
 * such surface.)
 */
@AutographInternalApi
@OptIn(ExperimentalForeignApi::class)
public fun Any.accessibilityIdentifierOrNull(): String? {
    val raw = if (this is UIAccessibilityIdentificationProtocol) {
        accessibilityIdentifier
    } else {
        val obj = this as? NSObject ?: return null
        if (!obj.respondsToSelector(accessibilityIdentifierSelector)) return null
        obj.performSelector(accessibilityIdentifierSelector) as? String
    }
    // A blank identifier is treated as absent rather than reported as a target. UIKit's own default
    // is nil, so an empty or whitespace-only string is not something a developer chose as a name —
    // it is an unset value that arrived through a template, a nil-coalesced binding, or an
    // interpolation that produced nothing. Reporting it emits an event whose target is blank in every
    // dashboard downstream: indistinguishable from an unnamed element, except that it looks
    // deliberate. Dropping it instead makes it the same non-event as an element with no identifier at
    // all, which is what it is. This matters more now the walk is aimed at arbitrary UIKit/SwiftUI
    // trees, where identifiers are far less curated than Compose testTags. Note this rejects, and
    // never trims: " foo " stays " foo ", because normalizing a name the developer did choose would
    // be a different and much less obvious decision.
    return raw?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalForeignApi::class)
private val accessibilityIdentifierSelector = NSSelectorFromString("accessibilityIdentifier")
