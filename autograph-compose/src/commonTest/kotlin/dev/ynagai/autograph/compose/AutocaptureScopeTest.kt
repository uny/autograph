package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class ScopeCaptureTracker : Tracker {
    val trackedProps = mutableListOf<JsonObject>()
    val trackedTargets = mutableListOf<String?>()
    override fun track(name: String, properties: JsonObject, target: String?) {
        trackedProps += properties
        trackedTargets += target
    }
    override fun screen(name: String, properties: JsonObject) {}
    override fun identify(userId: String, traits: JsonObject) {}
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

/**
 * Resolves a tap at [windowPosition] exactly the way `ElementResolver.android.kt` does: hit-test the
 * unmerged semantics tree with [findDeepestHit], then read the hit node's ancestry through
 * `selfAndAncestors()` / `toAutocaptureNode()` / [resolveAutocaptureTarget].
 *
 * Every line of that is the production path. The one substitution is where the root comes from —
 * `onRoot(useUnmergedTree = true)` instead of `RootForTest.semanticsOwner.unmergedRootSemanticsNode`,
 * which `compose.uiTest` cannot reach on the JVM these tests run on. It is the same node. In
 * particular the **hit test is real**: nothing here tells the resolver which element was meant, so a
 * scope collected off the wrong element's ancestry fails these tests rather than passing them.
 *
 * Unmerged matters twice over — on the merged tree a descendant's scope folds into its clickable
 * ancestor's config, and the sibling rows below would collapse into one node.
 */
private fun SemanticsNode.resolveTapAt(windowPosition: Offset): AutocaptureTarget? {
    val hit = findDeepestHit(
        root = this,
        point = windowPosition,
        bounds = { it.boundsInWindow },
        children = { it.children },
    ) ?: return null
    return resolveAutocaptureTarget(hit.selfAndAncestors().map { it.toAutocaptureNode() })
}

/** The window-space centre of the element tagged [tag] — where a user tapping it would land. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centreOf(tag: String): Offset =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.center

/** The unmerged semantics root, the node the Android resolver hit-tests from. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.semanticsRoot(): SemanticsNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

@OptIn(ExperimentalTestApi::class)
class AutocaptureScopeTest {

    @Test
    fun theModifierWritesItsPropertiesIntoTheSemanticsTree() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("row").size(10.dp).autocaptureScope("article_id" to "42"))
        }
        waitForIdle()

        val scope = onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode().config.autocaptureScopeOrEmpty()
        assertEquals("42", scope.str("article_id"))
    }

    @Test
    fun aBlankModifierCallContributesNothing() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("row").size(10.dp).autocaptureScope().clickable {})
        }
        waitForIdle()

        assertEquals(EmptyJsonObject, semanticsRoot().resolveTapAt(centreOf("row"))?.scope)
    }

    /**
     * The point of #68. Three rows are mounted at once, each in its own scope — the shape the ambient
     * [ScopeStack] cannot resolve, because from mount order alone it cannot tell which subtree a tap
     * landed in and so drops all three (see `ScopedContextUiTest.siblingScopesMountedAtOnceResolveToNoScope`).
     * Read off the tapped element's own ancestry instead, each row attributes exactly.
     */
    @Test
    fun siblingRowsEachAttributeTheirOwnScope() = runComposeUiTest {
        setContent {
            Column {
                ScopedRow("row1")
                ScopedRow("row2")
                ScopedRow("row3")
            }
        }
        waitForIdle()

        assertEquals("row1", semanticsRoot().resolveTapAt(centreOf("row1"))?.scope?.str("article_id"))
        assertEquals("row2", semanticsRoot().resolveTapAt(centreOf("row2"))?.scope?.str("article_id"))
        assertEquals("row3", semanticsRoot().resolveTapAt(centreOf("row3"))?.scope?.str("article_id"))
    }

    /**
     * Two scopes stacked exactly on top of each other, the visually-topmost one raised by
     * `Modifier.zIndex` *against* declaration order — the shape a scope resolved from anything other
     * than the hit itself gets wrong.
     *
     * It resolves to the element actually on top. Measured, not assumed: `over` is declared FIRST,
     * so [findDeepestHit]'s `asReversed()` would return `under` if `SemanticsNode.children` were in
     * declaration order. It returns `over`, which means the semantics children are z-sorted and the
     * walk sees the visual stacking. (`AutocaptureConfig`'s known-gaps note is corrected accordingly:
     * `Modifier.clip` remains a gap — a rect cannot describe a rounded or shaped clip — but `zIndex`
     * is not one, at least for overlapping siblings.)
     *
     * The invariant this pins is the one the design rests on: identifier and scope are read off the
     * *same* chain, so whichever element the hit test picks, the scope names that element and no
     * other. A scope resolved separately — from a positional registry, or from mount order — is what
     * produces the far worse "right target carrying another element's scope".
     */
    @Test
    fun theTopmostOfTwoStackedScopesWinsAndTheScopeNamesWhicheverElementWasHit() = runComposeUiTest {
        setContent {
            Box {
                // Declared first, drawn on top: declaration order and visual order disagree here.
                Box(Modifier.testTag("over").size(20.dp).zIndex(1f).autocaptureScope("card" to "over").clickable {})
                Box(Modifier.testTag("under").size(20.dp).autocaptureScope("card" to "under").clickable {})
            }
        }
        waitForIdle()

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(centreOf("over")))
        assertEquals("over", resolved.identifier, "the visually topmost element takes the tap")
        assertEquals(resolved.identifier, resolved.scope.str("card"), "the scope must name the element that was hit")
    }

    /**
     * Nested `autocaptureScope`s compose the way nested [AutographScope]s do. This is the case a
     * `CompositionLocal`-carried lineage could not have expressed — two modifiers in the same
     * composable read the same enclosing frame, so the stack would see them as siblings and drop both.
     * Read off the layout ancestry, the nesting is simply true.
     */
    @Test
    fun nestedScopesMergeWithTheInnerOneWinningAClash() = runComposeUiTest {
        setContent {
            Column(Modifier.autocaptureScope("section" to "for_you", "surface" to "list")) {
                Box(
                    Modifier
                        .testTag("row")
                        .size(10.dp)
                        .autocaptureScope("article_id" to "42", "surface" to "row")
                        .clickable {},
                )
            }
        }
        waitForIdle()

        val scope = assertNotNull(semanticsRoot().resolveTapAt(centreOf("row"))).scope
        assertEquals("42", scope.str("article_id"))
        assertEquals("for_you", scope.str("section"), "the enclosing scope must still reach the tap")
        assertEquals("row", scope.str("surface"), "the inner scope must win the shared key")
    }

    @Test
    fun aScopeOnAnAncestorReachesAClickableDescendant() = runComposeUiTest {
        setContent {
            Column(Modifier.autocaptureScope("article_id" to "42")) {
                Box(Modifier.testTag("share").size(10.dp).clickable {})
            }
        }
        waitForIdle()

        val resolved = semanticsRoot().resolveTapAt(centreOf("share"))
        assertEquals("share", resolved?.identifier)
        assertEquals("42", resolved?.scope?.str("article_id"))
    }

    @Test
    fun anIgnoredSubtreeReportsNoTapAndSoNoScope() = runComposeUiTest {
        // A scope must not leak out of a subtree the caller excluded from autocapture entirely.
        setContent {
            Column(Modifier.autographIgnore()) {
                Box(Modifier.testTag("row").size(10.dp).autocaptureScope("article_id" to "42").clickable {})
            }
        }
        waitForIdle()

        assertNull(semanticsRoot().resolveTapAt(centreOf("row")))
    }

    /**
     * The whole pipeline: an ambient [AutographScope] route above rows that carry their own element
     * scope, resolved off the semantics tree and reported through [reportTapIfResolvable]. Both
     * scopes must reach the event — the route because it encloses every row (so the ambient stack
     * keeps it), the row because the tap resolved to it.
     */
    @Test
    fun anAmbientRouteScopeAndTheTappedRowsOwnScopeBothReachTheEvent() = runComposeUiTest {
        val stack = ScopeStack()
        val tracker = ScopeCaptureTracker()
        setContent {
            CompositionLocalProvider(LocalTracker provides tracker, LocalScopeStack provides stack) {
                AutographScope("route" to "feed") {
                    Column {
                        ScopedRow("row1")
                        ScopedRow("row2")
                    }
                }
            }
        }
        waitForIdle()

        // The ambient stack alone cannot name the row — that is exactly the gap this modifier fills.
        assertNull(stack.current().scope.str("article_id"))

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(centreOf("row2")))
        reportTapIfResolvable(tracker, stack, AutocaptureConfig()) { resolved }

        val props = tracker.trackedProps.single()
        assertEquals("row2", props.str("article_id"), "the tapped row's own scope")
        assertEquals("feed", props.str("route"), "the enclosing route scope from the ambient stack")
        assertEquals("row2", tracker.trackedTargets.single())
    }
}

@Composable
private fun ScopedRow(id: String) {
    Box(
        Modifier
            .testTag(id)
            .size(10.dp)
            .autocaptureScope("article_id" to id)
            .clickable {},
    )
}
