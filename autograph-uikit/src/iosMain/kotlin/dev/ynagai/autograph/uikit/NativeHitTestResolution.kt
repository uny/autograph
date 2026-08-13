package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGPointMake
import platform.UIKit.UIControl
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIScrollView
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView

/*
 * iOS's native tap resolver — since #191, the only one.
 *
 * Its predecessor identified a tapped element by walking the **accessibility tree**, which
 * UIKit and SwiftUI build on demand — and measured on a physical iPad Pro 11" (3rd gen) running
 * iOS 26.2.1, rebooted and launched from the home screen, they had built nothing at all: 0 of 15 views
 * carried a non-zero `accessibilityFrame`, 0 carried any trait. Every native tap is dropped for the
 * life of such a process (#135). A freshly created simulator was already known to behave this way; the
 * device measurement is what closed the question of whether real hardware differs. It does not.
 *
 * `hitTest` never consults accessibility. In the same cold process, on the same screen, the same tap
 * position handed to `UIWindow.hitTest` returned the real `UIButton` **carrying its
 * `accessibilityIdentifier`** — the exact string this pipeline reports as a target. That is the whole
 * mechanism here, and it is why this resolver exists (#189).
 *
 * **It rescues the UIKit half only.** Under a SwiftUI button the same cold `hitTest` returned a
 * `PlatformGroupContainer` with a nil identifier and a nil label: SwiftUI creates no per-element
 * backing view, and the `.accessibilityIdentifier(_:)` set on the button appears nowhere in the view
 * hierarchy. The accessibility tree is SwiftUI's only enumeration, and that tree is what needs a
 * client. Do not re-derive this: it was measured twice, on a fresh simulator and on the device, and a
 * later sweep over `List`, `Form`, `Picker` and a plain `Button` found no SwiftUI
 * `.accessibilityIdentifier` anywhere in the view hierarchy — cold *or* warm. This is also the line
 * PostHog draws (UIKit swizzling; SwiftUI element metadata documented as possibly incomplete).
 *
 * **So SwiftUI is not covered at all, and #191 stopped pretending otherwise.** The accessibility
 * resolver did name SwiftUI elements, but only in a process where an accessibility client happened to
 * be running, which made what it captured conditional on the user running VoiceOver or on the tap
 * coming from a test runner. That bias is invisible downstream — silence reads as "nobody tapped this"
 * — so it was removed rather than kept for the population it served. SwiftUI surfaces need explicit
 * instrumentation; `installAutographNativeTapCapture`'s kdoc says so to the developer.
 *
 * **Two side benefits, both of them real and neither of them the reason.** `hitTest` *is* the answer
 * to "which view receives this touch" — the accessibility tree is an approximation of it, which is why
 * [deepestAccessibilityHitPath] has an entire documented section of overlap and z-order failures
 * (#140). Along a `hitTest` chain those questions do not arise: UIKit already resolved them, including
 * `isUserInteractionEnabled`, `isHidden` and `alpha`, none of which the accessibility tree carries.
 * And it is unit-testable — `hitTest` behaves on a hand-built `UIView` tree exactly as it does on a
 * real one, unlike the accessibility walk, which needs a warm client and is why #135 hid behind green
 * CI for so long. `NativeHitTestResolutionTest` therefore covers this code directly.
 *
 * **Where it and the removed resolver disagreed, this one is right**, which is what made removing the
 * other a simplification rather than a loss: `hitTest` observed what UIKit actually did, while the
 * accessibility walk inferred it from traits and approximate frames (#189).
 */

/**
 * The identifier a native tap at this position would be reported under, or null when nothing would be
 * reported for it — whether because it resolved to nothing or because a veto dropped it.
 *
 * **The public face of [resolveNativeTapTargetByHitTest], and the reason it exists is that
 * `AutographUI` is a separate Swift package.** `.autographIgnore()` ships there, its contract is "a tap
 * in this region is not reported", and the only way to check that contract from Swift is to ask what
 * the pipeline would report. `AutographIgnoreTests` does exactly that. Nothing inside this module calls
 * it — [AutographNativeTapCapture.report] uses the resolver directly, because it needs the distinction
 * this function deliberately collapses (see [NativeHitTestResolution]).
 *
 * A caller outside the pipeline has no use for that distinction: both mean no event. So the tri-state
 * stays internal and this hands back the one thing an external caller can act on.
 *
 * The predecessor of this name walked the accessibility tree and took a scale instead of a second
 * position; #191 removed it. Same question, answered by `hitTest`, so it works in a cold process — see
 * this file's header. Both positions describe the same point in [root]'s space; see
 * [resolveNativeTapTargetByHitTest] for the units.
 *
 * **Threading.** Main thread only.
 */
@AutographInternalApi
public fun resolveNativeTapTarget(
    root: UIView,
    positionInWindowPoints: AxPoint,
    positionInWindowPx: AxPoint,
): String? = when (
    val resolution = resolveNativeTapTargetByHitTest(root, positionInWindowPoints, positionInWindowPx)
) {
    is NativeHitTestResolution.Target -> resolution.identifier
    NativeHitTestResolution.Dropped, NativeHitTestResolution.Unresolved -> null
}

/**
 * What [resolveNativeTapTargetByHitTest] concluded. The three cases exist because "no answer" and
 * "the answer is: report nothing" must not be confused.
 *
 * **The distinction outlived the reason it was introduced, so do not collapse it to a nullable
 * `String`.** It was originally what kept a veto here from being undone by the second resolver behind
 * this one; #191 removed that resolver, and both [Dropped] and [Unresolved] now produce no event. What
 * still separates them is that only one of them is a *symptom*. [Unresolved] means nothing here could
 * be named — the case a developer needs told about, and the only one allowed to spend the
 * one-per-process warning. [Dropped] means this library did exactly what it was asked: an ignored
 * region, Compose-owned content, a reserved identifier. In a hybrid app the very first tap is quite
 * likely to be the latter, on Compose content `autograph-compose` then reports perfectly well; letting
 * it consume the warning would blame a pipeline that did nothing wrong and leave the tap that needed
 * the warning silent.
 *
 * `aTapVetoedAsComposeOwnedDoesNotSpendTheWarning` and
 * `aTapThatResolvesToNothingDoesSpendTheWarning` are the pair that pin this; collapsing the two cases
 * fails the first and no other.
 */
internal sealed interface NativeHitTestResolution {

    /** The tap resolved to [identifier], which is what the event's `target` should be. */
    data class Target(val identifier: String) : NativeHitTestResolution

    /**
     * The tap resolved, and the answer is that nothing may be reported for it — an ignored region, an
     * excluded or Compose-owned subtree, a reserved identifier. **Terminal**: nothing may name this tap
     * afterwards, and nothing may treat it as a symptom. That was originally a rule about a second
     * resolver behind this one; #191 removed it, and what the distinction buys now is the diagnostic —
     * see this interface's kdoc above.
     */
    object Dropped : NativeHitTestResolution

    /**
     * `hitTest` found nothing that could be named. Not a drop and not an error — it is the ordinary
     * outcome on every SwiftUI screen, where the view hierarchy carries no per-element identity at all
     * (see this file's header), and on any UIKit control left without an `accessibilityIdentifier`.
     * The one outcome that may spend the caller's one-per-process warning.
     */
    object Unresolved : NativeHitTestResolution
}

/**
 * Resolves a native tap at [positionInWindowPoints] through `hitTest`, without reading the
 * accessibility tree at any point — so it works in a process no accessibility client has ever
 * touched. See this file's header for the measurement behind that and for what it does not cover.
 *
 * [root] is the view the tap was observed on — a `UIWindow` in production — and both positions must
 * describe the same point in **[root]'s own coordinate space**: [positionInWindowPoints] in UIKit
 * points, as `UITouch.locationInView(null)` reports it for a window, and [positionInWindowPx] the same
 * point multiplied by `UIScreen.mainScreen.scale`. Two arguments rather than one plus a scale because
 * each is consumed by a different mechanism and neither should be re-derived here: points are what
 * `hitTest` takes, pixels are the space [AutographIgnoredBounds] registers rectangles in (see [AxRect]
 * for why that space exists, and for the two on-device coordinate bugs that named it).
 *
 * The questions are asked in the order below, and each step's outcome is stated in
 * [NativeHitTestResolution]'s terms. The ownership vetoes come first, before anything is attributed,
 * so a tap this library must not report cannot be named by a later step:
 *
 * 1. **Position excluded** by a SwiftUI `.autographIgnore()` region → [Dropped][NativeHitTestResolution.Dropped].
 *    Checked first, before anything is walked, since it needs only the position already in hand.
 * 2. **`hitTest` declines the point**, or returns a view that is not under [root] →
 *    [Unresolved][NativeHitTestResolution.Unresolved].
 * 3. **The hit chain crosses a Compose host or a developer-excluded view** →
 *    [Dropped][NativeHitTestResolution.Dropped]. Both ownership questions are asked of the chain UIKit
 *    itself produced, which is the actual touch-delivery path rather than a geometric approximation of
 *    it. Note this needs only *one* chain where the removed accessibility resolver needed two walks:
 *    "where did the tap land" and "what should it be attributed to" are the same question here.
 * 4. **Nothing on the chain below [root] is interactive** ([isNativeInteractive]) →
 *    [Unresolved][NativeHitTestResolution.Unresolved]. The innermost interactive view wins, matching
 *    [nearestAccessibilityClickable]'s leaf-upward search, so a button inside a tappable row
 *    attributes to the button.
 * 5. **It carries no usable `accessibilityIdentifier`** ([accessibilityIdentifierOrNull], which also
 *    rejects a blank one) → [Unresolved][NativeHitTestResolution.Unresolved]. Deliberately *not* a
 *    drop: an untagged control is a miss, not something this library was asked to withhold, and only a
 *    miss may spend the caller's one-per-process warning — which is exactly the developer this case
 *    concerns. (Until #191 the reason was different and stronger: a second resolver ran behind this
 *    one, and a drop here would have cut off warm SwiftUI capture. That resolver is gone; the outcome
 *    is unchanged.) Identification never falls back to `accessibilityLabel`; see
 *    [accessibilityIdentifierOrNull] for that privacy guarantee.
 * 6. **The identifier is reserved** ([AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX]) →
 *    [Dropped][NativeHitTestResolution.Dropped], because that prefix names an Autograph marker
 *    unconditionally — here and in `autograph-compose`'s resolver alike.
 *
 * **There is deliberately no disabled-control veto here, and that is a measured decision rather than
 * an omission.** The removed accessibility resolver had one, and #189's sketch of this function
 * carried it over. Written, it never fired — because `hitTest` has already applied it, and *better*.
 * Measured on the simulator, for a bare `UIControl`, a `UISwitch` and a `UIButton` alike: a control
 * whose `isEnabled` is false is declined by hit-testing entirely, along with its whole subtree, and a
 * touch over one **passes straight through to the view drawn behind it**. So there is no state in
 * which this resolver holds a disabled control to veto.
 *
 * That measurement also corrects what [isAccessibilityDisabled]'s kdoc used to assert — that
 * `isEnabled` is not `isUserInteractionEnabled`, so a disabled control could still be what `hitTest`
 * returns. It cannot. The consequences are the opposite of the ones #128 measured for Compose, where a
 * disabled clickable swallows the tap and fires nothing:
 *
 * - A disabled control inside an interactive, identified container resolves to **the container**,
 *   which is correct here — the container is what UIKit delivers the touch to.
 * - An *enabled* control nested inside a disabled one resolves to nothing, because UIKit makes the
 *   child untappable too. Nothing runs, so nothing is reported.
 * - A disabled control alone over inert background is [Unresolved][NativeHitTestResolution.Unresolved].
 *   Nothing ran, so nothing is reported — and since it is a miss rather than a veto, it is allowed to
 *   spend the caller's warning, which is right: a developer whose disabled control produced no event
 *   is exactly who that line is for.
 *
 * All three outcomes are pinned by tests. Note what those tests can and cannot catch: they fix the
 * *result* for each shape, but none of them would fail if a disabled veto were re-added, because in
 * every one of them `hitTest` has already declined the disabled control and it is therefore absent from
 * the chain a veto could inspect. That is the same fact stated one paragraph up — there is no state in
 * which this resolver holds a disabled control — so the absence of the veto is guaranteed by the
 * mechanism rather than by the suite, and a reader must not expect a red test to stop them re-adding it.
 *
 * **[root] is never the answer.** It is dropped from the candidate set before step 4, which is not a
 * detail: in production [root] is the `UIWindow` this capture attached its own `UITapGestureRecognizer`
 * to, so it satisfies [isNativeInteractive] by Autograph's own doing. UIKit also pre-populates a
 * `UIWindow` with recognizers of its own. Without this, an app whose window carries an identifier
 * would report every tap anywhere on screen under that one name.
 *
 * **Threading.** Main thread only — `hitTest`, `superview` and `gestureRecognizers` are all
 * main-thread-only UIKit API.
 */
@OptIn(AutographInternalApi::class, ExperimentalForeignApi::class)
internal fun resolveNativeTapTargetByHitTest(
    root: UIView,
    positionInWindowPoints: AxPoint,
    positionInWindowPx: AxPoint,
): NativeHitTestResolution {
    if (AutographIgnoredBounds.contains(positionInWindowPx)) return NativeHitTestResolution.Dropped
    val hitView = root.hitTest(
        CGPointMake(positionInWindowPoints.x.toDouble(), positionInWindowPoints.y.toDouble()),
        withEvent = null,
    ) ?: return NativeHitTestResolution.Unresolved
    val chain = hitChain(from = hitView, to = root).ifEmpty { return NativeHitTestResolution.Unresolved }
    if (AutographComposeHosts.containsAny(chain) || AutographIgnoredViews.containsAny(chain)) {
        return NativeHitTestResolution.Dropped
    }
    // Leaf-first, so `firstOrNull` is the innermost interactive view. `dropLast` removes [root] — see
    // the kdoc; hitChain always ends at it whenever it returns anything at all. `takeWhile` stops the
    // search at the first scroll view rather than stepping over it — see [isNativeInteractive].
    val interactive = chain.dropLast(1)
        .takeWhile { it !is UIScrollView }
        .firstOrNull { it.isNativeInteractive() }
        ?: return NativeHitTestResolution.Unresolved
    // Asked of the element about to be *reported*, not of the view that received the touch: attributing
    // this tap to `interactive` is exactly the claim an excluded view inside it must be able to veto.
    if (anExcludedViewIsUnder(positionInWindowPoints, root, interactive)) return NativeHitTestResolution.Dropped
    val identifier = interactive.accessibilityIdentifierOrNull() ?: return NativeHitTestResolution.Unresolved
    if (identifier.startsWith(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX)) return NativeHitTestResolution.Dropped
    return NativeHitTestResolution.Target(identifier)
}

/**
 * Whether an [AutographIgnoredViews]-excluded view is under [point] in the sense the opt-out promises.
 *
 * **This exists because the two paths mean different things, and the opt-out is defined in terms of the
 * one this resolver does not have.** `registerAutographIgnoredView` promises that a tap whose hit path
 * crosses the excluded view "is not reported at all", and its hit path is the *visual* one the
 * accessibility walk builds. A `hitTest` chain is the *touch-delivery* path, and the two diverge exactly
 * at a view that declines touches — the default for the two view types an app is most likely to exclude:
 * `UILabel` and `UIImageView` both ship with `isUserInteractionEnabled = false`. Measured: a `UILabel`
 * showing an email address, registered as ignored, inside an identified card carrying a tap recognizer
 * — the accessibility resolver drops the tap, and without this check `hitTest` reported the card. The
 * opt-out silently stopped holding for the shape it is most often reached for.
 *
 * **Two walks, because one does not cover it and each fails in the direction the other does not.**
 *
 * 1. **The frontmost drawn branch from [root]** — `hitTest`'s own algorithm with a single gate removed:
 *    descend the frontmost subview containing the point, skipping hidden and effectively transparent
 *    views as `hitTest` does, but **not** skipping one that merely declines touches. This is what
 *    catches an excluded view drawn *over* its sibling, which is nowhere in [hitView]'s subtree. On its
 *    own it is not enough: it commits to the frontmost view at every step, so a clear-backgrounded
 *    full-size `scrim` sibling — visible by `alpha`, occluding nothing — swallows the walk and an
 *    excluded label behind it is never reached. Nothing UIKit exposes reliably answers "is this view
 *    actually opaque here", so that cannot be decided by looking harder.
 * 2. **[attributedTo]'s subtree** — the element about to be reported — judging each view by whether *it*
 *    contains the point and never gating descent on an ancestor containing it. This covers the scrim
 *    case, and also an excluded view drawn outside its own parent's bounds: a zero-sized or undersized
 *    container with an overhanging child, which autolayout produces routinely and which walk 1 (like
 *    `hitTest` itself) refuses to enter. Descent still stops at a hidden or transparent ancestor,
 *    because nothing under one is drawn.
 *
 *    Scoped to the *reported* element rather than to the view `hitTest` returned, which is not a detail:
 *    a touch-receiving scrim makes itself the hit view, so a sweep rooted there would search an empty
 *    subtree while the excluded label sits one level up inside the card being reported. Attribution is
 *    the claim being made, so attribution is the right scope for a veto of it.
 *
 * The union is deliberately asymmetric, and that asymmetry is the point: an excluded view *behind* what
 * the user tapped, in a different branch entirely, is reached by neither walk and correctly does not
 * veto. Over-dropping every tap that merely shares a screen region with an excluded subtree would be
 * its own defect.
 *
 * **Fail-closed on truncation.** Exceeding [MAX_HIT_CHAIN_DEPTH] vetoes rather than continuing. The
 * depth is unreachable for a real UIKit hierarchy; the direction is what matters, because the wrong one
 * would let an opt-out silently lapse.
 *
 * **A registered view that covers the screen therefore silences this resolver for every tap inside it.**
 * That is the opt-out doing what it was asked, not a bug — but an app excluding a full-screen
 * touch-transparent toast container rather than its transient children gets terminal [Dropped] on every
 * tap, and nothing runs afterwards to rescue it. Register the content, not the chrome.
 *
 * [point] is in [root]'s coordinate space (a `UIWindow` in production), and is converted per node
 * rather than accumulated, so a transformed or offset ancestor cannot skew it.
 *
 * **Threading.** Main thread only.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
private fun anExcludedViewIsUnder(point: AxPoint, root: UIView, attributedTo: UIView): Boolean {
    val cgPoint = CGPointMake(point.x.toDouble(), point.y.toDouble())

    fun UIView.isDrawn(): Boolean = !hidden && alpha >= MINIMUM_VISIBLE_ALPHA
    fun UIView.containsPoint(): Boolean =
        pointInside(convertPoint(cgPoint, fromView = root), withEvent = null)

    // Walk 1: the frontmost drawn branch.
    var view: UIView = root
    var depth = 0
    while (true) {
        if (AutographIgnoredViews.contains(view)) return true
        if (depth++ >= MAX_HIT_CHAIN_DEPTH) return true
        view = view.subviews
            .filterIsInstance<UIView>()
            .asReversed()
            .firstOrNull { it.isDrawn() && it.containsPoint() }
            ?: break
    }

    // Walk 2: everything drawn under the point inside the reported element, ungated by containment.
    val stack = ArrayDeque<Pair<UIView, Int>>()
    stack.addLast(attributedTo to 0)
    while (stack.isNotEmpty()) {
        val (node, nodeDepth) = stack.removeLast()
        if (nodeDepth >= MAX_HIT_CHAIN_DEPTH) return true
        if (node.containsPoint() && AutographIgnoredViews.contains(node)) return true
        node.subviews
            .filterIsInstance<UIView>()
            .forEach { if (it.isDrawn()) stack.addLast(it to nodeDepth + 1) }
    }
    return false
}

/**
 * The alpha at or above which UIKit's own hit-testing still considers a view — it ignores those "with an
 * alpha level less than 0.01". Mirrored by [anExcludedViewIsUnder] so its walks diverge from `hitTest`
 * only where intended.
 *
 * **`>=` rather than `>` is the doc-faithful form, and the difference is unobservable — measured, not
 * assumed.** A review flagged the boundary as an off-by-one that would skip a view `hitTest` still
 * reaches. It cannot: `UIView.alpha` is stored as a 32-bit float, so a view set to `0.01` reads back as
 * `0.009999999776482582`, which is below this either way — and the same probe showed `hitTest` declines
 * that view too (`hitTest` returned the view *behind* it). So no alpha exists for which this gate and
 * UIKit's disagree, and the operator is a statement of intent rather than a live distinction.
 */
private const val MINIMUM_VISIBLE_ALPHA = 0.01

/**
 * The view chain UIKit will deliver this touch along: [from] first, then each `superview` up to and
 * including [to]. Empty when [to] is not on that chain.
 *
 * Emptiness is a real case rather than paranoia — `hitTest` is overridable, and an app is free to
 * return a view from another part of the hierarchy. The caller reads empty as
 * [Unresolved][NativeHitTestResolution.Unresolved] rather than as a drop: a chain that does not reach
 * [to] cannot be checked against the ownership registries, so this resolver has no basis for either
 * naming the tap or claiming it withheld one. Nothing runs afterwards — the tap is simply not reported,
 * and the developer is told once (see [warnOnceIfANativeTapResolvedToNothing]).
 *
 * Compared with `==`, never `===`: [from] arrives from `hitTest` across the interop boundary and [to]
 * is the caller's own reference, so Kotlin/Native hands them out as distinct wrappers for what may be
 * the same view. `==` routes to `isEqual:`, pointer equality on the underlying object. This is the
 * same trap [deepestAccessibilityHitPath]'s cycle guard documents, and it fails silently: `===` here
 * would simply never terminate the loop at [to], returning empty for every tap and leaving this
 * resolver permanently inert behind a green test suite.
 *
 * [MAX_HIT_CHAIN_DEPTH] bounds the walk. A UIKit view hierarchy is a tree and cannot cycle, so this
 * only ever fires on a hierarchy far deeper than any real one; it is here because the loop's real
 * terminator is a view supplied by the host app.
 */
private fun hitChain(from: UIView, to: UIView): List<UIView> {
    val chain = mutableListOf<UIView>()
    var view: UIView? = from
    while (view != null && chain.size < MAX_HIT_CHAIN_DEPTH) {
        chain += view
        if (view == to) return chain
        view = view.superview
    }
    return emptyList()
}

/**
 * Depth ceiling for [hitChain], sized like [deepestAccessibilityHitPath]'s: far above any real UIKit
 * hierarchy (tens of levels, not hundreds), so it never truncates a genuine chain.
 */
private const val MAX_HIT_CHAIN_DEPTH = 256

/**
 * Whether tapping this view runs something — Autograph's clickability predicate for the `hitTest`
 * route, and the counterpart of [isAccessibilityButton] on the accessibility one.
 *
 * Two forms, because UIKit has two: a `UIControl` (its target-action machinery *is* interactivity),
 * and any view carrying an **enabled** `UITapGestureRecognizer` — which is how a plain `UIView`, a
 * `UIImageView` or a `UILabel` becomes tappable.
 *
 * **A disabled recognizer does not count**: a view whose only recognizer is disabled runs nothing and
 * does not consume the touch, so the search carries on to whatever ancestor does. Nothing symmetrical
 * is needed for a disabled `UIControl` — measured, `hitTest` never hands one back at all (see
 * [resolveNativeTapTargetByHitTest]'s note on the absent disabled veto).
 *
 * **Nor does a recognizer that this tap could not have satisfied.** The observer that feeds this
 * pipeline is a *single*-tap, single-touch `UITapGestureRecognizer` on the window, so a recognizer
 * requiring two taps or two fingers demonstrably did not fire for the tap being resolved: reporting its
 * view would invent an event for an action that never ran. `numberOfTapsRequired` and
 * `numberOfTouchesRequired` are checked for that reason and no other — they are the two requirements
 * readable from the umbrella header that this pipeline can compare against its own recognizer.
 *
 * What is deliberately *not* modelled is the rest of recognition: a `delegate` that would have refused
 * the touch, a `require(toFail:)` relationship, an `isEnabled` view hierarchy above. Those need the
 * live gesture-recognition state rather than the recognizer's static configuration, and guessing at
 * them would trade a rare phantom event for the misattribution of naming some ancestor instead. The
 * identifier requirement bounds the residue: an untagged view is never reported whatever this answers.
 *
 * **Deliberately not narrowed to a control that has a registered action.** `UIControl.allControlEvents`
 * would exclude a `UITextField` tapped only to focus it, but it would also exclude a `UIButton` driven
 * by `showsMenuAsPrimaryAction`, and neither behaviour has been measured. The identifier requirement
 * in [resolveNativeTapTargetByHitTest] bounds the over-capture in the meantime: an untagged control is
 * never reported whatever this answers.
 *
 * **A `UIScrollView` is never interactive here, and that exclusion is load-bearing.** A scroll view —
 * including `UITableView` and `UICollectionView` — carries tap recognizers of UIKit's own for
 * scrolling and selection plumbing, which say nothing about the view being a tappable *element*. Worse,
 * SwiftUI applies `.accessibilityIdentifier` to whatever backing view a construct happens to have, and
 * a `List`'s backing view is a real `UICollectionView` while its rows have no backing view at all. So
 * without this the identified container **shadows its own content**: measured against the sample app's
 * XCUITest suite, a tap on `native_row_2` inside an identified `List` resolved to `native_list`, where
 * the accessibility resolver had correctly named the row. That is a regression, not a gap — a container
 * claiming its children's taps is a misattribution, the failure this codebase consistently ranks worse
 * than a drop.
 *
 * A scroll view is therefore a **barrier**, not a skip: [resolveNativeTapTargetByHitTest] stops the
 * upward search at the first one rather than stepping over it. Answering "not interactive" for the
 * scroll view alone was measured to be only half the fix, because it relocates the problem rather than
 * removing it — the search simply carried on to the *next* interactive ancestor and let that one shadow
 * the content instead. Measured against a hand-built tree: `screen(id="login_screen", tap recognizer) →
 * UIScrollView(id="feed_list") → row`, a tap on the row resolved to `login_screen`. A screen container
 * carrying an `accessibilityIdentifier` for UI testing and a tap-to-dismiss-keyboard recognizer is an
 * ordinary UIKit shape, so this was not a corner. Stopping at the barrier gives
 * [Unresolved][NativeHitTestResolution.Unresolved].
 *
 * **Since #191 that is where the tap ends** — no second resolver runs behind this one, so a row the
 * barrier stops at is not reported at all. When this barrier was written the accessibility route still
 * caught the SwiftUI rows it stopped, and this note said so; that rescue is gone, and the barrier's
 * cost is now paid in full. It is still the right trade, for the reason below rather than for that one:
 * nothing above a scroll view is a plausible owner of a tap the scroll view's own content received, so
 * the barrier costs no target *this* resolver should have named — UIKit delivered the touch inside the
 * scroll view, and an ancestor's recognizer that did also fire is not what the user tapped. What it
 * costs is a row this resolver could never have named anyway; a drop, chosen over a misattribution.
 *
 * **A `UITextView` is a `UIScrollView`**, and so is excluded by the same branch — an editable one is
 * tapped to focus rather than to activate, so this is the wanted answer, but it means text views are
 * never reported, like a cell. Named because the type relationship is easy to miss.
 *
 * **Known gap — UIKit cell selection.** A `UITableViewCell` or `UICollectionViewCell` is neither a
 * `UIControl` nor recognizer-bearing; selection is the delegate's, driven by recognizers on the scroll
 * view this now excludes. So a tap on a plain cell reaches nothing interactive and **is not reported at
 * all** — warm or cold, since #191 removed the accessibility resolver that used to resolve it on a warm
 * tree. The README's gap list says the same; keep the two in step. Adding cells to the predicate is a
 * guess until a real `UITableView` hierarchy is run against it.
 *
 * **Threading.** Main thread only.
 */
private fun UIView.isNativeInteractive(): Boolean = when {
    // Unreachable through the sole call site, which stops at the first scroll view before ever asking
    // this — so no test holds this branch, and deleting it turns nothing red. Kept anyway, and the fact
    // recorded rather than dressed up: the predicate has to answer "is this view itself a tappable
    // element" correctly in its own right, or a second call site added later inherits the bug the
    // barrier was introduced to fix.
    this is UIScrollView -> false
    this is UIControl -> true
    else -> gestureRecognizers
        ?.filterIsInstance<UIGestureRecognizer>()
        ?.any { it is UITapGestureRecognizer && it.enabled && it.recognizesASingleTap() }
        ?: false
}

/** Whether this recognizer's requirements match the single-finger single tap this pipeline observes. */
private fun UITapGestureRecognizer.recognizesASingleTap(): Boolean =
    numberOfTapsRequired == 1uL && numberOfTouchesRequired == 1uL
