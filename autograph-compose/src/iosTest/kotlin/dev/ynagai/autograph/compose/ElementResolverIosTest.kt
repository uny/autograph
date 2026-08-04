package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitNotEnabled
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

    /**
     * A claim for an element drawn at exactly the size it was measured at — no transform, which is
     * every case but [resolveIosElementSuppressesAScaledTrackClickElement]'s.
     */
    private fun claim(drawn: Rect) = AutocaptureClaimBounds(drawn, drawn.size)

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
        claims.put(Any(), AutocaptureClaimKind.IGNORED, claim(Rect(0f, 0f, 100f, 100f)))

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementReturnsNullWhenTheButtonItselfIsTheInstrumentedClaim() {
        // Mirrors self-registration (trackClick registers its OWN boundsInWindow()),
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
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(buttonBounds))

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
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(driftedButtonBounds))

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
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(farDriftedButtonBounds))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }

    /**
     * The claim a `trackClick`ed element registers when it is SHORTER than the minimum touch
     * target: its unexpanded layout bounds, centred on the same point as the accessibility frame
     * the fixture's button publishes (20x20pt). Here 20x8pt, so only the vertical axis is expanded —
     * matching what was measured on device for a natural-height `Text` (#151).
     */
    private fun unexpandedClaimBoundsOfAShortButton(): Rect {
        val scale = UIScreen.mainScreen.scale
        return Rect(
            (10.0 * scale).toFloat(),
            (16.0 * scale).toFloat(),
            (30.0 * scale).toFloat(),
            (24.0 * scale).toFloat(),
        )
    }

    /** The fixture button's own 20x20pt size — what Compose would have expanded that claim to. */
    private fun minimumTouchTargetPxOfTheFixture(): Size {
        val side = (20.0 * UIScreen.mainScreen.scale).toFloat()
        return Size(side, side)
    }

    @Test
    fun resolveIosElementSuppressesAnInstrumentedElementShorterThanTheMinimumTouchTarget() {
        // #151: Compose expands a small element's touch target — and the accessibility frame it
        // publishes with it — to the minimum touch target, centred, while the claim it registers
        // stays the unexpanded layout bounds. The two must still be recognised as one element.
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(unexpandedClaimBoundsOfAShortButton()))

        val result = resolveIosElement(root, claims, position, minimumTouchTargetPxOfTheFixture())

        assertNull(result)
    }

    @Test
    fun resolveIosElementDoesNotSuppressAShortInstrumentedElementWithoutAMinimumTouchTarget() {
        // The negative of the test above, pinning that reconciling the expansion is what does the
        // work: with no minimum touch target to expand to, the same claim is the #151 defect —
        // an explicitly instrumented element reported a second time by autocapture.
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(unexpandedClaimBoundsOfAShortButton()))

        val result = resolveIosElement(root, claims, position, minimumTouchTargetPx = Size.Zero)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementSuppressesAScaledTrackClickElement() {
        // #159. The element is drawn at half the size it was measured at, so Compose expands the
        // touch target on the MEASURED size and then draws the result through the transform: the
        // published frame is the minimum HALVED, not the plain minimum. Here the button's frame is
        // the fixture's 20x20pt and the claim is drawn 10x10pt from a measured 20x20pt, so the
        // minimum has to be halved to 20x20pt of a 40x40pt nominal before it lands on the frame.
        val (root, position) = buildRootWithButton()
        val scale = UIScreen.mainScreen.scale
        val drawn = Rect(
            (15.0 * scale).toFloat(),
            (15.0 * scale).toFloat(),
            (25.0 * scale).toFloat(),
            (25.0 * scale).toFloat(),
        )
        val claims = AutocaptureClaims()
        claims.put(
            Any(),
            AutocaptureClaimKind.INSTRUMENTED_CLICK,
            AutocaptureClaimBounds(drawn, Size(drawn.width * 2f, drawn.height * 2f)),
        )
        val nominalMinimum = (40.0 * scale).toFloat()

        val result = resolveIosElement(root, claims, position, Size(nominalMinimum, nominalMinimum))

        assertNull(result)
    }

    @Test
    fun resolveIosElementDoesNotSuppressAScaledClaimWhoseUnscaledExpansionWouldHaveMatched() {
        // The negative of the test above: taking the claim's measured size as its drawn size — what
        // the code did before #159 — expands this claim onto the button's frame and vetoes. The
        // element really is drawn at half size, so that match describes no element on screen.
        val (root, position) = buildRootWithButton()
        val scale = UIScreen.mainScreen.scale
        val drawn = Rect(
            (18.0 * scale).toFloat(),
            (18.0 * scale).toFloat(),
            (22.0 * scale).toFloat(),
            (22.0 * scale).toFloat(),
        )
        val claims = AutocaptureClaims()
        claims.put(
            Any(),
            AutocaptureClaimKind.INSTRUMENTED_CLICK,
            AutocaptureClaimBounds(drawn, Size(drawn.width * 5f, drawn.height * 5f)),
        )

        val result = resolveIosElement(root, claims, position, minimumTouchTargetPxOfTheFixture())

        assertEquals("share_button", result)
    }

    /**
     * The fixture's button with one non-clickable child whose own accessibility frame is exactly
     * [unexpandedClaimBoundsOfAShortButton] — Compose Multiplatform publishes child `Text`s as their
     * own accessibility descendants even inside a merged clickable (measured), so a sub-minimum
     * `trackClick` *container* whose child fills it is reachable. Here 20x8pt inside 20x20pt.
     */
    private fun buildRootWithButtonContainingAShortChild(): Pair<UIView, Offset> {
        val (root, position) = buildRootWithButton()
        val button = root.subviews.first() as UIView
        val child = UIView()
        child.setPointFrame(10.0, 16.0, 20.0, 8.0)
        button.addSubview(child)
        return root to position
    }

    @Test
    fun resolveIosElementStillSuppressesAShortTrackClickElementThatHasAChildFillingIt() {
        // A descendant publishing the claim's own rect must NOT be read as "the claim belongs to
        // someone else": a trackClick claim's element is clickable by construction, so a match
        // describes that clickable however its children are laid out. Treating the descendant as
        // disambiguating would reopen #151 for a sub-minimum trackClick container.
        val (root, position) = buildRootWithButtonContainingAShortChild()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(unexpandedClaimBoundsOfAShortButton()))

        val result = resolveIosElement(root, claims, position, minimumTouchTargetPxOfTheFixture())

        assertNull(result)
    }

    @Test
    fun resolveIosElementStillDoesNotSuppressAnAncestorContainerWhenExpandingToTheMinimumTouchTarget() {
        // Expansion must not widen what counts as a match: a claim ALREADY larger than the minimum
        // touch target is left alone by it, so an instrumented ancestor container still doesn't
        // suppress a button inside it (the invariant the test below states without expansion).
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(Rect(0f, 0f, 100f, 100f)))

        val result = resolveIosElement(root, claims, position, minimumTouchTargetPxOfTheFixture())

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementDoesNotSuppressAButtonInsideAnInstrumentedAncestorContainer() {
        // Android's resolveAutocaptureTarget only checks the resolved nearestClickable's OWN
        // `instrumented` flag — an instrumented ANCESTOR (e.g. a trackClick container wrapping an
        // unrelated Button) never suppresses it. iOS has no ancestor chain to consult, so this
        // must be approximated by NOT treating a claim broader than nearestClickable's own bounds
        // (i.e. a container, not a self-registration) as a suppression match.
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        // A container claim covering the whole root — much larger than the button's own (10,10)-(30,30)
        // point bounds — simulating a trackClick ancestor, not the button self-registering.
        claims.put(Any(), AutocaptureClaimKind.INSTRUMENTED_CLICK, claim(Rect(0f, 0f, 100f, 100f)))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }

    @Test
    fun resolveIosElementIgnoresClaimsOutsideTheTapPosition() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.put(Any(), AutocaptureClaimKind.IGNORED, claim(Rect(500f, 500f, 600f, 600f)))

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result)
    }

    /**
     * `Modifier.clickable(enabled = false)` bridges to `Button|NotEnabled`, and tapping it fires no
     * handler at all — so an event here would be one Compose never produced. This is the last of the
     * three divergences from Android that #134 tracks; the other two do not reproduce on the bridged
     * accessibility tree as measured (see the CHANGELOG entry). Android's counterpart is #128.
     *
     * That the bridge really does publish the trait is not something this test can establish — it
     * sets the trait itself. `sample-ios`'s `testDisabledElementIsNotCaptured` is what pins that.
     */
    @Test
    fun resolveIosElementReturnsNullWhenTheTappedElementIsDisabled() {
        val (root, position) = buildRootWithButton()
        val button = root.subviews.first() as UIView
        button.setAccessibilityTraits(UIAccessibilityTraitButton or UIAccessibilityTraitNotEnabled)

        val result = resolveIosElement(root, claims = null, position)

        assertNull(result)
    }

    /**
     * The veto's shape, not just its existence: the disabled element keeps taking the hit, so a tap on
     * it reports nothing rather than being handed to the enabled clickable ancestor whose bounds also
     * cover it. Measured on-device for exactly this shape — Compose fires *nothing*, so the ancestor
     * never received the tap either (#134). The parent still resolves everywhere else.
     */
    @Test
    fun resolveIosElementDoesNotNameTheEnabledAncestorOfADisabledElement() {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val enabledParent = IdentifiableButtonView()
        enabledParent.setPointFrame(0.0, 0.0, 100.0, 100.0)
        enabledParent.setAccessibilityTraits(UIAccessibilityTraitButton)
        enabledParent.accessibilityIdentifier = "enabled_row"
        root.addSubview(enabledParent)

        val disabledChild = IdentifiableButtonView()
        disabledChild.setPointFrame(10.0, 10.0, 20.0, 20.0)
        disabledChild.setAccessibilityTraits(UIAccessibilityTraitButton or UIAccessibilityTraitNotEnabled)
        disabledChild.accessibilityIdentifier = "disabled_child"
        enabledParent.addSubview(disabledChild)

        val onChild = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())
        val onParent = Offset((50.0 * scale).toFloat(), (50.0 * scale).toFloat())

        assertNull(resolveIosElement(root, claims = null, onChild))
        assertEquals("enabled_row", resolveIosElement(root, claims = null, onParent))
    }

    /** The confinement in the other direction: a disabled ancestor does not veto an enabled child. */
    @Test
    fun resolveIosElementStillResolvesAnEnabledElementInsideADisabledAncestor() {
        val scale = UIScreen.mainScreen.scale
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val disabledParent = IdentifiableButtonView()
        disabledParent.setPointFrame(0.0, 0.0, 100.0, 100.0)
        disabledParent.setAccessibilityTraits(UIAccessibilityTraitButton or UIAccessibilityTraitNotEnabled)
        disabledParent.accessibilityIdentifier = "disabled_row"
        root.addSubview(disabledParent)

        val enabledChild = IdentifiableButtonView()
        enabledChild.setPointFrame(10.0, 10.0, 20.0, 20.0)
        enabledChild.setAccessibilityTraits(UIAccessibilityTraitButton)
        enabledChild.accessibilityIdentifier = "enabled_child"
        disabledParent.addSubview(enabledChild)

        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())

        assertEquals("enabled_child", resolveIosElement(root, claims = null, position))
    }
}
