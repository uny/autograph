package dev.ynagai.autograph.uikit

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
 * 3. **Nothing on the path is clickable.** Autograph's clickability predicate is
 *    `UIAccessibilityTraitButton` ([isAccessibilityButton]); a tap on inert background is not an
 *    interaction worth reporting. The search runs leaf-upward so the *innermost* clickable wins —
 *    a button inside a tappable row attributes to the button, matching the Compose path.
 * 4. **The clickable element has no `accessibilityIdentifier`.** There is no stable name to report
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
    val path = deepestAccessibilityHitPath(root, root, positionInWindowPx, scale) ?: return null
    if (AutographComposeHosts.containsAny(path)) return null
    val nearestClickable = path.asReversed().firstOrNull { it.isAccessibilityButton() } ?: return null
    return nearestClickable.accessibilityIdentifierOrNull()
}
