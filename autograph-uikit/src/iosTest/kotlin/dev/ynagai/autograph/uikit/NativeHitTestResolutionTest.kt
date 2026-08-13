package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIButton
import platform.UIKit.UILabel
import platform.UIKit.UIScreen
import platform.UIKit.UIScrollView
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.accessibilityFrame
import platform.UIKit.accessibilityTraits
import platform.UIKit.setAccessibilityFrame
import platform.darwin.NSObject

/**
 * Drives [resolveNativeTapTargetByHitTest] against hand-built [UIView] trees.
 *
 * **These fixtures set real `frame`s and nothing else** — no `accessibilityFrame`, no traits, no
 * `isAccessibilityElement`. That is not a shortcut, it is the point: it reproduces the cold process
 * measured on a physical device in #189, where UIKit had built no accessibility tree at all.
 * [resolvesAControlInATreeWithNoAccessibilityStateAtAll] checks that coldness directly rather than
 * assuming it, so the suite would notice if a fixture drifted into publishing accessibility state.
 *
 * Since #191 this is the **only** native resolver, so these tests are the whole of native tap
 * resolution rather than one of two routes through it.
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
     * before any accessibility client has run. The `hitTest` route names it anyway.
     *
     * The second assertion is the non-vacuity guard, and it has to be here: without it this test would
     * keep passing if the fixture drifted into publishing accessibility state, and would then no longer
     * be about coldness at all. It pins the cold signature directly — zero traits and an empty
     * `accessibilityFrame`, exactly what was counted on the device — rather than by asking a second
     * resolver, which is how it was written while one existed (#191 removed it).
     */
    @Test
    fun resolvesAControlInATreeWithNoAccessibilityStateAtAll() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val control = controlAt(10.0, 10.0, 20.0, 20.0).identified("share_button")
        root.addSubview(control)

        assertTarget("share_button", resolve(root, 15.0, 15.0))

        val obj = control as NSObject
        assertEquals(0uL, obj.accessibilityTraits(), "precondition: a cold control publishes no traits")
        assertTrue(
            obj.accessibilityFrame().useContents {
                origin.x == 0.0 && origin.y == 0.0 && size.width == 0.0 && size.height == 0.0
            },
            "precondition: a cold control publishes an empty accessibilityFrame",
        )
    }

    /** The other form of interactivity: a plain view made tappable by a gesture recognizer. */
    @Test
    fun resolvesAPlainViewCarryingATapRecognizer() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(view(10.0, 10.0, 20.0, 20.0).withTapRecognizer().identified("tappable_card"))

        assertTarget("tappable_card", resolve(root, 15.0, 15.0))
    }

    /** A button inside a tappable row attributes to the button, not the row. */
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

    /**
     * The release half of the boundary, and the only test that asserts anything about
     * [AutographComposeHosts.unregister] at all. Its predecessor died with `NativeTapResolutionTest`
     * in #191, and nothing replaced it: every other Compose-host test only ever *registers*, and the
     * `@AfterTest` teardown unregisters without asserting, so making `unregister` a no-op left the whole
     * iOS suite green. In production `ComposeHostRegistration.ios.kt` unregisters on `onDispose`, so a
     * broken release means a dismissed `ComposeUIViewController`'s view stays registered for the life
     * of the process and every native tap over that screen region is silently [Dropped] forever.
     *
     * Covered on the shared [WeakViewRegistry] by the ignored-view suite, but not through *this*
     * boundary — and the two are deliberately separate registry instances, so the delegation is exactly
     * what could break without either suite noticing.
     */
    @Test
    fun unregisteringAComposeHostRestoresNativeResolution() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val composeHost = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("was_compose_button"))

        registerHost(composeHost)
        assertSame(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "precondition: while registered the tap is Compose-owned",
        )

        AutographComposeHosts.unregister(composeHost)
        registeredHosts.remove(composeHost)

        assertTarget(
            "was_compose_button",
            resolve(root, 15.0, 15.0),
            "once the host is released the view is ordinary native content again",
        )
    }

    /**
     * Ref-counting on the Compose-host boundary specifically: two registrations of one host — two
     * `AutographProvider`s under one `ComposeUIViewController`, or two nav destinations composed during
     * a transition — must both be released before native capture re-arms. Releasing one early would
     * expose live Compose content to the native pipeline, which is the privacy direction of this
     * boundary rather than the merely-noisy one.
     */
    @Test
    fun aComposeHostStaysRegisteredUntilEveryRegistrationIsReleased() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val composeHost = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(controlAt(10.0, 10.0, 20.0, 20.0).identified("compose_button"))

        registerHost(composeHost)
        registerHost(composeHost)

        AutographComposeHosts.unregister(composeHost)
        registeredHosts.remove(composeHost)

        assertSame(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "a host released by one of its two registrations stays Compose-owned",
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
     * it is correct rather than a misattribution. The accessibility resolver removed in #191 dropped
     * this shape instead, inferring inertness from a trait where this one observes what UIKit actually
     * did — the divergence was real, and this is the right way round.
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
     * A disabled control alone over inert background is [NativeHitTestResolution.Unresolved] rather
     * than [NativeHitTestResolution.Dropped]. Nothing ran, so nothing is reported either way; the
     * distinction is kept because only `Unresolved` may spend the one-per-process diagnostic, and a tap
     * that reached a disabled control is exactly the kind a developer would want explained.
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

    // --- unresolved: a miss, not a drop ---

    /**
     * An untagged control is a **miss**, not something the library was asked to withhold, so it must be
     * [NativeHitTestResolution.Unresolved] and not [NativeHitTestResolution.Dropped]. Nothing is
     * reported either way; the distinction is that only a miss may spend the one-per-process warning,
     * and a developer whose untagged control produced no event is exactly who that line is for.
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
     * That resolver is gone since #191, so the row is now not reported at all rather than rescued. The
     * exclusion still holds, because it chooses a drop over a misattribution — see
     * [isNativeInteractive]'s barrier section for the trade.
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

    /**
     * The half of the scroll-view rule that excluding the scroll view alone does **not** buy, and which
     * [neverTreatsAScrollViewAsTheTappedElement] cannot see because nothing interactive sits above its
     * list. Skipping the scroll view merely hands the shadowing to the next interactive ancestor: here a
     * screen container with an `accessibilityIdentifier` (routine for UI testing) and a
     * tap-to-dismiss-keyboard recognizer (an ordinary UIKit idiom). Measured before the barrier: a tap
     * on the row resolved to `login_screen`.
     *
     * The scroll view has to stop the search, not just decline to answer it.
     */
    @Test
    fun stopsTheSearchAtAScrollViewRatherThanLettingAnAncestorClaimTheTap() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val screen = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("login_screen")
        root.addSubview(screen)
        val list = UIScrollView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0)).identified("feed_list")
        screen.addSubview(list)
        list.addSubview(view(10.0, 10.0, 20.0, 20.0))

        assertSame(
            NativeHitTestResolution.Unresolved,
            resolve(root, 15.0, 15.0),
            "a container above a scroll view must not claim a tap the scroll view's content received",
        )
    }

    /**
     * The barrier must not cost a legitimate target *inside* the scroll view: a tappable identified card
     * in a scrolling list is the ordinary shape of any UIKit feed, and it sits below the barrier, so the
     * leaf-upward search reaches it before the scroll view is ever considered.
     */
    @Test
    fun stillResolvesAnInteractiveViewInsideAScrollView() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val list = UIScrollView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0)).identified("feed_list")
        root.addSubview(list)
        val card = view(0.0, 0.0, 100.0, 40.0).withTapRecognizer().identified("feed_card")
        list.addSubview(card)
        card.addSubview(view(10.0, 10.0, 20.0, 20.0))

        assertTarget("feed_card", resolve(root, 15.0, 15.0))
    }

    // --- the developer opt-out holds on this route too ---

    /**
     * `registerAutographIgnoredView` promises that "a tap whose hit path crosses [view] is not reported
     * at all". That hit path is the *visual* one; a `hitTest` chain is the *touch-delivery* path, and
     * the two diverge at a view that declines touches — which is the default for the two types an app is
     * most likely to exclude, `UILabel` and `UIImageView`.
     *
     * Measured on the tree below: before the fix the `hitTest` route reported `profile_card` — so the
     * opt-out stopped holding for the shape it is most often reached for.
     */
    @Test
    fun honoursAnOptOutOnAViewThatDeclinesTouches() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        // A label showing the user's email. `isUserInteractionEnabled` is false by default, so `hitTest`
        // returns the card and this view never appears on the delivery chain.
        val emailLabel = UILabel(frame = CGRectMake(5.0, 5.0, 50.0, 20.0)).identified("email_label")
        card.addSubview(emailLabel)

        assertTarget("profile_card", resolve(root, 15.0, 15.0), "precondition: reported before opting out")

        registerIgnored(emailLabel)

        assertSame(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "an excluded view under the tap must veto it even when it cannot receive the touch",
        )
        // Control arm: without it the drop above could equally mean "this fixture resolves to nothing
        // for an unrelated reason", and the claim would be unbacked. Releasing the registration must
        // bring the resolver back to naming the card.
        registeredIgnores.forEach { it.unregister() }
        registeredIgnores.clear()
        assertTarget(
            "profile_card",
            resolve(root, 15.0, 15.0),
            "non-vacuity: the drop above must be the opt-out's doing, not the fixture's",
        )
    }

    /**
     * The same divergence in its other shape: the excluded view is a *sibling drawn over* the tapped
     * view rather than nested inside it. `hitTest` declines it for the same reason and returns the card
     * behind, so a walk that only descended the hit view's own subtree would miss it — which is why the
     * veto follows the frontmost drawn branch from the root instead.
     */
    @Test
    fun honoursAnOptOutOnAViewDrawnOverTheTappedOne() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        // A sibling of the card, drawn on top of it, that cannot receive touches.
        val emailOverlay = UILabel(frame = CGRectMake(0.0, 0.0, 50.0, 50.0)).identified("email_overlay")
        root.addSubview(emailOverlay)

        assertTarget("profile_card", resolve(root, 15.0, 15.0), "precondition")

        registerIgnored(emailOverlay)

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }

    /**
     * The shape that defeats a frontmost-only walk. A clear-backgrounded full-size sibling — visible by
     * `alpha`, occluding nothing, declining touches — sits in front of the excluded label. `hitTest`
     * steps over it and returns the card, and a walk that always takes the frontmost subview commits to
     * the scrim and never reaches the label. The hit view's own subtree walk is what covers this.
     */
    @Test
    fun honoursAnOptOutBehindATransparentFrontSibling() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        val emailLabel = UILabel(frame = CGRectMake(5.0, 5.0, 50.0, 20.0)).identified("email_label")
        card.addSubview(emailLabel)
        // Added after the label, so it is in front of it, and touch-transparent so hitTest ignores it.
        card.addSubview(view(0.0, 0.0, 100.0, 100.0))

        assertTarget("profile_card", resolve(root, 15.0, 15.0), "precondition")

        registerIgnored(emailLabel)

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }

    /**
     * An excluded view drawn outside its own parent's bounds. A zero-sized container with an overhanging
     * child is an ordinary autolayout result, and `hitTest` refuses to enter such a parent at all — so
     * the subtree walk deliberately judges each view by whether *it* contains the point rather than
     * gating descent on its ancestors.
     */
    @Test
    fun honoursAnOptOutDrawnOutsideItsParentsBounds() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        // A zero-sized container: it contains no point at all, yet its child is drawn over the card.
        val badgeContainer = view(0.0, 0.0, 0.0, 0.0)
        card.addSubview(badgeContainer)
        badgeContainer.addSubview(UILabel(frame = CGRectMake(0.0, 0.0, 50.0, 20.0)).identified("email_label"))

        assertTarget("profile_card", resolve(root, 15.0, 15.0), "precondition")

        registerIgnored(badgeContainer.subviews.first() as UIView)

        assertSame(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))
    }

    /**
     * The other direction, and the reason the veto follows only the *frontmost* branch: an excluded view
     * sitting behind the opaque thing the user actually tapped is not what they touched, so it must not
     * veto. A sweep of everything under the point would drop this tap.
     */
    @Test
    fun anOptOutBehindTheTappedViewDoesNotVetoIt() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val hidden = UILabel(frame = CGRectMake(0.0, 0.0, 50.0, 50.0)).identified("behind_label")
        root.addSubview(hidden)
        root.addSubview(view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("cover_card"))
        registerIgnored(hidden)

        assertTarget(
            "cover_card",
            resolve(root, 15.0, 15.0),
            "an excluded view the user cannot see or reach must not veto the view drawn over it",
        )
    }

    /** An excluded view that is hidden is not drawn, so it is not what the tap landed on. */
    @Test
    fun aHiddenOptOutDoesNotVetoTheTapUnderIt() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        val errorLabel = UILabel(frame = CGRectMake(0.0, 0.0, 50.0, 50.0)).identified("error_label")
        errorLabel.hidden = true
        card.addSubview(errorLabel)
        registerIgnored(errorLabel)

        assertTarget(
            "profile_card",
            resolve(root, 15.0, 15.0),
            "a hidden excluded view must not silently swallow taps on the card it sits over",
        )
    }

    /** The opt-out is the excluded view's, not the whole card's: a tap clear of it still resolves. */
    @Test
    fun anOptOutOnlyVetoesTapsThatActuallyLandOnIt() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("profile_card")
        root.addSubview(card)
        val emailLabel = UILabel(frame = CGRectMake(5.0, 5.0, 20.0, 20.0)).identified("email_label")
        card.addSubview(emailLabel)
        registerIgnored(emailLabel)

        assertTarget(
            "profile_card",
            resolve(root, 80.0, 80.0),
            "a tap elsewhere on the card is not inside the excluded view and must still be reported",
        )
    }

    // --- a recognizer this tap could not have satisfied is not interactivity ---

    /**
     * The observer feeding this pipeline is a single-tap, single-touch recognizer, so a view whose only
     * recognizer needs two taps demonstrably ran nothing for the tap being resolved. Reporting it would
     * invent an event. The search carries on upward instead, exactly as for a disabled recognizer.
     */
    @Test
    fun looksPastAViewWhoseOnlyTapRecognizerNeedsTwoTaps() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("card")
        root.addSubview(card)
        val zoom = view(10.0, 10.0, 20.0, 20.0).identified("double_tap_only")
        zoom.addGestureRecognizer(UITapGestureRecognizer().also { it.numberOfTapsRequired = 2uL })
        card.addSubview(zoom)

        assertTarget("card", resolve(root, 15.0, 15.0))
    }

    /** The two-finger form of the same requirement. */
    @Test
    fun looksPastAViewWhoseOnlyTapRecognizerNeedsTwoFingers() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val card = view(0.0, 0.0, 100.0, 100.0).withTapRecognizer().identified("card")
        root.addSubview(card)
        val twoFinger = view(10.0, 10.0, 20.0, 20.0).identified("two_finger_only")
        twoFinger.addGestureRecognizer(UITapGestureRecognizer().also { it.numberOfTouchesRequired = 2uL })
        card.addSubview(twoFinger)

        assertTarget("card", resolve(root, 15.0, 15.0))
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
