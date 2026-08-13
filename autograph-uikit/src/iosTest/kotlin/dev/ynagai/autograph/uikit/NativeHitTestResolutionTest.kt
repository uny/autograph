package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIButton
import platform.UIKit.UIScreen
import platform.UIKit.UIScrollView
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityFrame
import platform.darwin.NSObject

/**
 * Drives [resolveNativeTapTargetByHitTest] against hand-built [UIView] trees.
 *
 * **These fixtures set real `frame`s and nothing else** — no `accessibilityFrame`, no traits, no
 * `isAccessibilityElement`. That is not a shortcut, it is the point: it reproduces the cold process
 * measured on a physical device in #189, where UIKit had built no accessibility tree at all.
 * [resolvesAControlInATreeWithNoAccessibilityStateAtAll] asserts both halves — that this resolver names
 * the button *and* that [resolveNativeTapTarget] finds nothing in the very same tree — so the fixture's
 * coldness is checked rather than assumed, and the suite would notice if a future change let the
 * accessibility path start resolving these trees by accident.
 *
 * The reverse holds for the older [NativeTapResolutionTest] and its sibling registry suites: those
 * fixtures set only `accessibilityFrame`, so their views have zero real bounds, `hitTest` declines
 * every one of their taps, and they continue to exercise the accessibility resolver exactly as before.
 *
 * Positions are given in points; the pixel position is derived the way the production caller derives
 * it, so the [AutographIgnoredBounds] veto is exercised in its real space.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class NativeHitTestResolutionTest {

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    /** A view with a real `frame`, which is all `hitTest` consults. */
    private fun view(x: Double, y: Double, w: Double, h: Double): UIView =
        UIView(frame = CGRectMake(x, y, w, h))

    private fun <T : UIView> T.identified(id: String): T =
        apply { (this as NSObject).setValue(id, forKey = "accessibilityIdentifier") }

    /** A real `UIControl`, so `isEnabled` and the control half of the predicate are the real ones. */
    private fun controlAt(x: Double, y: Double, w: Double, h: Double): UIButton =
        UIButton(frame = CGRectMake(x, y, w, h))

    private fun UIView.withTapRecognizer(recognizerEnabled: Boolean = true): UIView = apply {
        addGestureRecognizer(UITapGestureRecognizer().also { it.enabled = recognizerEnabled })
    }

    /** Resolves a tap given in window points, deriving the pixel position as the production caller does. */
    private fun resolve(root: UIView, xPoints: Double, yPoints: Double): NativeHitTestResolution =
        resolveNativeTapTargetByHitTest(
            root = root,
            positionInWindowPoints = AxPoint(xPoints.toFloat(), yPoints.toFloat()),
            positionInWindowPx = AxPoint(xPoints.toFloat() * scale, yPoints.toFloat() * scale),
        )

    private fun assertTarget(expected: String, actual: NativeHitTestResolution, message: String? = null) {
        assertEquals(NativeHitTestResolution.Target(expected), actual, message)
    }

    /** Registries are process-global, so a leaked entry would leak into the next test. */
    private val registeredHosts = mutableListOf<UIView>()
    private val registeredIgnores = mutableListOf<AutographIgnoredViewRegistration>()
    private val registeredBounds = mutableListOf<AutographIgnoredBoundsRegistration>()

    @AfterTest
    fun clearRegistries() {
        registeredHosts.forEach { AutographComposeHosts.unregister(it) }
        registeredHosts.clear()
        registeredIgnores.forEach { it.unregister() }
        registeredIgnores.clear()
        registeredBounds.forEach { it.unregister() }
        registeredBounds.clear()
    }

    private fun registerHost(view: UIView) {
        AutographComposeHosts.register(view)
        registeredHosts += view
    }

    private fun registerIgnored(view: UIView) {
        registeredIgnores += registerAutographIgnoredView(view)
    }

    private fun registerIgnoredBounds(left: Float, top: Float, right: Float, bottom: Float) {
        registeredBounds += registerAutographIgnoredBounds().apply { update(left, top, right, bottom) }
    }

    // --- the cold-process case this resolver exists for ---

    /**
     * The whole of #189 in one test: a `UIControl` with an `accessibilityIdentifier` and **nothing
     * accessibility-related populated**, which is what a physical device was measured to look like
     * before any accessibility client has run. The `hitTest` route names it; the accessibility route,
     * asked about the identical tree, finds nothing at all.
     *
     * The second assertion is the non-vacuity guard. Without it this test would keep passing if the
     * fixture drifted into publishing accessibility state, and would then no longer be about coldness.
     */
    @Test
    fun resolvesAControlInATreeWithNoAccessibilityStateAtAll() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button"))

        assertTarget("share_button", resolve(root, 15.0, 15.0))
        assertNull(
            resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale),
            "precondition: this tree is cold, so the accessibility resolver must find nothing in it",
        )
    }

    /** The other form of interactivity: a plain view made tappable by a gesture recognizer. */
    @Test
    fun resolvesAPlainViewCarryingATapRecognizer() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(view(10.0, 10.0, 20.0, 20.0).withTapRecognizer().identified("tappable_card"))

        assertTarget("tappable_card", resolve(root, 15.0, 15.0))
    }

    /** Mirrors [NativeTapResolutionTest.attributesToTheInnermostClickable]; the two must agree here. */
    @Test
    fun attributesToTheInnermostInteractiveView() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val row = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("row")
        root.addSubview(row)
        row.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("inner_button"))

        assertTarget(
            "inner_button",
            resolve(root, 15.0, 15.0),
            "a button inside a tappable row must attribute to the button",
        )
    }

    /**
     * The hit view is usually not the interactive one: a `UIButton`'s label, an icon inside a tappable
     * card. The chain walk upward is what makes those resolve rather than drop.
     */
    @Test
    fun walksUpToTheNearestInteractiveAncestorWhenTheHitViewIsInert() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val button = controlAt(10.0, 10.0, 40.0, 40.0).identified("share_button")
        root.addSubview(button)
        // An inert, unidentified child covering the tap — a label or icon inside the control.
        button.addSubview(view(5.0, 5.0, 10.0, 10.0))

        assertTarget("share_button", resolve(root, 20.0, 20.0))
    }

    /**
     * The z-order property the accessibility route measurably lacks (#140): `hitTest` returns the view
     * actually drawn on top, because UIKit resolved the overlap itself.
     */
    @Test
    fun resolvesAnOverlapToTheViewDrawnOnTop() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 40.0, 40.0).identified("underneath"))
        root.addSubview(controlAt(10.0, 10.0, 40.0, 40.0).identified("on_top"))

        assertTarget("on_top", resolve(root, 20.0, 20.0))
    }

    /**
     * The other signal the accessibility tree does not carry at all. A control with
     * `isUserInteractionEnabled = false` receives no touches, so a tap over it belongs to whatever is
     * behind — and `hitTest` applies that for free.
     */
    @Test
    fun looksPastAViewThatDeclinesTouches() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 40.0, 40.0).identified("underneath"))
        root.addSubview(
            controlAt(10.0, 10.0, 40.0, 40.0).identified("decorative_overlay")
                .also { it.userInteractionEnabled = false },
        )

        assertTarget("underneath", resolve(root, 20.0, 20.0))
    }

    // --- the root is never a target ---

    /**
     * In production [root] is the `UIWindow` this capture attached its own `UITapGestureRecognizer` to,
     * so it is interactive by Autograph's own doing — and UIKit pre-populates a window with recognizers
     * besides. An identified window would otherwise claim every tap on screen under one name.
     */
    @Test
    fun neverResolvesToTheRootItself() {
        val root = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("main_window")

        assertSame(
            NativeHitTestResolution.Unresolved,
            resolve(root, 15.0, 15.0),
            "the view the tap was observed on is not an element the user tapped",
        )
    }

    // --- vetoes: terminal, never fall through ---

    @Test
    fun dropsATapWhoseHitChainCrossesAComposeHost() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val composeHost = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("compose_button"))

        assertTarget(
            "compose_button",
            resolve(root, 15.0, 15.0),
            "precondition: without registration this resolver does name the control",
        )

        registerHost(composeHost)

        assertSame(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "content under a Compose host belongs to the Compose pipeline exclusively",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheComposeHost() {
        val root = view(0.0, 0.0, 200.0, 100.0)
        val composeHost = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        root.addSubview(controlAt(120.0, 10.0, 20.0, 20.0).identified("native_button"))
        registerHost(composeHost)

        assertTarget(
            "native_button",
            resolve(root, 125.0, 15.0),
            "registering a Compose host must not deafen the native pipeline everywhere else",
        )
    }

    @Test
    fun dropsATapWhoseHitChainCrossesADeveloperExcludedView() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("private_button"))

        assertTarget("private_button", resolve(root, 15.0, 15.0), "precondition")

        registerIgnored(excluded)

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }

    /**
     * SwiftUI's `.autographIgnore()` excludes by window *region*, since a SwiftUI view has no UIView to
     * register. The veto has to hold on this route too — a SwiftUI ignored region can perfectly well sit
     * over UIKit content, and this route no longer passes such a tap to the resolver that used to be
     * the only one applying it.
     */
    @Test
    fun dropsATapInsideAnIgnoredRegionBeforeHitTestingAnything() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button"))

        assertTarget("share_button", resolve(root, 15.0, 15.0), "precondition")

        registerIgnoredBounds(0f, 0f, 50f * scale, 50f * scale)

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))

        registeredBounds.forEach { it.clear() }

        assertTarget(
            "share_button",
            resolve(root, 15.0, 15.0),
            "clearing the region revives the tap — the veto is the region's, not the view's",
        )
    }

    /**
     * [AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX] is reserved unconditionally, in every resolver — an internal
     * marker must never reach an app's analytics as an element name.
     */
    @Test
    fun neverReportsAReservedIdentifierAsANativeTarget() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(
            controlAt(10.0, 10.0, 20.0, 20.0)
                .identified("${AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX}{\"article_id\":\"42\"}"),
        )

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }

    // --- disabled controls: `hitTest` applies the veto, so this resolver has none ---

    /**
     * The measurement that removed the disabled veto from this resolver, in the shape that makes the
     * consequence visible. `UIControl.isEnabled = false` is declined by hit-testing entirely and the
     * touch **passes through to the view drawn behind it** — measured for a bare `UIControl`, a
     * `UISwitch` and a `UIButton` alike. So the tap resolves to what actually received it.
     *
     * This is the opposite of what [isAccessibilityDisabled] assumed about UIKit before #189, and the
     * opposite of what #128 measured for Compose, where a disabled clickable swallows the tap.
     */
    @Test
    fun looksPastADisabledControlToWhateverActuallyReceivesTheTouch() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("card_behind"))
        root.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("disabled_button").also { it.enabled = false })

        assertTarget("card_behind", resolve(root, 15.0, 15.0))
    }

    /**
     * The same fact seen through nesting: the container is what UIKit delivers the touch to, so naming
     * it is correct rather than a misattribution. [resolveNativeTapTarget] drops this shape, which is
     * a genuine divergence between the two resolvers and the right way round — this one observed what
     * UIKit did, the other inferred it from a trait.
     */
    @Test
    fun namesTheInteractiveContainerOfADisabledControl() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val row = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("enabled_row")
        root.addSubview(row)
        row.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("disabled_child").also { it.enabled = false })

        assertTarget("enabled_row", resolve(root, 15.0, 15.0))
    }

    /**
     * A disabled control's subtree is declined too, so an enabled control nested inside one is
     * untappable and nothing runs. Reporting nothing is therefore right — and this is why the veto's
     * absence costs no phantom event.
     */
    @Test
    fun declinesAnEnabledControlNestedInsideADisabledOne() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val disabledRow = controlAt(0.0, 0.0, 100.0, 100.0).identified("disabled_row").also { it.enabled = false }
        root.addSubview(disabledRow)
        disabledRow.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("enabled_child"))

        assertSame(NativeHitTestResolution.Unresolved, resolve(root, 15.0, 15.0))
    }

    /**
     * A disabled control alone over inert background falls through rather than dropping, so
     * [resolveNativeTapTarget]'s `UIAccessibilityTraitNotEnabled` veto still gets its say on a warm
     * tree. Fall-through, not a drop, is what keeps the two resolvers composable here.
     */
    @Test
    fun fallsThroughWhenTheOnlyThingUnderTheTapIsADisabledControl() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(
            controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button").also { it.enabled = false },
        )

        assertSame(NativeHitTestResolution.Unresolved, resolve(root, 15.0, 15.0))
    }

    /**
     * A view whose only recognizer is disabled runs nothing *and* does not consume the touch, so the
     * search carries on upward rather than stopping — the asymmetry with a disabled `UIControl` that
     * [isNativeInteractive] documents. Fixture reversed from the usual nesting so the disabled
     * recognizer sits *below* the answer.
     */
    @Test
    fun looksPastAViewWhoseOnlyTapRecognizerIsDisabled() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("card")
        root.addSubview(card)
        card.addSubview(
            view(10.0, 10.0, 20.0, 20.0).withTapRecognizer(recognizerEnabled = false).identified("inert_badge"),
        )

        assertTarget("card", resolve(root, 15.0, 15.0))
    }

    // --- fall-through: not a drop ---

    /**
     * **The load-bearing one.** A warm SwiftUI screen lands exactly here — hosting views carrying
     * recognizers and no identifier — so a drop would silently disable the SwiftUI half of native
     * capture, which has no other route. It must fall through to the accessibility resolver.
     */
    @Test
    fun fallsThroughWhenTheInteractiveViewCarriesNoIdentifier() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 20.0, 20.0))

        assertSame(NativeHitTestResolution.Unresolved, resolve(root, 15.0, 15.0))
    }

    /**
     * The regression this exclusion exists for, reduced from the sample app's XCUITest suite. A
     * SwiftUI `List` is backed by a real `UICollectionView` that carries the `.accessibilityIdentifier`
     * applied to the `List`, while its rows have no backing view at all — so an interactive scroll view
     * would shadow its own content and claim every tap inside it. Measured before the exclusion: a tap
     * on `native_row_2` reported `native_list`, where the accessibility resolver named the row.
     *
     * Falling through is what lets the accessibility route — the only one that can see SwiftUI rows —
     * resolve it as it always did.
     */
    @Test
    fun neverTreatsAScrollViewAsTheTappedElement() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val list = UIScrollView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0)).identified("native_list")
        list.withTapRecognizer()
        root.addSubview(list)
        // A row with no backing identity of its own, exactly as a SwiftUI List row has none.
        list.addSubview(view(10.0, 10.0, 20.0, 20.0))

        assertSame(
            NativeHitTestResolution.Unresolved,
            resolve(root, 15.0, 15.0),
            "an identified scroll view must not claim a tap that landed on its content",
        )
    }

    /** A tap on inert background: `hitTest` answers, nothing on the chain is interactive. */
    @Test
    fun fallsThroughWhenNothingOnTheChainIsInteractive() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(view(10.0, 10.0, 20.0, 20.0).identified("just_a_label"))

        assertSame(
            NativeHitTestResolution.Unresolved,
            resolve(root, 15.0, 15.0),
            "an identifier alone is not interactivity",
        )
    }

    /** `hitTest` declines a point outside the root's bounds. */
    @Test
    fun fallsThroughWhenTheTapMissesEverything() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button"))

        assertSame(NativeHitTestResolution.Unresolved, resolve(root, 500.0, 500.0))
    }

    /**
     * The whole reason the accessibility suites still test what they always did: their fixtures set
     * only `accessibilityFrame`, so every view has zero real bounds and `hitTest` declines the tap.
     * Asserted rather than assumed, because if it ever stopped holding those suites would quietly
     * start measuring this resolver instead.
     */
    @Test
    fun declinesATreeThatHasFramesOnlyInAccessibilitySpace() {
        val root = UIView()
        root.setAccessibilityFrame(CGRectMake(0.0, 0.0, 100.0, 100.0))
        val button = UIView()
        button.setAccessibilityFrame(CGRectMake(10.0, 10.0, 20.0, 20.0))
        root.addSubview(button.identified("share_button"))

        assertSame(NativeHitTestResolution.Unresolved, resolve(root, 15.0, 15.0))
    }

    // --- the interop trap ---

    /**
     * Regression test for the trap that made #74's first cycle guard inert, in the one place this file
     * depends on it. `hitTest` hands back a *different Kotlin wrapper* than the caller's `root`
     * reference, so a chain walk terminating on `===` would never recognise the root, return empty for
     * every tap, and leave this resolver permanently inert behind a green suite.
     *
     * Sourced through the interop boundary rather than reusing the Kotlin reference: a Kotlin
     * round-trip preserves identity and would pass either way.
     */
    @Test
    fun terminatesTheChainAtTheRootDespiteDistinctInteropWrappers() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val button = controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button")
        root.addSubview(button)

        val rootViaHitTest = generateSequence(root.subviews.first() as UIView) { it.superview }.last()
        assertTrue(
            root !== rootViaHitTest,
            "precondition: this test is only meaningful while Kotlin/Native hands out distinct wrappers",
        )

        assertTarget("share_button", resolve(rootViaHitTest, 15.0, 15.0))
    }
}
