package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * THROWAWAY falsifier for [#185](https://github.com/uny/autograph/issues/185) option B — delete with
 * the spike.
 *
 * Option B would carry an element scope to iOS through the *one* bridged property that is both
 * identity-bearing and never spoken aloud: the accessibility identifier (`testTag`). That slot is
 * already where the tap's own `target` comes from, so the scope has to live on a **separate layout
 * node** — a wrapper — and `isTraversalGroup = true` is the lever meant to make Compose
 * Multiplatform publish that wrapper to the UIKit bridge as an element of its own instead of folding
 * it away.
 *
 * The iOS half (does the wrapper appear as an element, is it an ancestor on `deepestAccessibilityHitPath`,
 * does `nearestAccessibilityClickable` still pick the right element) can only be measured on a
 * simulator. What runs here in seconds is everything that does *not* need one — because #185's
 * pointer-dispatch route died to exactly such a check *after* a device rig had already been built.
 */
private const val SCOPE_TAG_PREFIX = "__autograph_scope__"

private fun scopeTag(json: String) = SCOPE_TAG_PREFIX + json

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centre(tag: String): Offset =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.root(): SemanticsNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

/** A clickable wrapped in the option-B scope carrier, with an `onClick` oracle. */
@androidx.compose.runtime.Composable
private fun ScopeWrapped(json: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .semantics { isTraversalGroup = true }
            .testTag(scopeTag(json)),
    ) {
        Box(Modifier.testTag(tag).size(40.dp).clickable { onClick() })
    }
}

@OptIn(ExperimentalTestApi::class)
class ZzMeasure185BTest {

    /**
     * B-1 — the wrapper is inert for routing. It writes a semantics property and installs no pointer
     * node, so unlike #185's dead end it cannot arbitrate the pointer. That dead end failed exactly
     * this assertion, in both `sharePointerInputWithSiblings` settings.
     */
    @Test
    fun aTraversalGroupScopeWrapperDoesNotChangeWhichElementComposeRoutesTo() = runComposeUiTest {
        var fired: String? = null
        setContent { ScopeWrapped("""{"article_id":"42"}""", "row") { fired = "row" } }
        waitForIdle()

        val centre = centre("row")
        onRoot().performTouchInput { down(centre); up() }
        waitForIdle()

        assertEquals("row", fired)
    }

    /**
     * B-2 — the wrapper does not steal the tap's `target`. [resolveAutocaptureTarget] reads the
     * identifier off the *nearest clickable's own* node rather than off the nearest tagged ancestor,
     * so a non-clickable carrier is structurally incapable of being reported. iOS resolves the same
     * way (`nearestAccessibilityClickable` requires the button trait), which is why option B does not
     * need the identifier exclusion #185's next-step note assumed it would.
     */
    @Test
    fun theScopeWrapperIsNotReportedAsTheTarget() = runComposeUiTest {
        setContent { ScopeWrapped("""{"article_id":"42"}""", "row") {} }
        waitForIdle()

        val resolved = assertNotNull(root().resolveTapAt(centre("row")))
        assertEquals("row", resolved.identifier)
    }

    /**
     * B-3 — the wrapper is *forced*, not stylistic. Two `testTag`s on one layout node collapse to the
     * first, so putting the scope on the clickable's own chain — the canonical usage in
     * [autocaptureScope]'s kdoc — costs one of the two whole. Whichever wins, option B cannot express
     * scope-and-target on a single element, and that is an API constraint the design has to state
     * rather than a bug to fix later.
     */
    @Test
    fun aScopeTagOnTheClickablesOwnChainCollidesWithItsTestTag() = runComposeUiTest {
        setContent {
            Box(
                Modifier
                    .testTag(scopeTag("""{"article_id":"42"}"""))
                    .testTag("row")
                    .size(40.dp)
                    .clickable {},
            )
        }
        waitForIdle()

        val tag = root().findRowTag()
        // One of the two is gone. Which one is the measurement; that both cannot coexist is the point.
        assertTrue(
            tag == "row" || tag?.startsWith(SCOPE_TAG_PREFIX) == true,
            "unexpected surviving testTag: $tag",
        )
        assertEquals(
            1,
            listOfNotNull(tag).size,
            "a layout node reported more than one testTag, which would make the wrapper unnecessary",
        )
        println("AX185B collapsed testTag = $tag")
    }
}

/** The single `TestTag` the one clickable layout node in these fixtures ended up with. */
private fun SemanticsNode.findRowTag(): String? {
    config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
    return children.firstNotNullOfOrNull { it.findRowTag() }
}
