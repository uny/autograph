package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import platform.darwin.NSObject

/**
 * Drives the SwiftUI opt-out's window-region veto ([AutographIgnoredBounds] / [registerAutographIgnoredBounds])
 * through the real [resolveNativeTapTarget]. This is the mechanism SwiftUI `.autographIgnore()` uses — a
 * positional veto — verified here without SwiftUI, so the exclusion is provably the registry's doing and
 * not a side effect of a background view perturbing tap resolution.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class IgnoredBoundsRegistryTest {

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    private fun button(id: String, x: Double, y: Double, w: Double, h: Double): UIView =
        UIView().apply {
            setPointFrame(x, y, w, h)
            setAccessibilityTraits(UIAccessibilityTraitButton)
            (this as NSObject).setValue(id, forKey = "accessibilityIdentifier")
        }

    private val registrations = mutableListOf<AutographIgnoredBoundsRegistration>()

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
        val root = UIView().apply { setPointFrame(0.0, 0.0, 100.0, 100.0) }
        root.addSubview(button("secret_button", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        assertEquals(
            "secret_button",
            resolveNativeTapTarget(root, position, scale),
            "precondition: without the region the tap resolves",
        )

        // A region (px) covering the button's position.
        ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "a tap whose position is inside a registered region must not be reported",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheExcludedRegion() {
        val root = UIView().apply { setPointFrame(0.0, 0.0, 200.0, 100.0) }
        root.addSubview(button("public_button", 120.0, 10.0, 20.0, 20.0))

        // Region only covers the left half; the button is on the right.
        ignoreBoundsPx(0f, 0f, 100f * scale, 100f * scale)

        assertEquals(
            "public_button",
            resolveNativeTapTarget(root, AxPoint(125f * scale, 15f * scale), scale),
            "a region must not deafen taps outside it",
        )
    }

    @Test
    fun unregisteringARegionRestoresResolution() {
        val root = UIView().apply { setPointFrame(0.0, 0.0, 100.0, 100.0) }
        root.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        val region = ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)
        assertNull(resolveNativeTapTarget(root, position, scale))

        region.unregister()
        assertEquals("b", resolveNativeTapTarget(root, position, scale))
    }

    @Test
    fun aRegionMovedOffTheTapByUpdateNoLongerExcludesIt() {
        val root = UIView().apply { setPointFrame(0.0, 0.0, 200.0, 200.0) }
        root.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        val region = ignoreBoundsPx(0f, 0f, 40f * scale, 40f * scale)
        assertNull(resolveNativeTapTarget(root, position, scale))

        // The content scrolled away: update the rect off the tap. The veto must follow the new rect.
        region.update(100f * scale, 100f * scale, 140f * scale, 140f * scale)
        assertEquals(
            "b",
            resolveNativeTapTarget(root, position, scale),
            "after update() moves the region off the tap, the tap resolves again",
        )
    }
}
