package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi

/**
 * A point in **window-space pixels** — see [AxRect] for why that space, and why the unit is spelled
 * out in every name here rather than left to a comment.
 */
@AutographInternalApi
public data class AxPoint(public val x: Float, public val y: Float)

/**
 * A rectangle in **window-space pixels**: relative to the hosting `UIWindow`'s origin (not the
 * screen's, and not any intermediate view's), and scaled by the screen's point-to-pixel ratio.
 *
 * That space is unusual for UIKit code — UIKit deals in points, and `accessibilityFrame` is
 * documented in screen points — so it is named explicitly on every declaration that carries it. It
 * is the space Compose Multiplatform's `Offset`/`LayoutCoordinates` use, which is what this walk was
 * originally written against, and both of the two coordinate bugs found on-device in this code were
 * a mismatch against it:
 *
 * - **points vs pixels**: a 3x simulator reported a 48pt leaf frame against a 72px tap position, so
 *   the containment check silently never matched. Fixed by scaling to pixels.
 * - **view-local vs window-relative**: converting into the *hosting view's* local space subtracted
 *   that view's own offset within its window, which the tap position had never been adjusted for.
 *   Invisible while the view filled its window; every tap misattributed by roughly the safe-area
 *   inset the moment it didn't (a `ComposeUIViewController` inside a safe-area-respecting SwiftUI
 *   container). Fixed by converting into the *window's* space.
 *
 * A caller holding a point in UIKit's own terms has to convert it itself before calling in: take the
 * touch's location in the *window* (e.g. `UITouch.locationInView(window)`, which is already
 * window-relative) and multiply both components by `UIScreen.mainScreen.scale`. Nothing here does that
 * for you — [accessibilityBoundsInWindowPx] converts an element's own `accessibilityFrame`, not a
 * caller-supplied point.
 */
@AutographInternalApi
public data class AxRect(
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
) {
    /** Whether [point] falls within these bounds — left/top inclusive, right/bottom exclusive. */
    public fun contains(point: AxPoint): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom
}
