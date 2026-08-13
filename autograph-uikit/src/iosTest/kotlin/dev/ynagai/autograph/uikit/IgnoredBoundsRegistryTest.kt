package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Drives the SwiftUI opt-out's window-region veto ([AutographIgnoredBounds] /
 * [registerAutographIgnoredBounds]) through the real [resolveNativeTapTargetByHitTest]. This is the
 * mechanism SwiftUI `.autographIgnore()` uses — a positional veto — verified here without SwiftUI, so
 * the exclusion is provably the registry's doing and not a side effect of a background view perturbing
 * tap resolution.
 *
 * **Scope split with [NativeHitTestResolutionTest].** That suite pins the *resolver's* handling of a
 * registered region (that it is checked before anything is hit-tested at all). This one pins the
 * *registry's lifecycle* — that unregistering restores resolution, and that moving a region with
 * `update` moves the veto with it. The latter is what makes the opt-out safe under scrolling: a stale
 * rectangle would either keep deafening whatever slid into the old place or leak the content that
 * moved, and `AutographIgnore.swift` re-reports every frame precisely so it cannot.
 *
 * Fixtures set **real frames** and use a real `UIControl`, because `hitTest` is what consults them.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class IgnoredBoundsRegistryTest {

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    private fun view(x: Double, y: Double, w: Double, h: Double): UIView =
        UIView(frame = CGRectMake(x, y, w, h))

    private fun button(id: String, x: Double, y: Double, w: Double, h: Double): UIView =
        UIButton(frame = CGRectMake(x, y, w, h))
            .apply { (this as NSObject).setValue(id, forKey = "accessibilityIdentifier") }

    /** Resolves a tap given in window points, deriving the pixel position as the production caller does. */
    private fun resolve(root: UIView, xPoints: Double, yPoints: Double): NativeHitTestResolution =
        resolveNativeTapTargetByHitTest(
            root = root,
            positionInWindowPoints = AxPoint(xPoints.toFloat(), yPoints.toFloat()),
            positionInWindowPx = AxPoint(xPoints.toFloat() * scale, yPoints.toFloat() * scale),
        )

    private val registrations = mutableListOf<AutographIgnoredBoundsRegistration>()

    /** The registry is process-global, so a leaked entry would leak into the next test. */
    @AfterTest
    fun clearRegistry() {
        registrations.forEach { it.unregister() }
        registrations.clear()
    }

    private fun ignoreBoundsPx(left: Float, top: Float, right: Float, bottom: Float): AutographIgnoredBoundsRegistration =
        registerAutographIgnoredBounds().also {
            it.update(left, top, right, bottom)
            registrations += it
        }

    @Test
    fun dropsATapWhosePositionFallsInsideAnExcludedRegion() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button("secret_button", 10.0, 10.0, 20.0, 20.0))

        assertEquals(
            NativeHitTestResolution.Target("secret_button"),
            resolve(root, 15.0, 15.0),
            "precondition: without the region the tap resolves",
        )

        // A region (px) covering the button's position.
        ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)

        assertEquals(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "a tap whose position is inside a registered region must not be reported",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheExcludedRegion() {
        val root = view(0.0, 0.0, 200.0, 100.0)
        root.addSubview(button("public_button", 120.0, 10.0, 20.0, 20.0))

        // Region only covers the left half; the button is on the right.
        ignoreBoundsPx(0f, 0f, 100f * scale, 100f * scale)

        assertEquals(
            NativeHitTestResolution.Target("public_button"),
            resolve(root, 125.0, 15.0),
            "a region must not deafen taps outside it",
        )
    }

    @Test
    fun unregisteringARegionRestoresResolution() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val region = ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)
        assertEquals(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))

        region.unregister()
        assertEquals(NativeHitTestResolution.Target("b"), resolve(root, 15.0, 15.0))
    }

    @Test
    fun aRegionMovedOffTheTapByUpdateNoLongerExcludesIt() {
        val root = view(0.0, 0.0, 200.0, 200.0)
        root.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val region = ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)
        assertEquals(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))

        // The content scrolled away: update the rect off the tap. The veto must follow the new rect.
        region.update(100f * scale, 100f * scale, 140f * scale, 140f * scale)
        assertEquals(
            NativeHitTestResolution.Target("b"),
            resolve(root, 15.0, 15.0),
            "after update() moves the region off the tap, the tap resolves again",
        )
    }

    /**
     * [AutographIgnoredBoundsRegistration.clear] must excise the region while staying registered —
     * distinct from [AutographIgnoredBoundsRegistration.unregister], and distinct from updating to an
     * empty rect, which would still sit at a point and deafen a tap there. This is the path
     * `AutographIgnore.swift` takes when its content collapses to zero size while still on-window.
     */
    @Test
    fun clearingARegionStopsItExcludingWhileItStaysRegistered() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val region = ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)
        assertEquals(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))

        region.clear()
        assertEquals(NativeHitTestResolution.Target("b"), resolve(root, 15.0, 15.0))

        // Still registered, so a later update revives it rather than being ignored.
        region.update(0f, 0f, 40f * scale, 40f * scale)
        assertEquals(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }
}
