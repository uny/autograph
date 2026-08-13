package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.setValue
import platform.UIKit.UIButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Drives the developer opt-out ([registerAutographIgnoredView]) through the real
 * [resolveNativeTapTargetByHitTest] pipeline against hand-built [UIView] trees.
 *
 * **Scope split with [NativeHitTestResolutionTest].** That suite pins how the *resolver* honours an
 * excluded view — including the awkward geometries (a view that declines touches, one drawn over the
 * tapped view, one behind a transparent sibling, one overhanging its parent). This one pins the
 * *registry's lifecycle*: reference counting across two registrations, idempotent release, and matching
 * the underlying view rather than the Kotlin wrapper it arrived in, since [WeakViewRegistry] is shared
 * with the Compose-host boundary.
 *
 * Fixtures set **real frames**, because `hitTest` is what consults them.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class IgnoredViewRegistryTest {

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    private fun view(x: Double, y: Double, w: Double, h: Double): UIView =
        UIView(frame = CGRectMake(x, y, w, h))

    private fun button(id: String, x: Double, y: Double, w: Double, h: Double): UIView =
        UIButton(frame = CGRectMake(x, y, w, h))
            .apply { (this as NSObject).setValue(id, forKey = "accessibilityIdentifier") }

    private fun resolve(root: UIView, xPoints: Double, yPoints: Double): NativeHitTestResolution =
        resolveNativeTapTargetByHitTest(
            root = root,
            positionInWindowPoints = AxPoint(xPoints.toFloat(), yPoints.toFloat()),
            positionInWindowPx = AxPoint(xPoints.toFloat() * scale, yPoints.toFloat() * scale),
        )

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
        val root = view(0.0, 0.0, 100.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("secret_button", 10.0, 10.0, 20.0, 20.0))

        assertEquals(
            NativeHitTestResolution.Target("secret_button"),
            resolve(root, 15.0, 15.0),
            "precondition: without the opt-out the native pipeline does resolve this tap",
        )

        ignore(excluded)

        assertEquals(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "a tap under a registerAutographIgnoredView subtree must not be reported",
        )
    }

    @Test
    fun stillResolvesATapOutsideTheExcludedView() {
        val root = view(0.0, 0.0, 200.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        root.addSubview(button("public_button", 120.0, 10.0, 20.0, 20.0))

        ignore(excluded)

        assertEquals(
            NativeHitTestResolution.Target("public_button"),
            resolve(root, 125.0, 15.0),
            "excluding one subtree must not deafen the native pipeline everywhere else",
        )
    }

    @Test
    fun unregisteringRestoresNativeResolution() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val registration = ignore(excluded)
        assertEquals(NativeHitTestResolution.Dropped, resolve(root, 15.0, 15.0))

        registration.unregister()
        assertEquals(NativeHitTestResolution.Target("b"), resolve(root, 15.0, 15.0))
    }

    /** One view, two registrations: releasing the first must not re-arm capture the second still forbids. */
    @Test
    fun staysExcludedUntilEveryRegistrationIsReleased() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val first = ignore(excluded)
        ignore(excluded)

        first.unregister()

        assertEquals(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "a view released by one of its two registrations stays excluded by the one still holding it",
        )
    }

    /** `unregister()` is idempotent — a second call must not push the ref-count negative and re-arm. */
    @Test
    fun unregisterIsIdempotent() {
        val root = view(0.0, 0.0, 100.0, 100.0)
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(button("b", 10.0, 10.0, 20.0, 20.0))

        val outer = ignore(excluded)
        ignore(excluded)
        outer.unregister()
        outer.unregister() // extra release must be a no-op, not a second decrement

        assertEquals(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
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
     * The exclusion must hold when the excluded subtree is what the touch lands on but carries nothing
     * interactive, while an identified control sits in a sibling branch beneath it. Without the veto
     * the resolver would have no reason to stop at the excluded view.
     */
    @Test
    fun dropsATapOnAnExcludedViewEvenWhenAClickableSitsBeneathIt() {
        val root = view(0.0, 0.0, 100.0, 100.0)

        val nativeContent = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(nativeContent)
        nativeContent.addSubview(button("public_button", 10.0, 10.0, 20.0, 20.0))

        // On top, excluded, with nothing interactive at the tap position.
        val excluded = view(0.0, 0.0, 100.0, 100.0)
        root.addSubview(excluded)
        excluded.addSubview(view(0.0, 0.0, 100.0, 100.0))
        ignore(excluded)

        assertEquals(
            NativeHitTestResolution.Dropped,
            resolve(root, 15.0, 15.0),
            "a tap landing on an excluded subtree must be dropped even when a clickable sits beneath it",
        )
    }
}
