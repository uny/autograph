package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.uikit.AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class ElementResolverIosTest {

    private val scale: Double get() = UIScreen.mainScreen.scale

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
     * A window, a scope wrapper carrying [payload] after the reserved prefix, and a button inside it.
     * The wrapper is not clickable, matching what the scope composable emits — the traversal group
     * Compose Multiplatform bridges publishes a container, never a control.
     */
    private fun buildScopedRoot(vararg payloads: String): Pair<UIView, Offset> {
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 200.0, 200.0))
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 200.0, 200.0)
        window.addSubview(root)

        var parent: UIView = root
        for (payload in payloads) {
            val wrapper = IdentifiableButtonView()
            wrapper.setPointFrame(0.0, 0.0, 100.0, 100.0)
            wrapper.setAccessibilityIdentifier(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX + payload)
            parent.addSubview(wrapper)
            parent = wrapper
        }

        val button = IdentifiableButtonView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityIdentifier("share_button")
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        parent.addSubview(button)

        return root to Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())
    }

    @Test
    fun readsTheScopeOffTheSamePathTheIdentifierCameFrom() {
        val (root, position) = buildScopedRoot("""{"article_id":"42"}""")

        val result = resolveIosElement(root, claims = null, position)

        assertEquals("share_button", result?.identifier)
        assertEquals(JsonObject(mapOf("article_id" to JsonPrimitive("42"))), result?.scope)
    }

    /**
     * The overhanging clickable, driven through **this** resolver rather than through the walk.
     *
     * `AccessibilityTreeTest` pins what `allowScopeContainerDescent` does; nothing pinned that this
     * call site passes it. Every other fixture here puts the wrapper *around* the tap, so the
     * containment gate never fires and the flag is inert in all of them — deleting
     * `allowScopeContainerDescent = true` from `resolveIosElement` left both iOS suites green, while
     * the drop this PR's fix exists to remove came straight back. Geometry is
     * `descendsPastAScopeContainerThatDoesNotContainThePosition`'s, so the two are a matched pair
     * across the module boundary.
     */
    @Test
    fun resolvesAClickableDrawnOutsideItsScopeWrappersBounds() {
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 200.0, 200.0))
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 200.0, 200.0)
        window.addSubview(root)

        val wrapper = IdentifiableButtonView()
        wrapper.setPointFrame(0.0, 0.0, 10.0, 10.0)
        wrapper.setAccessibilityIdentifier("""${AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX}{"article_id":"42"}""")
        root.addSubview(wrapper)

        // Drawn outside the wrapper entirely — an overhanging badge, an `offset` decoration.
        val badge = IdentifiableButtonView()
        badge.setPointFrame(50.0, 50.0, 20.0, 20.0)
        badge.setAccessibilityIdentifier("badge")
        badge.setAccessibilityTraits(UIAccessibilityTraitButton)
        wrapper.addSubview(badge)

        val position = Offset((55.0 * scale).toFloat(), (55.0 * scale).toFloat())
        val result = resolveIosElement(root, claims = null, position)

        assertEquals("badge", result?.identifier)
        assertEquals(JsonObject(mapOf("article_id" to JsonPrimitive("42"))), result?.scope)
    }

    /**
     * The reader's half of the payload ceiling. The writer's is not enough on its own: the prefix sits
     * in an identifier slot the host app owns, so a foreign node — an app view, a third-party SDK under
     * a `UIKitView` — can carry one of any size, and without this the tap would parse it on the main
     * thread and fold it into the event. Oversized is skipped like any unreadable payload; the second,
     * in-bounds wrapper is the positive control that keeps this from passing vacuously.
     */
    @Test
    fun skipsAScopePayloadOverTheCeiling() {
        val oversized = """{"blob":"${"x".repeat(64 * 1024)}"}"""
        val (root, position) = buildScopedRoot(oversized, """{"article_id":"42"}""")

        val result = resolveIosElement(root, claims = null, position)

        assertEquals("share_button", result?.identifier)
        assertEquals(JsonObject(mapOf("article_id" to JsonPrimitive("42"))), result?.scope)
    }

    /**
     * Nesting composes outer→inner with the inner winning a key clash, the rule
     * [resolveAutocaptureTarget] applies on Android. The path is root→hit node, so the deeper wrapper
     * folds in later.
     */
    @Test
    fun nestedScopesMergeWithTheInnerWinningAKeyClash() {
        val (root, position) = buildScopedRoot(
            """{"section":"feed","article_id":"outer"}""",
            """{"article_id":"inner"}""",
        )

        val result = resolveIosElement(root, claims = null, position)

        assertEquals(
            JsonObject(mapOf("section" to JsonPrimitive("feed"), "article_id" to JsonPrimitive("inner"))),
            result?.scope,
        )
    }

    /**
     * The identifier is the host app's to write, so this parses arbitrary strings on the main thread
     * inside a tap handler. A payload that isn't an object is dropped, leaving the tap attributed as
     * it would have been without the wrapper — never thrown on, and never propagated as a scope.
     */
    @Test
    fun skipsAScopePayloadThatDoesNotParse() {
        val (root, position) = buildScopedRoot("not json at all", """{"article_id":"42"}""")

        val result = resolveIosElement(root, claims = null, position)

        assertEquals("share_button", result?.identifier)
        assertEquals(JsonObject(mapOf("article_id" to JsonPrimitive("42"))), result?.scope)
    }

    /**
     * The other half of that: a payload that parses perfectly well but isn't an object. Syntactically
     * invalid JSON exercises the parser's throw path; these exercise the cast, which is a different
     * branch and would otherwise be untested.
     */
    @Test
    fun skipsAScopePayloadThatParsesToSomethingOtherThanAnObject() {
        for (payload in listOf("[]", "null", "42", "\"a string\"")) {
            val (root, position) = buildScopedRoot(payload, """{"article_id":"42"}""")

            val result = resolveIosElement(root, claims = null, position)

            assertEquals("share_button", result?.identifier, "payload=$payload")
            assertEquals(JsonObject(mapOf("article_id" to JsonPrimitive("42"))), result?.scope, "payload=$payload")
        }
    }

    @Test
    fun reportsAnEmptyScopeWhenNoWrapperIsOnThePath() {
        val (root, position) = buildScopedRoot()

        assertEquals(EmptyJsonObject, resolveIosElement(root, claims = null, position)?.scope)
    }

    /**
     * The reserved prefix names an Autograph marker, never something the user touched. It can only
     * reach the nearest-clickable slot if an app puts the prefix on a control of its own; the tap then
     * drops rather than reporting a marker as an element.
     */
    @Test
    fun neverReportsAReservedIdentifierAsTheTappedElement() {
        val window = UIWindow()
        window.setFrame(CGRectMake(0.0, 0.0, 200.0, 200.0))
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 200.0, 200.0)
        window.addSubview(root)

        val marker = IdentifiableButtonView()
        marker.setPointFrame(0.0, 0.0, 100.0, 100.0)
        marker.setAccessibilityIdentifier("${AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX}{}")
        marker.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(marker)

        val position = Offset((15.0 * scale).toFloat(), (15.0 * scale).toFloat())

        assertNull(resolveIosElement(root, claims = null, position))
    }

    /** Opens a generation and records a [trackClick] execution in it, as a real tap would. */
    private fun AutocaptureClaims.withAnInstrumentedClickExecuted() {
        openTapGeneration()
        markInstrumentedClickExecuted()
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

        assertEquals("share_button", result?.identifier)
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

        assertEquals("share_button", result?.identifier)
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
        claims.ignored[Any()] = Rect(0f, 0f, 100f, 100f)

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    /**
     * The instrumented veto, now stated as execution rather than geometry.
     *
     * It suppresses whatever this dispatch resolved to, not "the element that matches the claim" —
     * there is no claim any more. A single pointer activates at most one `clickable`, so a
     * [trackClick] handler having run means this dispatch's click is already reported, and anything
     * the accessibility walk lands on here is either that same element or a misattribution.
     */
    @Test
    fun resolveIosElementReturnsNullWhenATrackClickRanDuringThisDispatch() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.withAnInstrumentedClickExecuted()

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    /**
     * The negative, and the whole of #179: an instrumented element that did NOT run must not silence
     * the element that did.
     *
     * This is the shape geometry could not express. Measured on device, a `fillMaxWidth` `trackClick`
     * `Text` at the top of a `fillMaxWidth`, 48dp, uninstrumented `clickable` published frames — host
     * `(16, 256, 370x48)`, inner `(16, 244, 370x48)` — that are the same size and both contain the
     * inner's registered rect, differing only in where Compose's touch-target clamp landed. Every rule
     * over those rectangles either double-reported the inner (#151, #179) or dropped the host's own
     * tap entirely (#180). Tapping the host's exposed strip runs no `trackClick` at all, so with
     * execution as the signal the two are not even close cases.
     */
    @Test
    fun resolveIosElementReportsTheTapWhenNoTrackClickRan() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result?.identifier)
    }

    /**
     * Evidence from a *previous* dispatch must not suppress this one.
     *
     * The generation is what confines a mark to the tap that produced it, and this is the failure it
     * exists to prevent — the one way execution-based suppression could drop an event rather than
     * duplicate one. [AutocaptureClaimsGenerationTest] pins the bookkeeping; this pins that the
     * resolver actually reads it.
     */
    @Test
    fun resolveIosElementReportsTheTapWhenTheExecutionBelongsToAClosedGeneration() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        val token = claims.openTapGeneration()
        claims.markInstrumentedClickExecuted()
        claims.closeTapGeneration(token)
        claims.openTapGeneration()

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result?.identifier)
    }

    /**
     * The observer discards execution evidence it cannot attribute to the pointer being reported (a
     * multi-touch dispatch, or a Final that does not belong to its Initial). Discarding it must
     * restore reporting, not leave a half-suppressed state.
     */
    @Test
    fun resolveIosElementReportsTheTapAfterTheObserverDiscardsUnattributableEvidence() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.withAnInstrumentedClickExecuted()

        claims.clearTapExecution()

        assertEquals("share_button", resolveIosElement(root, claims, position)?.identifier)
    }

    /**
     * An activation that reaches no pointer dispatch — VoiceOver's double-tap, an `Enter` press, a
     * dialog route — marks nothing, so the next real tap is unaffected. Without the generation this
     * would be a persistent "already instrumented" flag that silently ate subsequent taps.
     *
     * No [AutocaptureClaims.openTapGeneration] between the mark and the resolve, deliberately: with
     * one, deleting `markInstrumentedClickExecuted`'s `generation != null` guard still leaves this
     * green, because opening clears the flag the missing guard let through — the test would then be
     * restating [resolveIosElementReportsTheTapWhenTheExecutionBelongsToAClosedGeneration] rather
     * than pinning the guard. Resolving straight after the mark is what makes it discriminating.
     */
    @Test
    fun resolveIosElementReportsTheTapWhenTheActivationHappenedOutsideAnyDispatch() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.markInstrumentedClickExecuted()

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result?.identifier)
    }

    /**
     * The two vetoes are independent: [autographIgnore] is positional and has no execution to observe,
     * so it must keep working with the generation closed and empty.
     */
    @Test
    fun resolveIosElementStillHonoursIgnoredBoundsWithNoExecutionRecorded() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.ignored[Any()] = Rect(0f, 0f, 100f, 100f)
        claims.openTapGeneration()

        val result = resolveIosElement(root, claims, position)

        assertNull(result)
    }

    @Test
    fun resolveIosElementIgnoresClaimsOutsideTheTapPosition() {
        val (root, position) = buildRootWithButton()
        val claims = AutocaptureClaims()
        claims.ignored[Any()] = Rect(500f, 500f, 600f, 600f)

        val result = resolveIosElement(root, claims, position)

        assertEquals("share_button", result?.identifier)
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
        assertEquals("enabled_row", resolveIosElement(root, claims = null, onParent)?.identifier)
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

        assertEquals("enabled_child", resolveIosElement(root, claims = null, position)?.identifier)
    }
}
