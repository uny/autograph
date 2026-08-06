package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIView
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

    @Test
    fun aColdRootWithAWarmChildIsNotCold() {
        val root = UIView()
        val warm = UIView()
        warm.setPointFrame(0.0, 0.0, 1.0, 1.0)
        root.addSubview(warm)

        assertFalse(isAccessibilityTreeCold(root), "the root's own state is not the whole answer, the tree's is")
    }
}
