package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
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
 * Runs the **real** Android read path over this node: the same `selfAndAncestors()` /
 * `toAutocaptureNode()` / [resolveAutocaptureTarget] calls `ElementResolver.android.kt` makes,
 * against a semantics node fetched from a live composition.
 *
 * Only the hit test is stood in for — picking the node by tag instead of by coordinates — because
 * `compose.uiTest` cannot reach a `RootForTest` on the JVM these tests run on. Everything downstream
 * of the hit is production code, so a scope that stops arriving in the semantics tree, or stops being
 * collected off the ancestry, fails here. The geometry it replaces is covered separately by
 * [AutocaptureNodeTest]'s `findDeepestHit` tests.
 *
 * **Unmerged**, matching the resolver: on the merged tree a descendant's scope folds into its
 * clickable ancestor's config, which would make every test below pass for the wrong reason.
 */
private fun SemanticsNodeInteraction.resolveAutocapture(): AutocaptureTarget? =
    resolveAutocaptureTarget(fetchSemanticsNode().selfAndAncestors().map { it.toAutocaptureNode() })

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

        assertEquals(EmptyJsonObject, onNodeWithTag("row", useUnmergedTree = true).resolveAutocapture()?.scope)
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

        assertEquals("row1", onNodeWithTag("row1", useUnmergedTree = true).resolveAutocapture()?.scope?.str("article_id"))
        assertEquals("row2", onNodeWithTag("row2", useUnmergedTree = true).resolveAutocapture()?.scope?.str("article_id"))
        assertEquals("row3", onNodeWithTag("row3", useUnmergedTree = true).resolveAutocapture()?.scope?.str("article_id"))
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

        val scope = assertNotNull(onNodeWithTag("row", useUnmergedTree = true).resolveAutocapture()).scope
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

        val resolved = onNodeWithTag("share", useUnmergedTree = true).resolveAutocapture()
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

        assertNull(onNodeWithTag("row", useUnmergedTree = true).resolveAutocapture())
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

        val resolved = assertNotNull(onNodeWithTag("row2", useUnmergedTree = true).resolveAutocapture())
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
