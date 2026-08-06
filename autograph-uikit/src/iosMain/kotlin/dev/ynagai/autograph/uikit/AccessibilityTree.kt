package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitNotEnabled
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
 * `xcrun simctl`, Compose Multiplatform 1.11.1) that this walk reaches Compose's bridged elements
 * **without requiring an accessibility client** — VoiceOver off, no Inspector, no test runner attached.
 * The reason is not that the bridge is built eagerly: since CMP 1.8 (compose-multiplatform-core#1780)
 * it is built on demand. It is that the activation call site cannot distinguish one caller from
 * another — `AccessibilityRoot.accessibilityElements()` calls `activateAccessibilityIfNeeded()` for
 * whoever asks, and this walk asks. Two gates sit in front of that, neither of them tied to assistive
 * technology: `AccessibilityMediator.isEnabled` — set from `ComposeSceneMediator.isAccessibilityEnabled`,
 * which a reversed layer walk at scene setup leaves on unless a `focusable` layer sits above the scene,
 * i.e. it disables only a scene that could not receive the tap anyway — and the traversal itself.
 * (The other two entry points, `focusItemsInRect` and `accessibilityHitTest`, activate the same way
 * behind the same gate.) The old opt-out config `AccessibilitySyncOptions`, whose
 * `WhenRequiredByAccessibilityServices` default would have gated the bridge behind a running screen
 * reader, was removed by #1780 as well, so consumers can no longer turn it off. Do not restate this as
 * unconditional: it is a dependency on CMP's activation path, which is what makes the cold-device check
 * on a CMP bump (#154) more than superstition.
 *
 * Those names were read out of the **1.11.1 klib this project resolves**, not out of a development
 * branch — `AccessibilityRoot`, `activateAccessibilityIfNeeded`, all three entry points and
 * `isAccessibilityEnabled` are present in it; `AccessibilitySyncOptions` and
 * `WhenRequiredByAccessibilityServices` are absent from it. They are Compose-internal and free to move,
 * so re-read them on a CMP bump rather than trusting this paragraph.
 *
 * The trajectory is favourable, not fragile: compose-multiplatform-core#2416 (2025-09) added, in so
 * many words, support for **UI Automation** reaching child elements inside accessibility elements —
 * non-screen-reader clients, deliberately — and #2760 (2026-02) added `accessibilityHitTest` as a
 * third activation entry point. Those are the endpoints, not a survey of the eighteen months between
 * them: from #1780 to the 1.11.1 klib above, the opt-out knob is gone and the entry points went from
 * one to three.
 *
 * An earlier attempt concluded the tree was absent because it read
 * `LocalUIView.current.accessibilityElements()` directly, which is empty: Compose attaches the real
 * accessibility root to a *sibling* subview several levels down (`ComposeContainerView.subviews[2]` —
 * `OverlayInputView` — in the traced case, though that index isn't a contract worth hard-coding), not
 * to the view `LocalUIView` itself returns. Walking `subviews` alongside `accessibilityElements` at
 * every `UIView` node ([accessibilityChildren]) finds it regardless of which subview it lives under.
 *
 * **What is NOT reachable this way: custom semantics keys.** The bridge only carries the fixed
 * UIAccessibility properties — label, traits, identifier, frame. Anything a caller needs to know
 * beyond those (which subtree is excluded from capture, which element is already instrumented) has to
 * be tracked outside the tree; `autograph-compose` keeps a positional registry for exactly this
 * reason.
 *
 * **Measured (#156): this walk also flips CMP's internal "a screen reader is active" belief**
 * (`LocalPlatformScreenReader.current.isActive`, set from `AccessibilityRoot`'s `element` setter on
 * tree sync). Cold-device A/B: a single tap that runs this walk flips it `false → true` (further taps
 * are moot once flipped); an identical tap through plain Compose with no walk of ours leaves it
 * `false`, unchanged across repeats. Expected, not a new failure mode — the activation call site this
 * file already documents cannot distinguish an app from a screen reader, and that is exactly what this
 * flag records. Its only confirmed consumer is `isScreenReaderFocusable` (present in the same klib,
 * read inside CMP's own focus-traversal code); the flag itself is `@InternalComposeUiApi` with no
 * supported read path from application code, so no application code sitting downstream of Compose can
 * observe it. Whether it perturbs actual screen-reader traversal order was not independently
 * measurable: the only way to observe AT-facing behaviour is to attach a real AT, and attaching one
 * sets this same flag by itself — so a differential test would compare "AT plus our walk" against
 * "AT alone," not against a walk-free baseline. Autograph's walk cannot manufacture a state a genuine
 * AT visit wouldn't already produce; it can only make CMP believe that arrival happened somewhat
 * earlier and more often than it otherwise would. Closed as a measured non-issue.
 */

/**
 * Returns the path from [node] down to the deepest descendant whose accessibility frame contains
 * [positionInWindowPx], or null when nothing in the tree does.
 *
 * The full root-to-leaf path is returned, not just the leaf, so callers can inspect ancestry — pick
 * the nearest clickable ancestor of the hit node, or detect that the path crosses a view owned by
 * another capture pipeline.
 *
 * **The starting node is not a filter.** Containment gates the descent at every node except [node]
 * itself: [node] is the caller's choice of *where to search*, and its own frame says nothing about
 * where its descendants are. This is not a hypothetical distinction — it is the difference between
 * working and reporting nothing at all. Measured on a simulator created fresh, with no accessibility
 * client ever connected to the process, Compose Multiplatform's `OverlayInputView` (the view
 * `autograph-compose` starts this walk from, via `LocalUIView.current`) reports
 * `accessibilityFrame = CGRectZero`, while every bridged element beneath it already carries a correct
 * frame, identifier and traits. Gating on the starting node dropped **every** Compose tap in that
 * state, for the life of the process — and the tap itself was never in doubt: the element's own
 * `onClick` fired each time. Anything that connects to the accessibility subsystem (XCUITest,
 * VoiceOver, the Accessibility Inspector) populates that frame and hides the whole failure, which is
 * why the `sample-ios` XCUITest suite passed throughout: its runner is itself such a client. See #135.
 *
 * This exemption does **not** rescue [resolveNativeTapTarget] in that state, and the reason is not the
 * one an earlier version of this note gave (it claimed the native walk's `UIWindow` reports a valid
 * frame when cold — measured false, it reports `CGRectZero` too). Cold, UIKit and SwiftUI have not built
 * an accessibility tree at all: the walk reaches only plain `UIView`s through `subviews`, every one of
 * them reporting an empty frame and no traits, with not a single `SwiftUI.AccessibilityNode` or button
 * trait anywhere. So the exemption gets the walk past the root and every child then prunes on its own
 * empty frame. Compose differs because Compose Multiplatform builds its bridged elements itself, on an
 * activation path that reading the tree is enough to trigger (see the note at the top of this file) —
 * those are present and correct while cold, which is why exempting the root is enough there and only
 * there. See [resolveNativeTapTarget] for what that costs the native pipeline.
 *
 * **What the exemption does not loosen.** Two properties are preserved deliberately, because relaxing
 * the descent could otherwise turn a dropped event into a misattributed one — the worse failure:
 *
 * - A [node] that does not contain the position is never returned *alone*; only as an ancestor of a
 *   descendant that does. Every other element on a returned path contains the position.
 * - A [node] that is itself clickable keeps its gate, so it is never on a path it doesn't contain.
 *   It stays visible to ancestry checks on the paths it does contain, and there is no way to hold it
 *   in the path for those while withholding it from [nearestAccessibilityClickable], which knows no
 *   geometry. Measured: without that clause a clickable starting node whose frame missed the tap was
 *   attributed the tap whenever any inert child contained it.
 *
 * One consequence is genuinely new: a tap outside [node]'s own frame can now resolve, where it
 * previously always dropped. Both shipped callers pass a root that contains every tap they are asked
 * about (a `UIWindow`, or the Compose host's overlay view), so this widens where the walk's documented
 * overlap ambiguity below can be reached without changing which element any current tap names.
 *
 * **Overlap tie-break, and its limits.** Children are searched in reverse order, so among *subviews* a
 * later sibling — the one drawn on top — wins an overlap. That is a true z-order tie-break only for
 * subviews, and it fails in two measured ways.
 *
 * *Across groups*: [accessibilityChildren] returns `accessibilityElements + subviews`, a concatenation
 * whose across-group order has no relation to what is drawn on top, and reversing it searches every
 * subview before every accessibility element. So a node that exposes an on-top overlay through
 * `accessibilityElements` while the covered content is a plain subview resolves a tap to the covered
 * subview instead of the overlay.
 *
 * *Within `accessibilityElements`*: the order there is whatever the element's provider chose, and
 * nothing obliges a provider to choose z-order. Across nine Compose Multiplatform fixtures the emitted
 * order fit `(left, top)` lexicographically — **x-primary**. That is a fit to those nine, not a
 * contract; what the fixtures do establish is the two things it is *not*. It is not declaration order
 * (one fixture declares its small element first and the bridge emits it second), and it is not
 * y-primary reading order (one fixture emits the element with the *smaller* `top` second). Either way
 * the order is unrelated to what is drawn on top, so reversing it breaks an overlap in favour of an
 * element chosen for reasons that have nothing to do with which one received the tap. See #140.
 *
 * **What narrows this, and only on Compose.** The bridge subtracts an occluding sibling's rect from
 * the **covered** sibling's `accessibilityFrame` when the remainder is still an axis-aligned rectangle
 * — an edge strip. Measured in three fixtures, one per direction (top, bottom, left): the covered
 * element lost exactly the strip its neighbour covered, and no other fixture was trimmed. Since the
 * trim lands on the covered element it is itself a z-order signal, and where it applies the covered
 * element stops containing the tap, so the ambiguity is gone before this tie-break is consulted. It
 * follows real draw order rather than declaration order: two fixtures with identical geometry *and*
 * identical declaration order, differing only by `Modifier.zIndex`, trim differently. No trim was
 * observed where the remainder would not be one rectangle — measured for corner overhangs, where both
 * siblings kept full frames and both contained the tap.
 *
 * So the resulting misattribution takes two different shapes:
 *
 * - **Compose Multiplatform**: the overlap is not trimmable **and** the element on top sorts earlier
 *   under the order above (further left, or same left and higher). Both are required — a leftward or
 *   upward overhang whose overlap *is* trimmable resolves correctly, as three fixtures did. Measured
 *   to resolve correctly: a full-width overlay over content and a horizontal overlap, because the trim
 *   settles them; a badge overhanging to the top-right, because although its corner overlap is *not*
 *   trimmable the badge sits further right, so it sorts later and wins the tie-break on its own. The
 *   measured failure is a corner overhang **straight up** — the two elements share a `left`, and the
 *   one on top has the smaller `top`, so it sorts first. A strictly *leftward* overhang follows from
 *   the order above but was never run: no fixture put the on-top element at a smaller `left`.
 * - **UIKit / SwiftUI**: no trim was observed. In the one geometry Compose *does* trim — a covered
 *   element under a full-width strip — a SwiftUI button reported the same frame as an identical
 *   un-overlapped one, so the rescue above is absent and the tie-break is left to decide alone. That
 *   makes the same overlap that Compose disambiguates a candidate for misattribution here.
 *   Reproduced end to end through [resolveNativeTapTarget]. Measured for SwiftUI only; no UIKit
 *   hierarchy was run, and one trimmable geometry is not a sweep.
 *
 * **Do not reach for the obvious rankings.** Scored against the oracle — which element's handler
 * actually fired — over the five measured ambiguous cases, each is refuted: last-emitted (what this
 * walk does) by the overhang above; smallest-area by two fixtures where the *larger* element is on
 * top; first-emitted by four. Smallest-area survived a first round only because the fixture built to
 * refute it had a trimmable overlap, so the trim removed the covered element from the candidate set
 * before the rule was tested. ("Most-specific-frame", the other ranking #140 floated, coincides with
 * smallest-area on every one of these fixtures and was not scored as a separate rule.) Three refuted
 * rules are not the whole space, but they are the ones worth not re-deriving.
 *
 * All of this is documented rather than fixed — this walk is shared API and its callers should know
 * the edge of the contract they depend on. Note in particular that any change here moves
 * [resolveNativeTapTarget] too, including the second walk it runs with [preferClickableBranches] off
 * to decide *ownership*, which is a privacy boundary and not a de-duplication nicety.
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
    val bounds = node.accessibilityBoundsInWindowPx(view, scale)
    val containsPosition = bounds != null && bounds.contains(positionInWindowPx)
    // Containment gates the descent at every node EXCEPT the one the walk started from — see the
    // kdoc's "The starting node is not a filter". `ancestors` is empty only at that starting node.
    //
    // A *clickable* starting node keeps its gate, though. It stays on every path this returns, so
    // [nearestAccessibilityClickable] would happily attribute the tap to it, and there is no way to hold
    // it in the path for ancestry (the exclusion vetoes need the whole chain) while withholding it from
    // attribution. Measured: without this clause, a clickable starting node whose frame misses the tap
    // is reported whenever any child resolves — a dropped event turned into a misattributed one, the
    // wrong side of that trade. Neither pipeline's real starting node is clickable (Compose's
    // `OverlayInputView`, the native path's `UIWindow`), so the cold-start fix is unaffected.
    if (!containsPosition && (ancestors.isNotEmpty() || node.isAccessibilityButton())) return null
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
    // A bare starting node is returned only when it genuinely contains the position. Without that
    // condition, ungating the descent above would also make a *clickable* starting node claim a tap
    // that landed outside it — turning a drop into a misattribution, which is the worse failure.
    return fallback?.let { listOf(node) + it } ?: if (containsPosition) listOf(node) else null
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
 * Whether any **strict** descendant of this element publishes an accessibility frame satisfying
 * [predicate]. This element itself is never offered to [predicate].
 *
 * Unlike [deepestAccessibilityHitPath] this does not gate the descent on containing a position: the
 * caller is asking about the subtree as a whole, not about one tap. `autograph-compose` uses it to
 * ask whether an instrumented claim describes a descendant of the resolved clickable rather than the
 * clickable itself (#153) — a question that has to hold for taps anywhere on the clickable, including
 * the margin outside the descendant, so a position-gated walk would answer it correctly only for some
 * of them.
 *
 * **Threading.** Main thread only, for the same reason [deepestAccessibilityHitPath] is.
 *
 * **Termination.** Bounded exactly as [deepestAccessibilityHitPath] is, against the same
 * host-supplied and possibly cyclic tree: a branch revisiting a node already on the path is
 * abandoned, descent stops at [MAX_ACCESSIBILITY_TREE_DEPTH], and the walk stops after
 * [MAX_ACCESSIBILITY_NODE_VISITS] nodes. Exhausting any of them returns false — for the caller above
 * that degrades to keeping the veto, i.e. a dropped event rather than a wedged main thread, matching
 * how the rest of this file resolves the same trade.
 */
@AutographInternalApi
public fun Any.anyAccessibilityDescendant(
    view: UIView,
    scale: Float,
    predicate: (AxRect) -> Boolean,
): Boolean = anyAccessibilityDescendant(
    view,
    scale,
    predicate,
    ancestors = listOf(this),
    budget = intArrayOf(MAX_ACCESSIBILITY_NODE_VISITS),
)

@OptIn(AutographInternalApi::class)
private fun Any.anyAccessibilityDescendant(
    view: UIView,
    scale: Float,
    predicate: (AxRect) -> Boolean,
    ancestors: List<Any>,
    budget: IntArray,
): Boolean {
    if (ancestors.size >= MAX_ACCESSIBILITY_TREE_DEPTH) return false
    for (child in accessibilityChildren()) {
        if (budget[0]-- <= 0) return false
        // `==`, not `===`, for the reason deepestAccessibilityHitPath's cycle guard documents.
        if (ancestors.any { it == child }) continue
        val bounds = child.accessibilityBoundsInWindowPx(view, scale)
        if (bounds != null && predicate(bounds)) return true
        if (child.anyAccessibilityDescendant(view, scale, predicate, ancestors + child, budget)) return true
    }
    return false
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
 * Whether this element exposes `UIAccessibilityTraitNotEnabled` — the iOS counterpart of Compose's
 * `SemanticsProperties.Disabled`, and Autograph's signal that activating this element runs nothing.
 * Measured: Compose Multiplatform bridges `Modifier.clickable(enabled = false)` as
 * `Button|NotEnabled`, and so do a disabled `UIButton` and a SwiftUI `Button` under `.disabled(true)`.
 *
 * **Deliberately separate from [isAccessibilityButton], not folded into it.** That predicate decides
 * which elements *take* a hit, and a disabled control still swallows the touch it is drawn over.
 * Narrowing it would make the walk fall through to whatever sits beneath or around the disabled
 * element — which never received the tap either, so a phantom event would be traded for a
 * misattributed one, the worse failure. Measured for Compose Multiplatform: tapping a disabled
 * clickable nested in an enabled clickable parent fires *nothing*, so the parent did not receive the
 * tap. In UIKit the mechanism is `UIControl`'s: `isEnabled` is not `isUserInteractionEnabled`, so a
 * disabled control can still be what `hitTest` returns while suppressing its own control action.
 *
 * So a disabled element keeps taking the hit and is vetoed at the point where a hit becomes an event:
 * [resolveNativeTapTarget] and `autograph-compose`'s `resolveIosElement`. **Both must apply it** — the
 * same element has to resolve the same way whichever pipeline observed the tap, or a hybrid app
 * reports one control under two behaviours. Note that unlike [nearestAccessibilityClickable] the veto
 * itself is not centralized, only this predicate is: a third resolution site would have to remember
 * to ask. The veto is confined to the element the tap resolves to and is never made subtree-wide: on
 * Android, whose `Disabled` semantics this mirrors, a disabled *ancestor* measurably does not block an
 * enabled clickable descendant (#128).
 *
 * **What this trait does not prove, and the drop it therefore costs.** It is the best signal the
 * accessibility tree carries, not a proof that nothing ran. It is the element's own claim about
 * itself: an app is free to publish it on a view that still has a working `UITapGestureRecognizer`, or
 * to mark a live `Modifier.clickable` disabled through `semantics { disabled() }`, and such a tap does
 * fire while this drops it. That is the exact shape #128 documents on Android, and it is the same
 * trade for the same reason — the alternative reading of the trait misattributes instead. Separately,
 * a genuinely disabled control does not stop an *ancestor* gesture recognizer from seeing the touch,
 * so an app that puts its own tap handler on a container around a disabled control loses that event
 * here too.
 */
@AutographInternalApi
public fun Any.isAccessibilityDisabled(): Boolean =
    (((this as? NSObject)?.accessibilityTraits() ?: 0uL) and UIAccessibilityTraitNotEnabled) != 0uL

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
