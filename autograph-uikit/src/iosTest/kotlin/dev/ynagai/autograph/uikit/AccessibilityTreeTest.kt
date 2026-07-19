package dev.ynagai.autograph.uikit

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.setAccessibilityElements
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import platform.UIKit.setFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Exercises the accessibility-tree walk ([deepestAccessibilityHitPath] / [accessibilityChildren] /
 * [accessibilityBoundsInWindowPx]) against genuine [UIView] instances. No UI framework is involved —
 * these helpers only ever consult UIKit accessibility APIs — and a hand-built [UIView] tree is the
 * only way to drive this logic in CI: `compose.uiTest`'s iOS scene never populates a real
 * accessibility tree (see autograph-compose's `PlatformAutocaptureTestHost.ios.kt`).
 *
 * `accessibilityFrame` is in points; the walk returns window-space pixels, so frames below are set in
 * points and tap positions given in pixels (point * [UIScreen.scale]), matching real callers.
 */
@OptIn(ExperimentalForeignApi::class, AutographInternalApi::class)
class AccessibilityTreeTest {

    private fun UIView.setPointFrame(x: Double, y: Double, width: Double, height: Double) {
        setAccessibilityFrame(CGRectMake(x, y, width, height))
    }

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    @Test
    fun findsTheNearestClickableAtTheTapPosition() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(button)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(listOf(root, button), path)
        assertTrue((path?.last() as? UIView)?.isAccessibilityButton() == true)
    }

    @Test
    fun returnsNullWhenThePositionMissesTheRoot() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val position = AxPoint(500f * scale, 500f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertNull(path)
    }

    @Test
    fun prefersTheLastChildWhenBoundsOverlap() {
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

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(front, path?.last())
    }

    /**
     * The shape that made every native tap resolve to null on a real SwiftUI `List`, measured on the
     * simulator (iPhone 17 Pro): a full-screen `_UITouchPassthroughView` sits on top of the collection
     * view and contains nothing, so the walk — which used to commit to the first branch containing the
     * point — stopped there and never reached the cells. Its name is the point: it passes touches
     * through, and the tap really does belong to what is underneath.
     *
     * Neither existing suite catches this. Hand-built trees like the ones above have no such overlay,
     * and the Compose pipeline's bridged tree doesn't either — the same blind spot as #77, where a
     * defect lived only in the shape real UI frameworks produce.
     */
    @Test
    fun descendsPastAnEmptyOverlayThatCoversTheClickable() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val content = UIView()
        content.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(content)

        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        content.addSubview(button)

        // Added last, so it is drawn on top and searched first — and it holds nothing at all.
        val passthroughOverlay = UIView()
        passthroughOverlay.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(passthroughOverlay)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(
            listOf(root, content, button),
            path,
            "expected the walk to back out of the empty on-top overlay and find the clickable beneath it",
        )
    }

    /**
     * The other half of the rule: preferring a clickable branch must not become "always take the
     * bottom branch". With no clickable anywhere the walk has nothing to prefer, so the topmost
     * branch still wins exactly as it did before — the pre-existing z-order tie-break is intact for
     * every caller that isn't asking about clickability.
     */
    @Test
    fun keepsThePreferenceForTheTopmostBranchWhenNoBranchIsClickable() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val back = UIView()
        back.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(back)

        val front = UIView()
        front.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(front)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(listOf(root, front), path)
    }

    /**
     * A clickable *deeper* in a lower branch still beats a shallower non-clickable one, so the
     * preference survives more than one level of backing out — the SwiftUI case above nests the cell
     * several levels below the collection view, not directly under it.
     */
    @Test
    fun backsOutOfAnEmptyBranchAcrossMultipleLevels() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val content = UIView()
        content.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(content)
        val cell = UIView()
        cell.setPointFrame(0.0, 0.0, 100.0, 100.0)
        content.addSubview(cell)
        val button = UIView()
        button.setPointFrame(10.0, 10.0, 20.0, 20.0)
        button.setAccessibilityTraits(UIAccessibilityTraitButton)
        cell.addSubview(button)

        // An on-top branch that is deep but leads nowhere clickable.
        val overlay = UIView()
        overlay.setPointFrame(0.0, 0.0, 100.0, 100.0)
        root.addSubview(overlay)
        val overlayInner = UIView()
        overlayInner.setPointFrame(0.0, 0.0, 100.0, 100.0)
        overlay.addSubview(overlayInner)

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertEquals(listOf(root, content, cell, button), path)
    }

    /**
     * The cost side of exploring every branch: a child that appears in *both* `accessibilityElements`
     * and `subviews` must be returned once, or the walk above pays for it 2^depth times.
     *
     * `accessibilityElements = subviews` is an ordinary thing for a view to do (it pins VoiceOver's
     * reading order), so this is the common shape, not an adversarial one.
     */
    @Test
    fun accessibilityChildrenReturnsAChildListedBothWaysOnlyOnce() {
        val root = UIView()
        val child = UIView()
        root.addSubview(child)
        root.setAccessibilityElements(listOf(child))

        val children = root.accessibilityChildren()

        assertEquals(1, children.size, "a child reached through both routes is still one child")
        assertTrue(children.single() == child)
    }

    /**
     * The breadth bound, which the depth ceiling does not give. Two parents per level sharing the same
     * two children is a DAG, not a cycle, so the cycle guard never fires and every one of the 2^depth
     * root-to-leaf *paths* would be walked. Nothing in the UIAccessibility contract forbids a host app
     * from building this, and unlike the duplicate above it cannot be deduplicated away.
     *
     * The assertion is a wall-clock bound because the failure mode is time, not a wrong answer: with
     * [MAX_ACCESSIBILITY_NODE_VISITS] the walk stops after ten thousand nodes and returns instantly;
     * without it this shape is ~2^26 frame conversions and takes minutes on the main thread. Four
     * orders of magnitude separate the two, so the threshold is not a flake risk.
     */
    @Test
    fun boundsTotalWorkWhenABranchingDagWouldExplode() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        var level = listOf<UIView>(root)
        repeat(26) {
            val left = UIView()
            left.setPointFrame(0.0, 0.0, 100.0, 100.0)
            val right = UIView()
            right.setPointFrame(0.0, 0.0, 100.0, 100.0)
            // Every parent on this level points at BOTH of the next level's nodes: the node count
            // stays linear while the path count doubles.
            level.forEach { it.setAccessibilityElements(listOf(left, right)) }
            level = listOf(left, right)
        }

        val position = AxPoint(15f * scale, 15f * scale)
        val started = TimeSource.Monotonic.markNow()
        val path = deepestAccessibilityHitPath(root, root, position, scale)
        val elapsed = started.elapsedNow()

        assertTrue(path != null && path.isNotEmpty())
        assertTrue(
            elapsed < 5.seconds,
            "expected the visit budget to bound the walk, but it took $elapsed",
        )
    }

    @Test
    fun accessibilityBoundsScalesPointsToPixels() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)
        val node = UIView()
        node.setPointFrame(2.0, 3.0, 4.0, 5.0)
        root.addSubview(node)

        val bounds = node.accessibilityBoundsInWindowPx(root, scale)

        assertTrue(bounds != null)
        assertEquals(2f * scale, bounds.left, 0.01f)
        assertEquals(3f * scale, bounds.top, 0.01f)
        assertEquals((2f + 4f) * scale, bounds.right, 0.01f)
        assertEquals((3f + 5f) * scale, bounds.bottom, 0.01f)
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
            "expected accessibilityChildren to include accessibilityElements()-only descendants — this is the entire reason it exists (Compose's AX root can be reachable only via accessibilityElements(), not subviews, per AccessibilityTree.kt's leading comment)",
        )
    }

    /**
     * [accessibilityBoundsScalesPointsToPixels] never attaches a window, so
     * `convertRect(_:fromCoordinateSpace:)` has no non-trivial screen→window offset to apply and that
     * test alone can't distinguish a correct window-relative conversion from a (wrong) root-local one.
     * This attaches `root` to a real [UIWindow] at a non-origin position so the conversion actually
     * exercises a screen→window translation, not just the scale multiply — and, critically, so the
     * result does NOT depend on root's own offset within the window (verifying the fix for the bug
     * below), only on the window's.
     *
     * On-device (see [AxRect]'s kdoc for the full account): converting to *root*-local space here used
     * to silently subtract root's own offset within its window — an offset the caller's tap position
     * was never adjusted for, since it's already window-relative. That mismatch is invisible whenever
     * root fills its window (root's offset is zero), which is why it went undetected until
     * `ComposeUIViewController` was embedded in a safe-area-respecting SwiftUI container (which
     * shrinks/repositions the Compose root to fit the safe content area) — every tap then
     * misattributed by roughly the safe-area inset.
     */
    @Test
    fun accessibilityBoundsConvertsToTheWindowsSpaceNotTheRootsOwnOffsetSpace() {
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

        val bounds = node.accessibilityBoundsInWindowPx(root, scale)

        assertTrue(bounds != null)
        // Window-relative (52, 63, 4, 5) — NOT root-local (2, 3, 4, 5), i.e. NOT adjusted for
        // root's (50, 60) offset within the window.
        assertEquals(52f * scale, bounds.left, 0.5f)
        assertEquals(63f * scale, bounds.top, 0.5f)
        assertEquals((52f + 4f) * scale, bounds.right, 0.5f)
        assertEquals((63f + 5f) * scale, bounds.bottom, 0.5f)
    }

    /**
     * The walked tree comes from the host app, and nothing stops an element from listing an ancestor
     * among its `accessibilityElements` — a cycle. Before the path-identity check this recursed until
     * the stack overflowed, i.e. any app with such a link crashed on its first tap. The walk must
     * abandon the cyclic branch and still resolve, not hang or die.
     */
    @Test
    fun terminatesWhenAnElementLinksBackToItsAncestor() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val child = UIView()
        child.setPointFrame(0.0, 0.0, 100.0, 100.0)
        child.setAccessibilityTraits(UIAccessibilityTraitButton)
        root.addSubview(child)
        // The cycle: child points back at its own ancestor.
        child.setAccessibilityElements(listOf(root))

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        // Resolves to the deepest non-cyclic node rather than recursing forever.
        assertEquals(child, path?.last())
    }

    /**
     * The cycle above closes over an `accessibilityElements` link, which is the one edge that happens
     * to hand back the *same* Kotlin wrapper each time — so it passes even under an identity (`===`)
     * check. This one closes the cycle over an ancestor the walk reached through `subviews`, where
     * Kotlin/Native hands back a fresh wrapper per fetch (`v.subviews.first() === v.subviews.first()`
     * is false). Under `===` the walk re-entered the cycle and returned a 5-long path with `mid` and
     * `leaf` each visited twice; the guard has to compare with `==` to catch it.
     */
    @Test
    fun terminatesWhenTheCycleClosesOverASubviewReachedAncestor() {
        val outer = UIView()
        outer.setPointFrame(0.0, 0.0, 100.0, 100.0)

        val mid = UIView()
        mid.setPointFrame(0.0, 0.0, 100.0, 100.0)
        outer.addSubview(mid)

        val leaf = UIView()
        leaf.setPointFrame(0.0, 0.0, 100.0, 100.0)
        mid.addSubview(leaf)

        // The cycle: leaf points back at mid, which the walk reached via `subviews`.
        leaf.setAccessibilityElements(listOf(mid))

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(outer, outer, position, scale)

        // Each node exactly once — not a second lap around the cycle.
        assertEquals(listOf(outer, mid, leaf), path)
    }

    /**
     * Backstop for a tree that is pathologically deep without being cyclic (so the identity check
     * can't catch it). Nests past [MAX_ACCESSIBILITY_TREE_DEPTH] and asserts the walk returns instead
     * of overflowing; the resolved node is necessarily shallower than the true leaf, which is the
     * intended trade — a truncated path loses one event, an overflow loses the app.
     */
    @Test
    fun stopsDescendingAtTheDepthCeiling() {
        val root = UIView()
        root.setPointFrame(0.0, 0.0, 100.0, 100.0)

        var deepest = root
        repeat(400) {
            val next = UIView()
            next.setPointFrame(0.0, 0.0, 100.0, 100.0)
            deepest.addSubview(next)
            deepest = next
        }

        val position = AxPoint(15f * scale, 15f * scale)
        val path = deepestAccessibilityHitPath(root, root, position, scale)

        assertTrue(path != null)
        assertTrue(
            path.size <= 256,
            "expected the walk to stop at the depth ceiling, but it descended ${path.size} levels",
        )
    }

    /**
     * The attribution rule both iOS pipelines share: leaf-upward, so a button inside a tappable row
     * resolves to the button. Pinned here because the two pipelines used to hold their own copy of
     * this search, and the promise that they agree was enforced by nothing but the copies being
     * textually identical (#79).
     */
    @Test
    fun nearestClickablePicksTheInnermostOne() {
        val outer = UIView()
        outer.setAccessibilityTraits(UIAccessibilityTraitButton)
        val middle = UIView()
        val inner = UIView()
        inner.setAccessibilityTraits(UIAccessibilityTraitButton)

        // Root-to-leaf, as deepestAccessibilityHitPath returns it.
        val path = listOf<Any>(outer, middle, inner)

        assertEquals(inner, path.nearestAccessibilityClickable())
    }

    @Test
    fun nearestClickableIsNullWhenNothingOnThePathIsClickable() {
        val path = listOf<Any>(UIView(), UIView())

        assertNull(path.nearestAccessibilityClickable())
    }

    @Test
    fun containsIsLeftTopInclusiveAndRightBottomExclusive() {
        // Pins the boundary semantics against Compose's Rect.contains, which the Compose adapter's
        // tap positions are produced by and which this walk must not silently diverge from.
        val rect = AxRect(10f, 20f, 30f, 40f)

        assertTrue(rect.contains(AxPoint(10f, 20f)))
        assertTrue(rect.contains(AxPoint(29.9f, 39.9f)))
        assertFalse(rect.contains(AxPoint(30f, 30f)))
        assertFalse(rect.contains(AxPoint(20f, 40f)))
        assertFalse(rect.contains(AxPoint(9.9f, 30f)))
    }
}
