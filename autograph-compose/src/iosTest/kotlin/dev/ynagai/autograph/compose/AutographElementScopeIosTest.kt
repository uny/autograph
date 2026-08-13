package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.uikit.AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the iOS actual of [AutographElementScope] at the **semantics** layer — the reserved
 * `testTag`, the traversal group, and the payload ceiling.
 *
 * This is the layer `compose.uiTest`'s iOS scene actually populates. The layer above it — whether
 * Compose Multiplatform bridges that traversal group into an accessibility *ancestor*, which is what
 * makes the scope reachable from a tap — it does not populate, and no test in this repo can (see
 * [AutographElementScopeTest]'s kdoc, and the on-device checklist on the pull request). So what is
 * pinned here is precisely what this module decides: *what gets written*, not what UIKit does with it.
 *
 * The oversized case is the one worth having. Over the ceiling the marker is dropped whole, which is
 * silent by construction — the app still reports taps, just unscoped — so a regression would look
 * exactly like a correctly-configured app until someone read the events.
 */
@OptIn(ExperimentalTestApi::class, AutographInternalApi::class)
class AutographElementScopeIosTest {

    /** Every reserved identifier in the tree, in traversal order. The wrapper writes exactly one. */
    private fun SemanticsNode.scopeIdentifiers(): List<String> = buildList {
        config.getOrNull(SemanticsProperties.TestTag)
            ?.takeIf { it.startsWith(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX) }
            ?.let(::add)
        children.forEach { addAll(it.scopeIdentifiers()) }
    }

    /**
     * Traversal groups anywhere in the tree. The wrapper is the only thing in these fixtures that sets
     * one, so this is how the accessibility cost of a scope is counted — and how a dropped scope is
     * shown to cost nothing.
     */
    private fun SemanticsNode.traversalGroups(): List<SemanticsNode> = buildList {
        if (config.getOrNull(SemanticsProperties.IsTraversalGroup) == true) add(this@traversalGroups)
        children.forEach { addAll(it.traversalGroups()) }
    }

    @Test
    fun publishesTheScopeAsAReservedIdentifierOnATraversalGroup() = runComposeUiTest {
        setContent {
            AutographElementScope("article_id" to "42") {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            listOf(AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX + """{"article_id":"42"}"""),
            root.scopeIdentifiers(),
        )
        // The identifier and the group are one node: without the group it is bridged as a flat
        // sibling of the clickable, and the walk has no ancestry to read the scope off.
        assertEquals(1, root.traversalGroups().size)
        assertEquals(
            AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX + """{"article_id":"42"}""",
            root.traversalGroups().single().config.getOrNull(SemanticsProperties.TestTag),
        )
    }

    /**
     * The payload ceiling, exercised an order of magnitude past it rather than at the boundary: the
     * constant is private to the actual, and pinning the exact number here would only assert that two
     * copies of it agree. What matters is that a pathological scope is dropped whole — no reserved
     * identifier, and so no accessibility container either, since publishing one without a readable
     * scope would be the cost of this API with none of its benefit.
     */
    @Test
    fun dropsTheMarkerEntirelyWhenTheEncodedScopeIsOversized() = runComposeUiTest {
        setContent {
            AutographElementScope("blob" to "x".repeat(64 * 1024)) {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(emptyList(), root.scopeIdentifiers())
        assertTrue(root.traversalGroups().isEmpty(), "a dropped scope must publish no container either")
    }

    /**
     * The other end of the same rule: an empty scope publishes nothing either. A container with no
     * readable scope is this API's whole accessibility cost — a rotor stop, an extra node — with none
     * of its benefit, since the reader folds an empty payload away regardless. Reachable without anyone
     * writing `AutographElementScope { }` on purpose: a scope whose values all turned out absent for
     * this row. Android has the matching assertion in [AutographElementScopeTest]; this is what stops
     * the two platforms disagreeing about what an empty scope costs.
     */
    @Test
    fun publishesNothingAtAllWhenTheScopeIsEmpty() = runComposeUiTest {
        setContent {
            AutographElementScope {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(emptyList(), root.scopeIdentifiers())
        assertTrue(root.traversalGroups().isEmpty(), "an empty scope must publish no container either")
        assertEquals("row", onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag))
    }

    /**
     * Dropping the marker degrades to exactly what happens with no wrapper at all — the element keeps
     * its own name and its click. This is the half of the drop that makes it safe to be silent.
     */
    @Test
    fun anOversizedScopeLeavesTheWrappedElementUntouched() = runComposeUiTest {
        setContent {
            AutographElementScope("blob" to "x".repeat(64 * 1024)) {
                Box(Modifier.testTag("row").size(20.dp).clickable {})
            }
        }
        waitForIdle()

        val row = onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("row", row.config.getOrNull(SemanticsProperties.TestTag))
    }
}
