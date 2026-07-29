package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import platform.UIKit.UIView

/**
 * Resolves a native (UIKit/SwiftUI) tap at [positionInWindowPx] to the identifier Autograph reports
 * as the event's `target`, or null when the tap should not be reported at all.
 *
 * This is the non-Compose half of iOS autocapture, and it deliberately runs the *same*
 * [deepestAccessibilityHitPath] walk `autograph-compose` uses. UIKit and SwiftUI populate the
 * accessibility tree natively, so one mechanism covers both — and covers Compose too, which is
 * precisely why [AutographComposeHosts] has to carve Compose back out.
 *
 * A tap resolves only when every step holds; each `null` below is a deliberate drop:
 *
 * 1. **The walk finds nothing at that position.** The tap landed outside [root]'s tree.
 * 2. **The path crosses a Compose host.** That content belongs to the Compose pipeline; reporting
 *    it here would double-count it, or worse, capture something `Modifier.autographIgnore()`
 *    excluded. See [AutographComposeHosts].
 *
 *    This is asked of the walk run *without* [deepestAccessibilityHitPath]'s clickable preference,
 *    which is deliberate. Ownership is a question about where the tap visually landed, and the
 *    preference exists to answer a different one — which element to attribute it to. Left conflated,
 *    a Compose host whose content under the tap carries no button trait is simply walked around: the
 *    preferred path never touches the host, the check below sees nothing, and this pipeline claims a
 *    tap on Compose-owned content. Both paths are checked because a host can also sit *below* the
 *    branch the preference chose.
 * 3. **Nothing on the path is clickable.** Autograph's clickability predicate is
 *    `UIAccessibilityTraitButton` ([isAccessibilityButton]); a tap on inert background is not an
 *    interaction worth reporting. The search runs leaf-upward so the *innermost* clickable wins —
 *    a button inside a tappable row attributes to the button, matching the Compose path.
 * 4. **The clickable element is disabled** — it carries `UIAccessibilityTraitNotEnabled`
 *    ([isAccessibilityDisabled]). It was tapped and it did take the touch, but a control in that
 *    state runs no action, so reporting a click would emit an event for something that never
 *    happened. It is vetoed here rather than skipped during the walk, because skipping it would name
 *    whatever sits beneath or around it, which never received the tap either. The trait is the
 *    element's own claim, so this also drops a tap that *did* run something behind a hand-published
 *    one — see [isAccessibilityDisabled] for that trade and for the ancestor-recognizer case.
 * 5. **The clickable element has no `accessibilityIdentifier`** — or carries a blank one, which
 *    [accessibilityIdentifierOrNull] treats as absent. There is no stable name to report
 *    it under, and this is where the privacy guarantee lives: identification never falls back to
 *    `accessibilityLabel`, which is user-facing display text. See [accessibilityIdentifierOrNull].
 *    In SwiftUI the identifier comes from `.accessibilityIdentifier(_:)`, the direct analogue of
 *    Compose's `testTag`; in UIKit from `view.accessibilityIdentifier`. An element without one is
 *    dropped exactly as an untagged Compose element is.
 *
 * **Known gap — `.onTapGesture` on a `Text`.** Measured on-device: it surfaces as `StaticText`
 * carrying no button trait, so step 3 drops it even though it does carry an identifier. Widening
 * the predicate to "anything with an identifier" would fix it and simultaneously start capturing
 * taps on ordinary labels, so the gap stands.
 *
 * **Known limitation — nothing is reported until an accessibility client has run in the process.**
 * This is the big one, and it is not a tuning problem: UIKit and SwiftUI build the accessibility element
 * tree *on demand*, when an accessibility client asks for it. Until then this walk finds only plain
 * `UIView`s reached through `subviews` — measured on a freshly created simulator: 88 nodes, every one
 * reporting `accessibilityFrame = CGRectZero` and no traits at all, with no `SwiftUI.AccessibilityNode`
 * and therefore no `UIAccessibilityTraitButton` anywhere in the tree. Step 1 and step 3 then both fail
 * and **every native tap is dropped, silently, for the life of the process**. The moment anything
 * connects — VoiceOver, Voice Control, Switch Control, the Accessibility Inspector, an XCUITest runner —
 * the tree appears with real frames and traits, and any target this pipeline otherwise supports
 * resolves — the gaps documented above still drop, warm or cold.
 *
 * There is no fix available here. Nothing public asks UIKit to populate that tree, and the private entry
 * points that would are not something a published library can ship. This is a property of the mechanism,
 * not of this code: any tap capture built on the accessibility tree inherits it. It is why
 * `autograph-compose`'s pipeline is unaffected — Compose Multiplatform bridges its own semantics into
 * accessibility elements unconditionally, without waiting to be asked (see #135, and
 * [deepestAccessibilityHitPath]'s note on the starting node, which fixed the Compose half only).
 *
 * The practical consequence for a hybrid app: taps on native surfaces should not be assumed present in
 * the data. Treat this pipeline as best-effort until #135 finds a mechanism that does not depend on an
 * accessibility client, and instrument anything you must not lose explicitly.
 *
 * [scale] must be `UIScreen.mainScreen.scale` — see [accessibilityBoundsInWindowPx], whose
 * precondition this inherits wholesale.
 *
 * **Threading.** Main thread only.
 */
@AutographInternalApi
public fun resolveNativeTapTarget(
    root: UIView,
    positionInWindowPx: AxPoint,
    scale: Float,
): String? {
    // A SwiftUI `.autographIgnore()` excludes by window region, not by view (see AutographIgnoredBounds):
    // drop the tap immediately if it lands in one. Checked first, before the walk, since it needs only
    // the position this already has.
    if (AutographIgnoredBounds.contains(positionInWindowPx)) return null
    // Ownership is asked of the *topmost* path and attribution of the clickable-preferred one, because
    // the two are different questions — see step 2 above. The developer opt-out
    // (registerAutographIgnoredView) is checked on the same two paths and for the same reason as the
    // Compose-host carve-out: an excluded subtree can sit under either path, and both "is this
    // Compose-owned?" and "did the developer exclude this?" are ownership questions asked before
    // attribution. Either veto drops the tap.
    val topmostPath = deepestAccessibilityHitPath(root, root, positionInWindowPx, scale, preferClickableBranches = false)
    if (topmostPath != null && topmostPath.crossesAnExcludedSubtree()) return null
    val path = deepestAccessibilityHitPath(root, root, positionInWindowPx, scale) ?: return null
    if (path.crossesAnExcludedSubtree()) return null
    val nearestClickable = path.nearestAccessibilityClickable() ?: return null
    // Asked of the resolved element only, never of its ancestry — see [isAccessibilityDisabled] for
    // why this is a veto here rather than a narrowing of the clickability predicate, and why a
    // disabled ancestor must not suppress an enabled descendant.
    if (nearestClickable.isAccessibilityDisabled()) return null
    return nearestClickable.accessibilityIdentifierOrNull()
}

/** Whether this hit path crosses a Compose host or a developer-excluded ([AutographIgnoredViews]) view. */
@OptIn(AutographInternalApi::class)
private fun List<Any>.crossesAnExcludedSubtree(): Boolean =
    AutographComposeHosts.containsAny(this) || AutographIgnoredViews.containsAny(this)
