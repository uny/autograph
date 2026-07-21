package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import platform.darwin.NSObject

/**
 * Drives the developer opt-out ([registerAutographIgnoredView]) through the real [resolveNativeTapTarget]
 * pipeline against hand-built [UIView] trees — the same approach and reasoning as [NativeTapResolutionTest].
 * Mirrors the Compose-host exclusion tests there, since the two boundaries share [WeakViewRegistry].
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class IgnoredViewRegistryTest {

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

    // The registry is process-global, so a leaked registration would leak between tests.
    private val registrations = mutableListOf<AutographIgnoredViewRegistration>()

    @AfterTest
    fun clearRegistry() {
        registrations.forEach { it.unregister() }
        registrations.clear()
    }

    private fun ignore(view: UIView): AutographIgnoredViewRegistration =
        registerAutographIgnoredView(view).also { registrations += it }

    @Test
    fun dropsATapWhoseHitPathCrossesAnExcludedView() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("secret_button", 10.0, 10.0, 20.0, 20.0))

        val position = AxPoint(15f * scale, 15f * scale)
        assertEquals(
            "secret_button",
            resolveNativeTapTarget(root, position, scale),
            "precondition: without the opt-out the native pipeline does resolve this tap",
        )

        ignore(excluded)

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "a tap under a registerAutographIgnoredView subtree must not be reported",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheExcludedView() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 200.0, 100.0)
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        root.addSubview(button("public_button", 120.0, 10.0, 20.0, 20.0))

        ignore(excluded)

        assertEquals(
            "public_button",
            resolveNativeTapTarget(root, AxPoint(125f * scale, 15f * scale), scale),
            "excluding one subtree must not deafen the native pipeline everywhere else",
        )
    }

    @Test
    fun unregisteringRestoresNativeResolution() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        val registration = ignore(excluded)
        assertNull(resolveNativeTapTarget(root, position, scale))

        registration.unregister()
        assertEquals("b", resolveNativeTapTarget(root, position, scale))
    }

    /** One view, two registrations: releasing the first must not re-arm capture the second still forbids. */
    @Test
    fun staysExcludedUntilEveryRegistrationIsReleased() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        val first = ignore(excluded)
        ignore(excluded)

        first.unregister()

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "a view released by one of its two registrations stays excluded by the one still holding it",
        )
    }

    /** `unregister()` is idempotent — a second call must not push the ref-count negative and re-arm. */
    @Test
    fun unregisterIsIdempotent() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))
        val position = AxPoint(15f * scale, 15f * scale)

        val outer = ignore(excluded)
        ignore(excluded)
        outer.unregister()
        outer.unregister() // extra release must be a no-op, not a second decrement

        assertNull(
            resolveNativeTapTarget(root, position, scale),
            "an unbalanced extra unregister must not disarm the registration still held",
        )
    }

    /**
     * Kotlin/Native hands out distinct wrappers for the same underlying view, so the view reached
     * through `subviews` during the walk is a different Kotlin object than the one registered. The
     * registry must match the underlying object, not the wrapper — sourced from `subviews`, since a
     * Kotlin round-trip would preserve identity and hide the bug. See [WeakViewRegistry].
     */
    @Test
    fun excludedViewMatchesEvenWhenReachedThroughSubviews() {
        val root = UIView()
        val excluded = UIView()
        root.addSubview(excluded)

        val viaSubviews = root.subviews.first() as UIView
        assertTrue(
            excluded !== viaSubviews,
            "precondition: only meaningful while Kotlin/Native hands out distinct wrappers",
        )

        ignore(excluded)

        assertTrue(
            AutographIgnoredViews.containsAny(listOf(viaSubviews)),
            "the registry must match the underlying view, not the Kotlin wrapper it arrived in",
        )
    }

    /**
     * The exclusion must hold even when the clickable-preference walk chooses a branch that never
     * touches the excluded view — the reason [resolveNativeTapTarget] also checks the topmost path.
     */
    @Test
    fun dropsATapOnAnExcludedViewEvenWhenAClickableSitsBeneathIt() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val nativeContent = UIView()
        nativeContent.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(nativeContent)
        nativeContent.addSubview(button("public-button", 10.0, 10.0, 20.0, 20.0))

        // On top, excluded, with nothing clickable at the tap position.
        val excluded = UIView()
        excluded.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        val inert = UIView()
        inert.setPointFrame(0.0, 0.0, 100.0, 100.0)
        excluded.addSubview(inert)
        ignore(excluded)

        assertNull(
            resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale),
            "a tap landing on an excluded subtree must be dropped even when a clickable sits beneath it",
        )
    }
}
