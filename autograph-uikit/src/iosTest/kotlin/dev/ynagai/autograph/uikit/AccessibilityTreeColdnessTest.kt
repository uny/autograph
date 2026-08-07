package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIView
import platform.UIKit.setAccessibilityElements
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits

/**
 * Exercises [isAccessibilityTreeCold] against hand-built [UIView] trees — same approach as
 * [AccessibilityTreeTest], and for the same reason: it only ever consults UIKit accessibility APIs.
 *
 * A freshly constructed [UIView] with neither `setAccessibilityFrame` nor `setAccessibilityTraits`
 * called stands in for the measured cold-process state: `accessibilityFrame = CGRectZero`, traits
 * `UIAccessibilityTraitNone`. Any test wanting a "warm" node sets one of the two explicitly.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class AccessibilityTreeColdnessTest {

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    @Test
    fun aLoneUninstrumentedViewIsCold() {
        assertTrue(isAccessibilityTreeCold(UIView()))
    }

    @Test
    fun aTreeOfPlainSubviewsWithNoFrameOrTraitsIsCold() {
        val root = UIView()
        val child = UIView()
        val grandchild = UIView()
        root.addSubview(child)
        child.addSubview(grandchild)

        assertTrue(isAccessibilityTreeCold(root), "no node anywhere in the tree has a populated frame or traits")
    }

    @Test
    fun aNonZeroFrameAnywhereInTheTreeMeansNotCold() {
        val root = UIView()
        val child = UIView()
        root.addSubview(child)
        // Deep enough that it is not the root itself, matching the real shape: UIKit populates real
        // elements somewhere under the window, not necessarily on the window itself.
        val warmDescendant = UIView()
        warmDescendant.setPointFrame(10.0, 10.0, 20.0, 20.0)
        child.addSubview(warmDescendant)

        assertFalse(isAccessibilityTreeCold(root))
    }

    @Test
    fun traitsAloneWithAZeroFrameMeanNotCold() {
        val root = UIView()
        val labelled = UIView()
        labelled.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(labelled)

        assertFalse(
            isAccessibilityTreeCold(root),
            "traits are evidence of a populated tree even before a frame is; either alone must be enough",
        )
    }

    /**
     * A frame can be populated without having a size — a laid-out but zero-sized accessibility element
     * publishes exactly this. The predicate is four terms, and the three tests above all use a non-zero
     * *size*, so without this one `origin.x == 0.0 && origin.y == 0.0` could be deleted and every test
     * would still pass. "Either alone is enough" applies within the frame too.
     */
    @Test
    fun aPopulatedOriginWithZeroSizeMeansNotCold() {
        val root = UIView()
        val movedButEmpty = UIView()
        movedButEmpty.setPointFrame(10.0, 10.0, 0.0, 0.0)
        root.addSubview(movedButEmpty)

        assertFalse(isAccessibilityTreeCold(root), "a populated origin is evidence even with no size")
    }

    /**
     * A warm *root* over a cold subtree — the mirror of the tests above, and a distinct branch: the
     * walk checks a node's own traits/frame before descending, so this is the only shape that returns
     * on the node itself rather than on a descendant.
     */
    @Test
    fun aWarmRootOverAColdSubtreeIsNotCold() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(UIView())

        assertFalse(isAccessibilityTreeCold(root))
    }

    // --- Compose-host carve-out: CMP publishes real frames while the native half is cold ---

    /**
     * The hybrid-app case, and the reason the walk skips Compose hosts at all. A `ComposeUIViewController`
     * publishes real frames and traits in a process whose UIKit/SwiftUI half has never been warmed — this
     * walk itself activates CMP's bridge on demand (see `AccessibilityTree.kt`'s header, measured). Were
     * the host descended into, its warmth would answer "not cold" and suppress the one warning #170 exists
     * to emit, in exactly the app that needs it.
     */
    @Test
    fun aWarmComposeHostDoesNotMakeAColdNativeTreeLookWarm() {
        val root = UIView()
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val composeContent = UIView()
        composeContent.setAccessibilityTraits(UIAccessibilityTraitButton)
        composeHost.addSubview(composeContent)
        root.addSubview(composeHost)

        AutographComposeHosts.register(composeHost)
        try {
            assertTrue(
                isAccessibilityTreeCold(root),
                "Compose's own warmth says nothing about whether UIKit/SwiftUI built their tree",
            )
        } finally {
            AutographComposeHosts.unregister(composeHost)
        }
    }

    /** The carve-out must not swallow the rest of the tree: warmth outside the host still counts. */
    @Test
    fun aWarmNativeSiblingOfAComposeHostStillMeansNotCold() {
        val root = UIView()
        val composeHost = UIView()
        composeHost.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val nativeSibling = UIView()
        nativeSibling.setPointFrame(0.0, 0.0, 10.0, 10.0)
        root.addSubview(composeHost)
        root.addSubview(nativeSibling)

        AutographComposeHosts.register(composeHost)
        try {
            assertFalse(isAccessibilityTreeCold(root))
        } finally {
            AutographComposeHosts.unregister(composeHost)
        }
    }

    // --- Termination bounds. The other two walks over this tree each pin all three; so does this one,
    // and it needs them more: it is not position-gated, so it prunes less than either. ---

    /**
     * A cyclic tree must terminate rather than recurse until the stack goes. Note the failure mode here
     * is *not* contained by [AutographNativeTapCapture.report]'s `catch (e: Exception)` — a stack
     * overflow is an `Error` — so this guard is the only thing between a host-built cycle and a crash on
     * a tap.
     */
    @Test
    fun aCyclicTreeTerminates() {
        val root = UIView()
        val child = UIView()
        root.addSubview(child)
        child.setAccessibilityElements(listOf(root))

        val started = TimeSource.Monotonic.markNow()
        val cold = isAccessibilityTreeCold(root)

        assertTrue(cold, "an abandoned branch contributes no warmth, so an all-cold cycle is still cold")
        assertTrue(started.elapsedNow() < 5.seconds, "a cyclic tree must terminate, not spin")
    }

    /**
     * The depth ceiling, for a tree pathologically deep without being cyclic (so the ancestor check
     * cannot catch it). Same fixture shape as [AccessibilityTreeTest]'s `stopsDescendingAtTheDepthCeiling`.
     */
    @Test
    fun aPathologicallyDeepTreeTerminates() {
        val root = UIView()
        var deepest = root
        repeat(400) {
            val next = UIView()
            deepest.addSubview(next)
            deepest = next
        }

        val started = TimeSource.Monotonic.markNow()

        assertTrue(isAccessibilityTreeCold(root))
        assertTrue(started.elapsedNow() < 5.seconds)
    }

    /**
     * The breadth bound, which neither guard above gives. A branching DAG has no cycle — every
     * root-to-leaf path is made of distinct nodes — while the *number* of paths doubles per level, so
     * the visit budget is the only thing standing between this shape and minutes on the main thread
     * inside a tap handler. Wall-clock, because the failure mode is time and not a wrong answer: with
     * the budget this returns instantly, without it it is ~2^26 node visits.
     */
    @Test
    fun aBranchingDagTerminates() {
        val root = UIView()
        var level = listOf(root)
        repeat(26) {
            val left = UIView()
            val right = UIView()
            level.forEach { it.setAccessibilityElements(listOf(left, right)) }
            level = listOf(left, right)
        }

        val started = TimeSource.Monotonic.markNow()

        assertTrue(isAccessibilityTreeCold(root))
        assertTrue(started.elapsedNow() < 5.seconds, "the visit budget must bound this, not the depth ceiling")
    }

    /**
     * Pins the direction each bound resolves in, which is the one behavioural decision here that is not
     * forced — and it is the *opposite* of `anyAccessibilityDescendant`'s convention forty lines below it
     * in the same file, so "fixing" the inconsistency is a live risk. A truncated branch answers "cold",
     * never "warm": the alternative silently loses #170's warning in precisely the large or deep app most
     * likely to hit a bound.
     *
     * Built so the *only* warm node sits past the depth ceiling — if exhausting a bound answered `false`,
     * or if the truncated branch were treated as evidence, this would come back warm.
     */
    @Test
    fun warmthHiddenPastTheDepthCeilingAnswersColdNotWarm() {
        val root = UIView()
        var deepest = root
        repeat(400) {
            val next = UIView()
            deepest.addSubview(next)
            deepest = next
        }
        deepest.setPointFrame(0.0, 0.0, 100.0, 100.0)

        assertTrue(
            isAccessibilityTreeCold(root),
            "warmth the walk never reached must not be inferred; a truncated branch answers cold",
        )
    }
}
