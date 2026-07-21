@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi

/**
 * Window-space regions a developer has excluded from native tap autocapture by POSITION rather than by
 * view. [resolveNativeTapTarget] already holds the tap's window-pixel position, so it vetoes a tap that
 * falls in any registered region — no dependency on the view hierarchy, hit-testing, or the accessibility
 * walk.
 *
 * This is the SwiftUI counterpart of [AutographIgnoredViews] (which excludes by UIView, right for UIKit
 * where the excluded thing IS a view). A SwiftUI view is not UIView-backed, and a background marker view
 * would either intercept touches (breaking the content) or perturb tap resolution without actually being
 * the registry's doing; a `GeometryReader` reports the content's window frame with no extra hittable
 * view, so exclusion here is purely positional. Rects are in **window pixels** (points × screen scale),
 * the same space as [resolveNativeTapTarget]'s `positionInWindowPx`.
 *
 * **Main thread only**, like the tap pipeline it feeds.
 */
internal object AutographIgnoredBounds {

    private val regions = mutableListOf<AutographIgnoredBoundsRegistration>()

    fun add(registration: AutographIgnoredBoundsRegistration) {
        regions.add(registration)
    }

    fun remove(registration: AutographIgnoredBoundsRegistration) {
        regions.remove(registration)
    }

    /** Whether [point] (window pixels) falls in any currently-registered excluded region. */
    fun contains(point: AxPoint): Boolean = regions.any { it.rect?.contains(point) == true }
}

/**
 * Excludes a rectangular region of the window from native tap autocapture — the mechanism behind
 * SwiftUI's `.autographIgnore()`. Keep it and [update] its rect as the content moves (scroll, layout),
 * then [unregister] when the content leaves. A **privacy** control; only ambient autocapture is
 * suppressed. **Main thread only.**
 */
public class AutographIgnoredBoundsRegistration internal constructor() {

    // Window-pixel bounds, or null while the content is off-window (contributes nothing then).
    internal var rect: AxRect? = null
        private set

    /** Updates the excluded region to [left]/[top]/[right]/[bottom] in window pixels. */
    public fun update(left: Float, top: Float, right: Float, bottom: Float) {
        rect = AxRect(left, top, right, bottom)
    }

    /**
     * Excludes nothing for now, while STAYING registered — for content that is momentarily off-layout
     * (collapsed to zero size, hidden) but still on-window, so a later [update] revives it. Distinct from
     * [unregister], which removes the entry entirely (used only when the content leaves the window). Not
     * the same as [update]-ing an empty rect: a zero-size `AxRect` still sits at a point and would deafen
     * a tap there, whereas a null rect matches nothing. Idempotent.
     */
    public fun clear() {
        rect = null
    }

    /** Stops excluding this region. Idempotent. */
    public fun unregister() {
        rect = null
        AutographIgnoredBounds.remove(this)
    }
}

/** Starts a window-region exclusion; call [AutographIgnoredBoundsRegistration.update] to set its rect. */
public fun registerAutographIgnoredBounds(): AutographIgnoredBoundsRegistration {
    val registration = AutographIgnoredBoundsRegistration()
    AutographIgnoredBounds.add(registration)
    return registration
}
