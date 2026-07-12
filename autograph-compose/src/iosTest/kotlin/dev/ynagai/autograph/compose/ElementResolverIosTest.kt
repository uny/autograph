package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real UIKit accessibility-tree walk (deepestAccessibilityHitPath /
 * accessibilityChildren / accessibilityLocalBounds) against genuine [UIView] instances — no Compose
 * composition needed, since these helpers only ever consult UIKit accessibility APIs. This is the
 * automated coverage [ElementResolver.ios.kt]'s KDoc notes is otherwise missing: `compose.uiTest`'s
 * iOS scene never populates a real accessibility tree (see [PlatformAutocaptureTestHost.ios.kt]), so
 * only a hand-built UIView tree can drive this logic in CI.
 *
 * `accessibilityFrame` is documented in points; [deepestAccessibilityHitPath]/[accessibilityLocalBounds]
 * convert to pixels internally, so frames below are set in points and tap positions in pixels
 * (point * [UIScreen.scale]), matching real callers.
 */
@OptIn(ExperimentalForeignApi::class)
class ElementResolverIosTest {

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    @Test
    fun findsTheNearestClickableAtTheTapPosition() {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(button)

        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())
        val path = deepestAccessibilityHitPath(root, root, position)

        assertEquals(listOf(root, button), path)
        assertTrue((path?.last() as? UIView)?.isAccessibilityButton() == true)
    }

    @Test
    fun returnsNullWhenThePositionMissesTheRoot() {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val position = Offset((500.0 * scale).toFloat(), (500.0 * scale).toFloat())
        val path = deepestAccessibilityHitPath(root, root, position)

        assertNull(path)
    }

    @Test
    fun prefersTheLastChildWhenBoundsOverlap() {
        val scale = UIScreen.mainScreen.scale
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

        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())
        val path = deepestAccessibilityHitPath(root, root, position)

        assertEquals(front, path?.last())
    }

    @Test
    fun accessibilityLocalBoundsScalesPointsToPixels() {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val node = UIView()
        node.setPointFrame(2.0, 3.0, 4.0, 5.0)
        root.addSubview(node)

        val bounds = node.accessibilityLocalBounds(root)

        assertTrue(bounds != null)
        assertEquals((2.0 * scale).toFloat(), bounds.left, 0.01f)
        assertEquals((3.0 * scale).toFloat(), bounds.top, 0.01f)
        assertEquals(((2.0 + 4.0) * scale).toFloat(), bounds.right, 0.01f)
        assertEquals(((3.0 + 5.0) * scale).toFloat(), bounds.bottom, 0.01f)
    }

    @Test
    fun accessibilityChildrenUnionsAccessibilityElementsAndSubviews() {
        val root = UIView()
        val subview = UIView()
        root.addSubview(subview)

        val children = root.accessibilityChildren()

        assertTrue(children.contains(subview))
    }
}
