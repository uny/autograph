package dev.ynagai.autograph.uikit

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.setAccessibilityElements
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import platform.UIKit.setFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the accessibility-tree walk ([deepestAccessibilityHitPath] / [accessibilityChildren] /
 * [accessibilityBoundsInWindowPx]) against genuine [UIView] instances. No UI framework is involved —
 * these helpers only ever consult UIKit accessibility APIs — and a hand-built [UIView] tree is the
 * only way to drive this logic in CI: `compose.uiTest`'s iOS scene never populates a real
 * accessibility tree (see autograph-compose's `PlatformAutocaptureTestHost.ios.kt`).
 *
 * `accessibilityFrame` is in points; the walk returns window-space pixels, so frames below are set in
 * points and tap positions given in pixels (point * [UIScreen.scale]), matching real callers.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class AccessibilityTreeTest {

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    @Test
    fun findsTheNearestClickableAtTheTapPosition() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(button)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(listOf(root, button), path)
        assertTrue((path?.last() as? UIView)?.isAccessibilityButton() == true)
    }

    @Test
    fun returnsNullWhenThePositionMissesTheRoot() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val position = AxPoint(500f * scale, 500f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertNull(path)
    }

    @Test
    fun prefersTheLastChildWhenBoundsOverlap() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val back = UIView()
        back.setPointFrame(10.0, 10.0, 30.0, 30.0)
        back.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(back)

        val front = UIView()
        front.setPointFrame(10.0, 10.0, 30.0, 30.0)
        front.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(front)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(front, path?.last())
    }

    @Test
    fun accessibilityBoundsScalesPointsToPixels() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val node = UIView()
        node.setPointFrame(2.0, 3.0, 4.0, 5.0)
        root.addSubview(node)

        val bounds = node.accessibilityBoundsInWindowPx(root, scale)

        assertTrue(bounds != null)
        assertEquals(2f * scale, bounds.left, 0.01f)
        assertEquals(3f * scale, bounds.top, 0.01f)
        assertEquals((2f + 4f) * scale, bounds.right, 0.01f)
        assertEquals((3f + 5f) * scale, bounds.bottom, 0.01f)
    }

    @Test
    fun accessibilityChildrenUnionsAccessibilityElementsAndSubviews() {
        val root = UIView()
        val subview = UIView()
        root.addSubview(subview)
        val axOnlyElement = UIView()
        root.setAccessibilityElements(listOf(axOnlyElement))

        val children = root.accessibilityChildren()

        assertTrue(children.contains(subview))
        assertTrue(
            children.contains(axOnlyElement),
            "expected accessibilityChildren to include accessibilityElements()-only descendants — this is the entire reason it exists (Compose's AX root can be reachable only via accessibilityElements(), not subviews, per AccessibilityTree.kt's leading comment)",
        )
    }

    /**
     * [accessibilityBoundsScalesPointsToPixels] never attaches a window, so
     * `convertRect(_:fromCoordinateSpace:)` has no non-trivial screen→window offset to apply and that
     * test alone can't distinguish a correct window-relative conversion from a (wrong) root-local one.
     * This attaches `root` to a real [UIWindow] at a non-origin position so the conversion actually
     * exercises a screen→window translation, not just the scale multiply — and, critically, so the
     * result does NOT depend on root's own offset within the window (verifying the fix for the bug
     * below), only on the window's.
     *
     * On-device (see [AxRect]'s kdoc for the full account): converting to *root*-local space here used
     * to silently subtract root's own offset within its window — an offset the caller's tap position
     * was never adjusted for, since it's already window-relative. That mismatch is invisible whenever
     * root fills its window (root's offset is zero), which is why it went undetected until
     * `ComposeUIViewController` was embedded in a safe-area-respecting SwiftUI container (which
     * shrinks/repositions the Compose root to fit the safe content area) — every tap then
     * misattributed by roughly the safe-area inset.
     */
    @Test
    fun accessibilityBoundsConvertsToTheWindowsSpaceNotTheRootsOwnOffsetSpace() {
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 400.0, 800.0))

        val root = UIView()
        // Root does NOT fill its window — mirrors ComposeUIViewController's view being
        // shrunk/repositioned by a safe-area-respecting SwiftUI container.
        root.setFrame(CGRectMake(50.0, 60.0, 100.0, 100.0))
        window.addSubview(root)

        val node = UIView()
        // accessibilityFrame is screen-absolute; the window sits at the screen origin, so
        // screen-absolute and window-relative coincide here.
        node.setPointFrame(52.0, 63.0, 4.0, 5.0)
        root.addSubview(node)

        val bounds = node.accessibilityBoundsInWindowPx(root, scale)

        assertTrue(bounds != null)
        // Window-relative (52, 63, 4, 5) — NOT root-local (2, 3, 4, 5), i.e. NOT adjusted for
        // root's (50, 60) offset within the window.
        assertEquals(52f * scale, bounds.left, 0.5f)
        assertEquals(63f * scale, bounds.top, 0.5f)
        assertEquals((52f + 4f) * scale, bounds.right, 0.5f)
        assertEquals((63f + 5f) * scale, bounds.bottom, 0.5f)
    }

    @Test
    fun containsIsLeftTopInclusiveAndRightBottomExclusive() {
        // Pins the boundary semantics against Compose's Rect.contains, which the Compose adapter's
        // tap positions are produced by and which this walk must not silently diverge from.
        val rect = AxRect(10f, 20f, 30f, 40f)

        assertTrue(rect.contains(AxPoint(10f, 20f)))
        assertTrue(rect.contains(AxPoint(29.9f, 39.9f)))
        assertTrue(!rect.contains(AxPoint(30f, 30f)))
        assertTrue(!rect.contains(AxPoint(20f, 40f)))
        assertTrue(!rect.contains(AxPoint(9.9f, 30f)))
    }
}
