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
 * The newer of iOS's two native tap resolvers, and the one asked first.
 *
 * [resolveNativeTapTarget] identifies a tapped element by walking the **accessibility tree**, which
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
 * client — so SwiftUI stays [resolveNativeTapTarget]'s case, warm only. Do not re-derive this: it was
 * measured twice, on a fresh simulator and on the device. This is also the line PostHog draws (UIKit
 * swizzling; SwiftUI element metadata documented as possibly incomplete).
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
 * **Why first rather than second.** For a warm UIKit app the two resolvers can name different targets,
 * so ordering is a behaviour change. It is taken deliberately: where they disagree, `hitTest` is the
 * one that observed what UIKit actually did. Preferring the less correct path for continuity was
 * rejected, and the library is pre-1.0 (#189).
 */

/**
 * What [resolveNativeTapTargetByHitTest] concluded. The three cases exist because "no answer" and
 * "the answer is: report nothing" must not be confused — the caller **falls through to
 * [resolveNativeTapTarget] on [Unresolved] and must not on [Dropped]**.
 *
 * Collapsing them to a nullable `String` was the first shape tried and it is wrong in a way that is
 * easy to miss: a veto returning null would send the tap on to a resolver that does not necessarily
 * apply the same veto to the same tree. The two resolvers ask the same ownership questions, but of
 * *different chains* — one built from real frames, one from accessibility frames — and those diverge
 * exactly where it matters. A registered Compose host has a zero `accessibilityFrame` while cold, so
 * the accessibility walk prunes that branch entirely, never sees the host, and is free to resolve a
 * neighbouring element instead. `hitTest` follows the real frames straight into it. Fall through on
 * that drop and a tap on Compose-owned content is reported by the native pipeline under some other
 * element's name — the privacy boundary [AutographComposeHosts] exists to hold, defeated by the very
 * mechanism added to strengthen it.
 *
 * That is not hypothetical: `aTapVetoedByTheHitTestResolverIsNotRescuedByTheAccessibilityOne` builds
 * exactly that tree, asserts the accessibility resolver *does* answer for it, and fails if this
 * distinction is collapsed.
 */
internal sealed interface NativeHitTestResolution {

    /** The tap resolved to [identifier], which is what the event's `target` should be. */
    data class Target(val identifier: String) : NativeHitTestResolution

    /**
     * The tap resolved, and the answer is that nothing may be reported for it — an ignored region, an
     * excluded or Compose-owned subtree, a reserved identifier. **Terminal**: the caller must not
     * consult the accessibility resolver, which could otherwise name something for the very tap this
     * vetoed.
     */
    object Dropped : NativeHitTestResolution

    /**
     * `hitTest` had nothing to say about this tap, so the caller should try [resolveNativeTapTarget].
     * Not a drop and not an error — this is the ordinary outcome on every SwiftUI screen, where the
     * view hierarchy carries no per-element identity at all (see this file's header).
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
 * The questions are asked in the same order and with the same shape as [resolveNativeTapTarget]'s, so
 * the two resolvers agree wherever they can — a UIKit control must not resolve one way cold and
 * another warm. Each step's outcome is stated in [NativeHitTestResolution]'s terms:
 *
 * 1. **Position excluded** by a SwiftUI `.autographIgnore()` region → [Dropped][NativeHitTestResolution.Dropped].
 *    Checked first, before anything is walked, exactly as [resolveNativeTapTarget] does.
 * 2. **`hitTest` declines the point**, or returns a view that is not under [root] →
 *    [Unresolved][NativeHitTestResolution.Unresolved].
 * 3. **The hit chain crosses a Compose host or a developer-excluded view** →
 *    [Dropped][NativeHitTestResolution.Dropped]. The same two ownership questions
 *    [resolveNativeTapTarget] asks of its hit path, asked of the chain UIKit itself produced — which
 *    is strictly the better evidence, since it is the actual touch delivery path rather than a
 *    geometric approximation of it. Note this resolver needs only *one* chain where the accessibility
 *    one needs two walks: the "where did the tap land" and "what should it be attributed to" questions
 *    it has to keep apart are the same question here.
 * 4. **Nothing on the chain below [root] is interactive** ([isNativeInteractive]) →
 *    [Unresolved][NativeHitTestResolution.Unresolved]. The innermost interactive view wins, matching
 *    [nearestAccessibilityClickable]'s leaf-upward search, so a button inside a tappable row
 *    attributes to the button.
 * 5. **It carries no usable `accessibilityIdentifier`** ([accessibilityIdentifierOrNull], which also
 *    rejects a blank one) → [Unresolved][NativeHitTestResolution.Unresolved]. Deliberately *not* a
 *    drop, and this is the single most load-bearing choice in the function: a warm SwiftUI screen hits
 *    exactly this case — its hosting views carry gesture recognizers and no identifier — so dropping
 *    here would silently disable the SwiftUI half of native capture, which is the half that has no
 *    other route. Identification never falls back to `accessibilityLabel`; see
 *    [accessibilityIdentifierOrNull] for that privacy guarantee.
 * 6. **The identifier is reserved** ([AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX]) →
 *    [Dropped][NativeHitTestResolution.Dropped], because that prefix names an Autograph marker
 *    unconditionally, in both resolvers.
 *
 * **There is deliberately no disabled-control veto here, and that is a measured decision rather than
 * an omission.** [resolveNativeTapTarget] has one, and #189's sketch of this function carried it over.
 * Written, it never fired — because `hitTest` has already applied it, and applied it *better*.
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
 * - A disabled control alone over inert background is [Unresolved][NativeHitTestResolution.Unresolved]
 *   and falls through, where [resolveNativeTapTarget]'s `UIAccessibilityTraitNotEnabled` veto still
 *   drops it warm. No phantom event either way.
 *
 * All three are pinned by tests; re-adding a veto here would break them, which is the point.
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
    // the kdoc; hitChain always ends at it whenever it returns anything at all.
    val interactive = chain.dropLast(1).firstOrNull { it.isNativeInteractive() }
        ?: return NativeHitTestResolution.Unresolved
    val identifier = interactive.accessibilityIdentifierOrNull() ?: return NativeHitTestResolution.Unresolved
    if (identifier.startsWith(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX)) return NativeHitTestResolution.Dropped
    return NativeHitTestResolution.Target(identifier)
}

/**
 * The view chain UIKit will deliver this touch along: [from] first, then each `superview` up to and
 * including [to]. Empty when [to] is not on that chain.
 *
 * Emptiness is a real case rather than paranoia — `hitTest` is overridable, and an app is free to
 * return a view from another part of the hierarchy. The caller reads empty as
 * [Unresolved][NativeHitTestResolution.Unresolved] rather than as a drop, since a chain that does not
 * reach [to] cannot be checked against the ownership registries either; sending it on to the
 * accessibility resolver, which does its own containment walk from [to], is the honest outcome.
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
 * Excluded rather than special-cased so the walk continues past it: the tap then reaches no interactive
 * view at all, the resolver answers [Unresolved][NativeHitTestResolution.Unresolved], and the
 * accessibility route — which *can* see SwiftUI rows — resolves it exactly as before.
 *
 * **Known gap — UIKit cell selection.** A `UITableViewCell` or `UICollectionViewCell` is neither a
 * `UIControl` nor recognizer-bearing; selection is the delegate's, driven by recognizers on the scroll
 * view this now excludes. So a tap on a plain cell reaches nothing interactive and falls through to the
 * accessibility resolver, where it resolves warm and drops cold — the same position UIKit cells were in
 * before #189, and no worse. Adding cells to the predicate is a guess until a real `UITableView`
 * hierarchy is run against it.
 *
 * **Threading.** Main thread only.
 */
private fun UIView.isNativeInteractive(): Boolean = when {
    this is UIScrollView -> false
    this is UIControl -> true
    else -> gestureRecognizers
        ?.filterIsInstance<UIGestureRecognizer>()
        ?.any { it is UITapGestureRecognizer && it.enabled }
        ?: false
}
