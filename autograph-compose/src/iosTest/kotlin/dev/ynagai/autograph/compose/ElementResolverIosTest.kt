package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityLabel
import platform.UIKit.setAccessibilityTraits
import platform.UIKit.setFrame
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

    /**
     * The above [accessibilityLocalBoundsScalesPointsToPixels] never attaches [UIView.window] a
     * window, so `convertRect(_:fromCoordinateSpace:)` has no non-trivial screen→local offset to
     * apply and that test alone can't distinguish a correct offset subtraction from none at all.
     * This attaches `root` to a real [UIWindow] at a non-origin position so the conversion actually
     * exercises a screen→local translation, not just the scale multiply.
     */
    @Test
    fun accessibilityLocalBoundsSubtractsTheRootsScreenOffsetWhenWindowAttached() {
        val scale = UIScreen.mainScreen.scale
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 400.0, 800.0))

        val root = UIView()
        root.setFrame(CGRectMake(50.0, 60.0, 100.0, 100.0))
        window.addSubview(root)

        val node = UIView()
        // accessibilityFrame is screen-absolute; root sits at (50, 60) in the window (which is at
        // the screen origin), so a node meant to read as root-local (2, 3, 4, 5) must be set at
        // screen-absolute (52, 63, 4, 5).
        node.setPointFrame(52.0, 63.0, 4.0, 5.0)
        root.addSubview(node)

        val bounds = node.accessibilityLocalBounds(root)

        assertTrue(bounds != null)
        assertEquals((2.0 * scale).toFloat(), bounds.left, 0.5f)
        assertEquals((3.0 * scale).toFloat(), bounds.top, 0.5f)
        assertEquals(((2.0 + 4.0) * scale).toFloat(), bounds.right, 0.5f)
        assertEquals(((3.0 + 5.0) * scale).toFloat(), bounds.bottom, 0.5f)
    }

    private fun buildRootWithButton(): Pair<UIView, Offset> {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        button.setAccessibilityLabel("share_button")
        root.addSubview(button)
        return root to Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())
    }

    @Test
    fun resolveIosElementReturnsAnIdentifierForAnUnclaimedButton() {
        val (root, position) = buildRootWithButton()

        val result = resolveIosElement(root, claims = null, position)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenThePositionIsInsideAnIgnoredClaim() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.IGNORED, androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 100f))

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenThePositionIsInsideAnInstrumentedClaim() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED, androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 100f))

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementIgnoresClaimsOutsideTheTapPosition() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.IGNORED, androidx.compose.ui.geometry.Rect(500f, 500f, 600f, 600f))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }
}
