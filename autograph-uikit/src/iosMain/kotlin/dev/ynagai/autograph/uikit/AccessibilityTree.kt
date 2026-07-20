package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi
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
 * **[preferClickableBranches] turns the preference off**, restoring the plain topmost-branch descent.
 * That is not a compatibility shim: the preference answers "which element should this tap be
 * attributed to", and a caller asking "where did this tap visually land" needs the other answer.
 * [resolveNativeTapTarget] needs both — it decides Compose ownership from the topmost path, because
 * a preference for clickables would otherwise route the path around a Compose host and let the native
 * pipeline claim a tap that landed on Compose-owned content — and resolves the target from the
 * preferred one. Conflating the two is exactly the bug that motivated splitting them.
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
 * until the stack overflows. Three bounds prevent that: a branch that revisits a node already on the
 * path being built is abandoned (the parent then tries its next sibling), descent stops at
 * [MAX_ACCESSIBILITY_TREE_DEPTH], and the walk as a whole stops after
 * [MAX_ACCESSIBILITY_NODE_VISITS] nodes. The last is what bounds *breadth* rather than depth, and it
 * is load-bearing precisely because of the clickable preference above: exploring every branch of a
 * clickable-free subtree compounds per level whenever a node is reachable by more than one route. All
 * three degrade to resolving a shallower element rather than crashing — a missed leaf is a dropped
 * event, an overflow or a multi-second walk on the main thread is a wedged app.
 */
@AutographInternalApi
public fun deepestAccessibilityHitPath(
    node: Any,
    view: UIView,
    positionInWindowPx: AxPoint,
    scale: Float,
    preferClickableBranches: Boolean = true,
): List<Any>? = deepestAccessibilityHitPath(
    node,
    view,
    positionInWindowPx,
    scale,
    preferClickableBranches,
    ancestors = emptyList(),
    budget = intArrayOf(MAX_ACCESSIBILITY_NODE_VISITS),
)

/**
 * Depth ceiling for [deepestAccessibilityHitPath]. Far above any real accessibility tree (UIKit
 * hierarchies run tens of levels, not hundreds), so it never truncates a genuine walk — it exists
 * only to bound a pathological or adversarial one that the cycle check can't catch, such as a chain
 * that generates a fresh element at every level.
 */
private const val MAX_ACCESSIBILITY_TREE_DEPTH = 256

/**
 * Total-work ceiling for one [deepestAccessibilityHitPath] call, counted in nodes examined.
 *
 * [MAX_ACCESSIBILITY_TREE_DEPTH] bounds how *deep* the walk goes; this bounds how *wide*. The two
 * became different questions once the walk started exploring every branch of a clickable-free
 * subtree instead of committing to the first: depth alone does not bound a tree whose nodes are
 * reachable by more than one route, where the branch count compounds per level. [accessibilityChildren]
 * removes the one duplication source this module creates, but the tree is the host app's, and nothing
 * stops it from sharing a subtree between two parents.
 *
 * Sized far above any real hit-test — a genuine walk examines the nodes along one root-to-leaf chain
 * plus their siblings, tens to low hundreds — so it only ever fires on a shape that would otherwise
 * hang. Exhausting it degrades exactly as the depth ceiling does: a shallower element resolves, which
 * is a dropped event rather than a frozen main thread.
 */
private const val MAX_ACCESSIBILITY_NODE_VISITS = 10_000

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
    preferClickableBranches: Boolean,
    ancestors: List<Any>,
    budget: IntArray,
): List<Any>? {
    // Single-element array rather than a counter field: the walk is main-thread-only and this is the
    // cheapest way to carry one mutable count down a recursion whose callers must not see it.
    if (budget[0]-- <= 0) return null
    if (ancestors.size >= MAX_ACCESSIBILITY_TREE_DEPTH) return null
    if (ancestors.any { it == node }) return null
    val bounds = node.accessibilityBoundsInWindowPx(view, scale) ?: return null
    if (!bounds.contains(positionInWindowPx)) return null
    val pathToNode = ancestors + node
    // Keep the first branch that resolved at all as a fallback and carry on looking for a clickable
    // one — see the kdoc for why clickability outranks the z-order tie-break. Walking on instead of
    // returning is what makes a clickable-free subtree exhaustive, which is what
    // MAX_ACCESSIBILITY_NODE_VISITS is there to bound.
    var fallback: List<Any>? = null
    for (child in node.accessibilityChildren().asReversed()) {
        val branch = deepestAccessibilityHitPath(
            child, view, positionInWindowPx, scale, preferClickableBranches, pathToNode, budget,
        ) ?: continue
        if (!preferClickableBranches) return listOf(node) + branch
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
 * from — see this file's leading documentation for the Compose case that proves it. It is a union and
 * not a concatenation: an element that appears in both lists is one child, and returning it twice
 * costs [deepestAccessibilityHitPath] an exponential amount of repeated work (see the body).
 */
@AutographInternalApi
public fun Any.accessibilityChildren(): List<Any> {
    val axChildren = (this as? NSObject)?.accessibilityElements()?.filterNotNull() ?: emptyList()
    val subviewChildren = (this as? UIView)?.subviews?.filterNotNull() ?: emptyList()
    // Deduplicated, keeping first occurrence. A view is free to list its own subviews in
    // `accessibilityElements` — `accessibilityElements = subviews` is an ordinary way to pin
    // VoiceOver's reading order — and the union would then return each of them twice. One child
    // reached by two routes is still one child, and the duplicate is not free:
    // [deepestAccessibilityHitPath] explores every branch of a clickable-free subtree, so a duplicate
    // at every level costs 2^depth walks of the same nodes. Measured before this filter existed: 18
    // such levels took ~11s to resolve a single tap, on the main thread inside a tap handler.
    //
    // Compared with `==`, not `distinct()`/`toSet()`, for the reason [deepestAccessibilityHitPath]'s
    // cycle guard documents: Kotlin/Native hands out a fresh wrapper per interop crossing, so the
    // same underlying element arrives as two objects that a hash-based dedup would keep. `==` routes
    // to `isEqual:`, whose NSObject default is pointer equality on the underlying object. The list is
    // a node's child count, so the quadratic scan is cheaper than the allocation it avoids.
    val children = axChildren + subviewChildren
    return children.filterIndexed { index, child -> children.subList(0, index).none { it == child } }
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
