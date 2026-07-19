package dev.ynagai.autograph.uikit

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityFrame
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.UIKit.setAccessibilityTraits
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [resolveNativeTapTarget] and [AutographComposeHosts] against real [UIView] trees — the same
 * approach as [AccessibilityTreeTest], and for the same reason: these helpers only ever consult
 * UIKit accessibility APIs, so a hand-built tree exercises the production logic exactly.
 *
 * Frames are set in points and tap positions given in pixels, matching real callers.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class NativeTapResolutionTest {

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    private fun button(id: String?, x: Double, y: Double, w: Double, h: Double): UIView =
        UIView().apply {
            setPointFrame(x, y, w, h)
            setAccessibilityTraits(UIAccessibilityTraitButton)
            id?.let { (this as NSObject).setValue(it, forKey = "accessibilityIdentifier") }
        }

    /** The registry is process-global, so a leaked entry would leak between tests. */
    @AfterTest
    fun clearRegistry() {
        registered.forEach { AutographComposeHosts.unregister(it) }
        registered.clear()
    }

    private val registered = mutableListOf<UIView>()

    private fun registerHost(view: UIView) {
        AutographComposeHosts.register(view)
        registered += view
    }

    @Test
    fun resolvesTheAccessibilityIdentifierOfTheTappedButton() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button("share_button", 10.0, 10.0, 20.0, 20.0))

        val target = resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale)

        assertEquals("share_button", target)
    }

    @Test
    fun attributesToTheInnermostClickable() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val row = button("row", 0.0, 0.0, 100.0, 100.0)
        root.addSubview(row)
        row.addSubview(button("inner_button", 10.0, 10.0, 20.0, 20.0))

        val target = resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale)

        assertEquals("inner_button", target, "a button inside a tappable row must attribute to the button")
    }

    @Test
    fun dropsATapOnSomethingWithNoButtonTrait() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val label = UIView()
        label.setPointFrame(10.0, 10.0, 20.0, 20.0)
        (label as NSObject).setValue("just_a_label", forKey = "accessibilityIdentifier")
        root.addSubview(label)

        val target = resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale)

        assertNull(target, "clickability is the button trait; an identifier alone must not qualify")
    }

    /**
     * The privacy guarantee: identification never falls back to `accessibilityLabel`, which is
     * user-facing display text. A clickable element without a developer-set identifier has no
     * stable name, and is dropped rather than named after what it says on screen.
     */
    @Test
    fun dropsAClickableThatCarriesNoIdentifier() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button(id = null, x = 10.0, y = 10.0, w = 20.0, h = 20.0))

        val target = resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale)

        assertNull(target)
    }

    @Test
    fun dropsATapThatMissesEverything() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(button("share_button", 10.0, 10.0, 20.0, 20.0))

        val target = resolveNativeTapTarget(root, AxPoint(500f * scale, 500f * scale), scale)

        assertNull(target)
    }

    // --- de-dup against the Compose pipeline ---

    /**
     * The de-dup boundary. Both pipelines walk the same tree, so without this the native side would
     * report every Compose tap a second time.
     *
     * The host is registered through the Kotlin reference but the walk reaches it via `subviews`,
     * which is the arrangement that matters: see [registeredHostMatchesEvenWhenReachedThroughSubviews].
     */
    @Test
    fun dropsATapWhoseHitPathCrossesAComposeHost() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(button("compose_button", 10.0, 10.0, 20.0, 20.0))

        val position = AxPoint(15f * scale, 15f * scale)
        assertEquals(
            "compose_button",
            resolveNativeTapTarget(root, position, scale),
            "precondition: without registration the native pipeline does resolve this tap",
        )

        registerHost(composeHost)

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "content under a Compose host belongs to the Compose pipeline exclusively",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheComposeHost() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 200.0, 100.0)
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        root.addSubview(button("native_button", 120.0, 10.0, 20.0, 20.0))

        registerHost(composeHost)

        assertEquals(
            "native_button",
            resolveNativeTapTarget(root, AxPoint(125f * scale, 15f * scale), scale),
            "registering a Compose host must not deafen the native pipeline everywhere else",
        )
    }

    @Test
    fun unregisteringAHostRestoresNativeResolution() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        registerHost(composeHost)
        assertNull(resolveNativeTapTarget(root, position, scale))

        AutographComposeHosts.unregister(composeHost)
        assertEquals("b", resolveNativeTapTarget(root, position, scale))
    }

    /**
     * One host, two registrations: the first release must not disarm the boundary the second still
     * needs.
     *
     * `AutographProvider` registers `LocalUIView`, and every provider under one `ComposeUIViewController`
     * reads the *same* host view. Two of them therefore register the same view — nested providers, or
     * simply two navigation destinations that each wrap their content while the outgoing one is still
     * composed during a transition. If the registry forgot the view on the first `unregister`, the
     * survivor would be left unprotected with nothing to re-register it, and the native walk would
     * descend into live Compose content: the exact privacy leak the boundary exists to prevent, back
     * again and silent.
     */
    @Test
    fun aHostStaysRegisteredUntilEveryRegistrationIsReleased() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        composeHost.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        registerHost(composeHost)
        registerHost(composeHost)

        AutographComposeHosts.unregister(composeHost)
        registered.remove(composeHost)

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "a host released by one of its two registrations must stay owned by the one still holding it",
        )
    }

    /**
     * Regression test for the trap that made #74's first cycle guard inert.
     *
     * Kotlin/Native does not canonicalize Objective-C wrappers, so the host view fetched back
     * through `subviews` is a *different Kotlin object* than the one handed to [register] — measured
     * here, not assumed. A registry compared with `===` (or backed by a Kotlin `Set`) calls those
     * two different and matches nothing, so de-dup silently never fires and every Compose tap is
     * double-counted. What this pins is that the registry compares the *underlying object* rather
     * than the Kotlin wrapper — the distinction between `===` and everything else, which is where
     * the bug actually lives. It does not, and cannot cheaply, distinguish the table's pointer
     * personality from the default `isEqual:` one: on a plain [UIView] those agree by construction.
     * See [AutographComposeHosts]'s backing table for why the stricter of the two is asked for.
     *
     * Deliberately sources the lookup object from `subviews` rather than reusing the Kotlin
     * reference: a Kotlin round-trip preserves identity and would pass either way — a false
     * negative that hides exactly this bug.
     */
    @Test
    fun registeredHostMatchesEvenWhenReachedThroughSubviews() {
        val root = UIView()
        val composeHost = UIView()
        root.addSubview(composeHost)

        val hostViaSubviews = root.subviews.first() as UIView
        assertTrue(
            composeHost !== hostViaSubviews,
            "precondition: this test is only meaningful while Kotlin/Native hands out distinct wrappers",
        )

        registerHost(composeHost)

        assertTrue(
            AutographComposeHosts.containsAny(listOf(hostViaSubviews)),
            "the registry must match the underlying view, not the Kotlin wrapper it arrived in",
        )
    }

    /**
     * Ownership must not depend on which branch the walk's clickable preference chose.
     *
     * A Compose host covering the tap whose content there carries no button trait — inert Compose
     * background, or anything the bridge exposes without the trait — is a branch the preference
     * declines, so a naive `containsAny(path)` is handed a path that never touches the host and lets
     * this pipeline claim a tap that landed on Compose-owned content. Measured, not hypothetical: this
     * asserted `native-button` before [resolveNativeTapTarget] started asking the topmost walk.
     */
    @Test
    fun dropsATapOnAComposeHostEvenWhenAClickableSitsBeneathIt() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val nativeContent = UIView()
        nativeContent.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(nativeContent)
        nativeContent.addSubview(button("native-button", 10.0, 10.0, 20.0, 20.0))

        // On top of it, and Compose-owned — with nothing clickable at the tap position.
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(composeHost)
        val composeInert = UIView()
        composeInert.setPointFrame(0.0, 0.0, 100.0, 100.0)
        composeHost.addSubview(composeInert)
        registerHost(composeHost)

        val position = AxPoint(15f * scale, 15f * scale)

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "a tap landing on Compose-owned content belongs to the Compose pipeline, not this one",
        )
    }
}
