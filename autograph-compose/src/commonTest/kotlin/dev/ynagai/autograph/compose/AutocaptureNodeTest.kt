package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class TestNode(val name: String, val bounds: Rect, val children: List<TestNode> = emptyList())

class AutocaptureNodeTest {

    @Test
    fun findDeepestHitReturnsTheInnermostNodeContainingThePoint() {
        val leaf = TestNode("button", Rect(10f, 10f, 20f, 20f))
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(leaf))

        val hit = findDeepestHit(root, Offset(15f, 15f), bounds = { it.bounds }, children = { it.children })

        assertEquals("button", hit?.name)
    }

    @Test
    fun findDeepestHitReturnsTheParentWhenThePointMissesEveryChild() {
        val leaf = TestNode("button", Rect(10f, 10f, 20f, 20f))
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(leaf))

        val hit = findDeepestHit(root, Offset(50f, 50f), bounds = { it.bounds }, children = { it.children })

        assertEquals("card", hit?.name)
    }

    @Test
    fun findDeepestHitReturnsNullWhenThePointMissesTheRoot() {
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f))

        val hit = findDeepestHit(root, Offset(200f, 200f), bounds = { it.bounds }, children = { it.children })

        assertNull(hit)
    }

    @Test
    fun findDeepestHitPrefersTheLastOverlappingChild() {
        // Later children are visually on top when siblings overlap — asReversed() must check them first.
        val behind = TestNode("behind", Rect(0f, 0f, 50f, 50f))
        val front = TestNode("front", Rect(0f, 0f, 50f, 50f))
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(behind, front))

        val hit = findDeepestHit(root, Offset(25f, 25f), bounds = { it.bounds }, children = { it.children })

        assertEquals("front", hit?.name)
    }

    @Test
    fun resolveAutocaptureTargetReturnsTheNearestClickableAncestorsIdentifier() {
        // Hit node itself isn't clickable (e.g. an inner Text/Icon); its clickable ancestor is.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "share_button", clickable = true, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "card", clickable = true, ignored = false, instrumented = false),
        )
        assertEquals("share_button", resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenNothingInTheChainIsClickable() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "card", clickable = false, ignored = false, instrumented = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenTheHitNodeItselfIsInstrumented() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = "inner", clickable = true, ignored = false, instrumented = true),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenAnAncestorBetweenTheHitNodeAndTheClickableIsIgnored() {
        // autographIgnore()'s own marker on an intermediate node must veto the tap even though a
        // clickable ancestor exists further up.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = true, instrumented = false),
            AutocaptureNode(identifier = "share_button", clickable = true, ignored = false, instrumented = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenAnAncestorAboveTheClickableIsIgnored() {
        // autographIgnore() is subtree-wide: a container ABOVE an already-clickable descendant
        // (e.g. Box(Modifier.autographIgnore()) { Button(...) }) must still suppress the tap, even
        // though the walk would otherwise return the clickable's identifier before reaching it.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "button", clickable = true, ignored = false, instrumented = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = true, instrumented = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetIgnoresAnInstrumentedDescendantThatIsNotTheReturnedClickable() {
        // trackImpression() sets `instrumented` on a non-clickable descendant (e.g. an inner
        // Image). It must NOT veto an outer plain Modifier.clickable that was never itself
        // instrumented -- only the node actually being returned should be checked.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = true),
            AutocaptureNode(identifier = "card", clickable = true, ignored = false, instrumented = false),
        )
        assertEquals("card", resolveAutocaptureTarget(chain))
    }

    @Test
    fun identifierFromPrefersTestTagOverRoleOverLabel() {
        assertEquals("tag", identifierFrom(testTag = "tag", role = "Button", label = "Share"))
        assertEquals("Button", identifierFrom(testTag = null, role = "Button", label = "Share"))
        assertEquals("Share", identifierFrom(testTag = null, role = null, label = "Share"))
        assertNull(identifierFrom(testTag = null, role = null, label = null))
    }
}
