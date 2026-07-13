package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.setAccessibilityElements
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

    // A bare UIView() doesn't statically conform to UIAccessibilityIdentificationProtocol in this
    // Kotlin/Native binding (unlike a real UIKit-driven accessibility element), so tests that need a
    // testTag/`accessibilityIdentifier` use this explicit-conformance subclass instead.
    private class IdentifiableButtonView : UIView(CGRectZero.readValue()), UIAccessibilityIdentificationProtocol {
        private var identifier: String? = null
        override fun accessibilityIdentifier(): String? = identifier
        override fun setAccessibilityIdentifier(accessibilityIdentifier: String?) {
            identifier = accessibilityIdentifier
        }
    }

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
        val axOnlyElement = UIView()
        root.setAccessibilityElements(listOf(axOnlyElement))

        val children = root.accessibilityChildren()

        assertTrue(children.contains(subview))
        assertTrue(
            children.contains(axOnlyElement),
            "expected accessibilityChildren to include accessibilityElements()-only descendants — this is the entire reason it exists (Compose's AX root can be reachable only via accessibilityElements(), not subviews, per this file's KDoc)",
        )
    }

    /**
     * The above [accessibilityLocalBoundsScalesPointsToPixels] never attaches [UIView.window] a
     * window, so `convertRect(_:fromCoordinateSpace:)` has no non-trivial screen→window offset to
     * apply and that test alone can't distinguish a correct window-relative conversion from a
     * (wrong) root-local one. This attaches `root` to a real [UIWindow] at a non-origin position so
     * the conversion actually exercises a screen→window translation, not just the scale multiply —
     * and, critically, so the result does NOT depend on root's own offset within the window
     * (verifying the fix for the bug below), only on the window's.
     *
     * On-device (`sample-ios`'s `ElementResolver.ios.kt` kdoc has the full account): converting to
     * *root*-local space here used to silently subtract root's own offset within its window — an
     * offset Compose's own `position` (from `root.localToWindow`, in [rememberElementResolver]) was
     * never adjusted for, since it's already window-relative. That mismatch is invisible whenever
     * root fills its window (root's offset is zero), which is why it went undetected until
     * `ComposeUIViewController` was embedded in a safe-area-respecting SwiftUI container (which
     * shrinks/repositions the Compose root to fit the safe content area) — every tap then
     * misattributed by roughly the safe-area inset.
     */
    @Test
    fun accessibilityLocalBoundsConvertsToTheWindowsSpaceNotTheRootsOwnOffsetSpace() {
        val scale = UIScreen.mainScreen.scale
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

        val bounds = node.accessibilityLocalBounds(root)

        assertTrue(bounds != null)
        // Window-relative (52, 63, 4, 5) — NOT root-local (2, 3, 4, 5), i.e. NOT adjusted for
        // root's (50, 60) offset within the window.
        assertEquals((52.0 * scale).toFloat(), bounds.left, 0.5f)
        assertEquals((63.0 * scale).toFloat(), bounds.top, 0.5f)
        assertEquals(((52.0 + 4.0) * scale).toFloat(), bounds.right, 0.5f)
        assertEquals(((63.0 + 5.0) * scale).toFloat(), bounds.bottom, 0.5f)
    }

    /**
     * End-to-end version of the above at the [resolveIosElement] level, with a tap position
     * expressed the way [rememberElementResolver] actually produces one: window-relative, via
     * `root.localToWindow`, wholly unaware of root's own offset within the window. This is the
     * exact scenario `sample-iosUITests` caught on a real device (root shrunk/repositioned by a
     * safe-area-respecting SwiftUI container) — reproduced here without needing a live app.
     */
    @Test
    fun resolveIosElementAttributesCorrectlyWhenTheRootDoesNotFillItsWindow() {
        val scale = UIScreen.mainScreen.scale
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 400.0, 800.0))

        val root = UIView()
        root.setFrame(CGRectMake(50.0, 60.0, 100.0, 100.0))
        // accessibilityFrame isn't derived from .frame automatically in this headless test
        // environment (every other test in this file sets it explicitly on its root, too) — set
        // it to root's actual screen-absolute position so deepestAccessibilityHitPath's own
        // top-level containment check (root against root) behaves like a real one would.
        root.setPointFrame(50.0, 60.0, 100.0, 100.0)
        window.addSubview(root)

        val button = IdentifiableButtonView()
        // Root-local (10, 10, 20, 20) → screen-absolute (60, 70, 20, 20), since root sits at
        // (50, 60) and the window is at the screen origin.
        button.setPointFrame(60.0, 70.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        button.accessibilityIdentifier = "share_button"
        root.addSubview(button)

        // Window-relative tap at (65, 75) points — inside the button's window-relative bounds
        // (60, 70)-(80, 90) — is what `root.localToWindow` would actually produce for a tap on
        // this button, root offset included.
        val position = Offset((65.0 * scale).toFloat(), (75.0 * scale).toFloat())

        val result = resolveIosElement(root, claims = null, position)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenTheTapHitsNoClickable() {
        // Every other resolveIosElement test taps a button-trait view via buildRootWithButton() —
        // this covers the (arguably most common at runtime) opposite branch: the tap hits an
        // element, but nothing in its ancestry exposes UIAccessibilityTraitButton.
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val nonClickableChild = UIView()
        nonClickableChild.setPointFrame(10.0, 10.0, 20.0, 20.0)
        root.addSubview(nonClickableChild)
        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())

        val result = resolveIosElement(root, claims = null, position)

        assertNull(result)
    }

    // testTag drives identification (`accessibilityIdentifier`); accessibilityLabel is also set to
    // confirm resolveIosElement never falls back to it (see resolveIosElementNeverFallsBackToTheAccessibilityLabel).
    private fun buildRootWithButton(): Pair<UIView, Offset> {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val button = IdentifiableButtonView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        button.accessibilityIdentifier = "share_button"
        button.setAccessibilityLabel("share_button_label")
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
    fun resolveIosElementNeverFallsBackToTheAccessibilityLabel() {
        // UIKit gives no way to tell an explicit contentDescription-derived label apart from one
        // Compose Multiplatform synthesizes from the element's displayed text, so falling back to
        // accessibilityLabel here would silently defeat autocapture's "never displayed text"
        // guarantee — unlike Android's resolveAutocaptureTarget, which only ever reads the explicit
        // SemanticsProperties.ContentDescription.
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val button = IdentifiableButtonView() // no testTag set — only the label below
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        button.setAccessibilityLabel("share_button_label")
        root.addSubview(button)
        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())

        val result = resolveIosElement(root, claims = null, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenThePositionIsInsideAnIgnoredClaim() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.IGNORED, Rect(0f, 0f, 100f, 100f))

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenTheButtonItselfIsTheInstrumentedClaim() {
        // Mirrors self-registration (trackClick/trackImpression register their OWN boundsInWindow()),
        // which resolveIosElement must still suppress to avoid double-reporting an explicitly
        // instrumented element via the ambient autocapture observer too.
        val scale = UIScreen.mainScreen.scale
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        val buttonBounds = Rect(
            (10.0 * scale).toFloat(),
            (10.0 * scale).toFloat(),
            (30.0 * scale).toFloat(),
            (30.0 * scale).toFloat(),
        )
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED, buttonBounds)

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementSuppressesTheInstrumentedButtonEvenWhenBoundsDriftWithinTolerance() {
        // approximatelyEquals' 1f tolerance exists because nearestClickable's bounds come from two
        // independent measurement paths (Compose boundsInWindow() vs UIKit accessibilityFrame +
        // convertRect + scale) for the same physical element — a sub-pixel drift between them must
        // still count as a match.
        val scale = UIScreen.mainScreen.scale
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        val driftedButtonBounds = Rect(
            (10.0 * scale).toFloat() + 0.5f,
            (10.0 * scale).toFloat() + 0.5f,
            (30.0 * scale).toFloat() + 0.5f,
            (30.0 * scale).toFloat() + 0.5f,
        )
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED, driftedButtonBounds)

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementDoesNotSuppressWhenBoundsDriftBeyondTolerance() {
        val scale = UIScreen.mainScreen.scale
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        val farDriftedButtonBounds = Rect(
            (10.0 * scale).toFloat() + 1.5f,
            (10.0 * scale).toFloat() + 1.5f,
            (30.0 * scale).toFloat() + 1.5f,
            (30.0 * scale).toFloat() + 1.5f,
        )
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED, farDriftedButtonBounds)

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementDoesNotSuppressAButtonInsideAnInstrumentedAncestorContainer() {
        // Android's resolveAutocaptureTarget only checks the resolved nearestClickable's OWN
        // `instrumented` flag — an instrumented ANCESTOR (e.g. a trackImpression container wrapping
        // an unrelated Button) never suppresses it. iOS has no ancestor chain to consult, so this
        // must be approximated by NOT treating a claim broader than nearestClickable's own bounds
        // (i.e. a container, not a self-registration) as a suppression match.
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        // A container claim covering the whole root — much larger than the button's own (10,10)-(30,30)
        // point bounds — simulating a trackImpression ancestor, not the button self-registering.
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED, Rect(0f, 0f, 100f, 100f))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementIgnoresClaimsOutsideTheTapPosition() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.IGNORED, Rect(500f, 500f, 600f, 600f))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }
}
