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
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityLabel
import platform.UIKit.setAccessibilityTraits
import platform.UIKit.setFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [resolveIosElement] — the Compose adapter's own logic: claims-based suppression and
 * identifier selection. The underlying accessibility-tree walk this delegates to is tested in
 * `autograph-uikit` (`AccessibilityTreeTest`), against the same kind of hand-built [UIView] tree;
 * `compose.uiTest`'s iOS scene can't drive either, since it never populates a real accessibility tree
 * (see [PlatformAutocaptureTestHost.ios.kt]).
 *
 * `accessibilityFrame` is in points; tap positions are window-relative pixels (point *
 * [UIScreen.scale]), matching what `rememberElementResolver` produces via `root.localToWindow`.
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

    /**
     * The tap position is expressed the way [rememberElementResolver] actually produces one:
     * window-relative, via `root.localToWindow`, wholly unaware of root's own offset within the
     * window. This is the exact scenario `sample-iosUITests` caught on a real device (root
     * shrunk/repositioned by a safe-area-respecting SwiftUI container) — reproduced here without
     * needing a live app. `autograph-uikit`'s own test pins the conversion this depends on.
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
        // it to root's actual screen-absolute position so the walk's own top-level containment
        // check (root against root) behaves like a real one would.
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
        // independent measurement paths (Compose boundsInWindow() vs the accessibility tree's
        // accessibilityFrame + convertRect + scale) for the same physical element — a sub-pixel drift
        // between them must still count as a match.
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
