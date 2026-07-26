package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

/** The window-space centre of the element tagged [tag] — where a user tapping it would land. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centreOf(tag: String): Offset =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.center

/** The unmerged semantics root, the node the Android resolver hit-tests from. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.semanticsRoot(): SemanticsNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

/**
 * These tests call [resolveTapAt] — the production hit test itself, shared with
 * `ElementResolver.android.kt` — against a live composition. In particular the **hit test is real**:
 * nothing here tells the resolver which element was meant, so a scope collected off the wrong
 * element's ancestry fails these tests rather than passing them.
 *
 * Three things around it are stood in for, and none of the three is covered anywhere else. The
 * root comes from `onRoot(useUnmergedTree = true)` rather than
 * `RootForTest.semanticsOwner.unmergedRootSemanticsNode` — the same node, but `compose.uiTest`
 * cannot reach a `RootForTest` on the JVM these tests run on. The resolver's `view as? RootForTest`
 * guard and its `localToWindow` conversion have no counterpart here, so callers pass window
 * coordinates in directly. `autograph-compose` declares no Android host-test source set, so the
 * Android `actual` of `rememberElementResolver` — the only place those two run — executes in no test
 * at all; nothing here would notice if the position it hit-tests stopped being root-local.
 *
 * Unmerged matters twice over — on the merged tree a descendant's scope folds into its clickable
 * ancestor's config, and the sibling rows below would collapse into one node.
 *
 * Several tests below assert against the element whose `onClick` actually fired, as an oracle for
 * what Compose itself routed the pointer to. That is the bar this hit test is held to: agreeing with
 * Compose, not merely being self-consistent.
 */
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

    /**
     * Composition happens across the *ancestry*, not within one modifier chain: two calls on the
     * same chain collapse to the first, and the second is dropped entirely — not merged, and not
     * last-wins. That is Compose's rule for every duplicate semantics property on one layout node
     * (a repeated `testTag` or `contentDescription` behaves identically), so this pins the
     * behaviour rather than claiming it is desirable. The kdoc says "once per element" because of
     * exactly this; if that ever stops being true, this test is what notices.
     */
    @Test
    fun twoScopesOnOneModifierChainCollapseToTheFirstRatherThanMerging() = runComposeUiTest {
        setContent {
            Box(
                Modifier
                    .testTag("row")
                    .size(10.dp)
                    .autocaptureScope("outer" to "1", "shared" to "outer")
                    .autocaptureScope("inner" to "2", "shared" to "inner")
                    .clickable {},
            )
        }
        waitForIdle()

        val scope = assertNotNull(semanticsRoot().resolveTapAt(centreOf("row"))).scope
        assertEquals("1", scope.str("outer"))
        assertEquals("outer", scope.str("shared"), "the first call on the chain wins")
        assertNull(scope.str("inner"), "the second call is dropped whole — Compose collapses it away")
    }

    /**
     * A `clickable` drawn *outside* its parent — an overhanging badge, an `offset` decoration — is
     * reached, and carries the scope its wrapper declared. Compose routes the real pointer to it, so
     * anything else would be a drop; the wrapper is on the badge's own ancestry, so its scope
     * describes the element that was hit.
     *
     * This shape is why the modifier can go on a wrapper at all: putting it there materialises that
     * wrapper into the semantics tree, and before [#126](https://github.com/uny/autograph/issues/126)
     * the walk stopped above any child the wrapper's bounds missed.
     */
    @Test
    fun aScopeOnAWrapperReachesAChildDrawnOutsideThatWrappersBounds() = runComposeUiTest {
        var clicks = 0
        setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.size(10.dp).autocaptureScope("row" to "1")) {
                    Box(Modifier.offset(x = 40.dp).testTag("badge").size(10.dp).clickable { clicks++ })
                }
            }
        }
        waitForIdle()

        val centre = centreOf("badge")
        onRoot().performTouchInput { down(centre); up() }
        waitForIdle()
        assertEquals(1, clicks, "precondition: the real pointer reaches the overflowing child")

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(centre))
        assertEquals("badge", resolved.identifier, "the element Compose actually fired")
        assertEquals("1", resolved.scope.str("row"), "and the wrapper it hangs off still scopes it")
    }

    /**
     * [autographIgnore] must reach that same overhanging child. It is subtree-wide, and hanging out
     * of the wrapper's bounds does not take an element out of the wrapper's subtree — so a tap
     * Compose really does deliver is still excluded, rather than escaping the exclusion by geometry.
     */
    @Test
    fun anIgnoredWrapperStillExcludesAChildDrawnOutsideItsBounds() = runComposeUiTest {
        var clicks = 0
        setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.size(10.dp).autographIgnore()) {
                    Box(Modifier.offset(x = 40.dp).testTag("badge").size(10.dp).clickable { clicks++ })
                }
            }
        }
        waitForIdle()

        val centre = centreOf("badge")
        onRoot().performTouchInput { down(centre); up() }
        waitForIdle()
        assertEquals(1, clicks, "precondition: the real pointer reaches the overflowing child")

        assertNull(semanticsRoot().resolveTapAt(centre))
    }

    /**
     * The counterpart, and the reason the walk cannot simply descend everywhere: an overhanging
     * decoration inside `row2` visually covers part of `row1`, and Compose fires **row1**. The
     * decoration is not clickable, so it never becomes the reported element and never drags `row2`
     * up with it — a wrong target carrying `row2`'s scope, exactly the failure [autocaptureScope]
     * exists to make impossible. (A plain deepest-node walk that then resolved *up* to a clickable
     * would report `row2` here; that was measured, and is why the first stage requires the element
     * itself to be clickable and to contain the point.)
     */
    @Test
    fun anOverhangingDecorationNeverStealsATapFromTheRowItCovers() = runComposeUiTest {
        var row1Clicks = 0
        setContent {
            Column(Modifier.size(200.dp)) {
                Box(Modifier.testTag("row1").size(200.dp, 100.dp).autocaptureScope("row" to "1").clickable { row1Clicks++ })
                Box(Modifier.testTag("row2").size(200.dp, 100.dp).autocaptureScope("row" to "2").clickable {}) {
                    Box(Modifier.offset(y = (-60).dp).testTag("avatar").size(40.dp))
                }
            }
        }
        waitForIdle()

        val overlap = Offset(20f, 60f)
        onRoot().performTouchInput { down(overlap); up() }
        waitForIdle()
        assertEquals(1, row1Clicks, "precondition: Compose routes this tap to row1")

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(overlap))
        assertEquals("row1", resolved.identifier)
        assertEquals("1", resolved.scope.str("row"), "and row2's scope must not follow it")
    }

    /**
     * Make that same overhang **clickable** and the answer flips, because Compose's answer flips:
     * the badge itself takes the pointer, so the tap belongs to the badge and carries `row2`'s
     * scope — the row the badge really hangs off.
     *
     * This is the case a walk pruned by parent bounds gets actively *wrong* rather than merely
     * missing: it cannot see the badge at all, so it reports `row1`, an element the pointer never
     * reached. The stronger of the two regressions here — the oracle disagrees with the old
     * behaviour, not just with a drop.
     */
    @Test
    fun aClickableOverhangTakesTheTapFromTheRowItCovers() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Column(Modifier.size(200.dp)) {
                Box(
                    Modifier.testTag("row1").size(200.dp, 100.dp)
                        .autocaptureScope("row" to "1").clickable { fired = "row1" },
                )
                Box(
                    Modifier.testTag("row2").size(200.dp, 100.dp)
                        .autocaptureScope("row" to "2").clickable { fired = "row2" },
                ) {
                    Box(Modifier.offset(y = (-60).dp).testTag("badge").size(40.dp).clickable { fired = "badge" })
                }
            }
        }
        waitForIdle()

        val overlap = Offset(20f, 60f)
        onRoot().performTouchInput { down(overlap); up() }
        waitForIdle()
        assertEquals("badge", fired, "precondition: Compose routes this tap to the overhanging badge")

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(overlap))
        assertEquals("badge", resolved.identifier)
        assertEquals("2", resolved.scope.str("row"), "the badge hangs off row2, so row2 scopes it")
    }

    /**
     * A node that has semantics but no click action — a `testTag`ged decoration, a scoped wrapper —
     * does not swallow the tap on a `clickable` underneath it. Compose passes the pointer straight
     * through, since only a pointer-input node consumes; the walk agrees because a node has to be
     * clickable itself before it can be the reported element.
     */
    @Test
    fun aNonClickableOverlayDoesNotSwallowTheTapOnTheElementBelowIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.testTag("button").size(50.dp).clickable { clicks++ })
                // Declared second, so drawn on top, and it carries semantics of its own.
                Box(Modifier.testTag("overlay").size(50.dp).autocaptureScope("overlay" to "yes"))
            }
        }
        waitForIdle()

        val point = Offset(25f, 25f)
        onRoot().performTouchInput { down(point); up() }
        waitForIdle()
        assertEquals(1, clicks, "precondition: Compose routes the tap through the overlay to the button")

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(point))
        assertEquals("button", resolved.identifier)
        assertNull(resolved.scope.str("overlay"), "the overlay is not on the button's ancestry")
    }

    /**
     * A scope declared *below* the reported element still contributes, and only where the tap
     * actually landed on it. That is what the hit test's second stage is for: having picked the
     * element to report, it keeps descending as far as the point still lands, so the leaf's scope
     * joins the chain — while a tap elsewhere in the same row carries only the row's own.
     */
    @Test
    fun aScopeOnALeafInsideTheReportedElementContributesOnlyWhereTheTapLands() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("row").size(100.dp).autocaptureScope("row" to "1").clickable {}) {
                Box(Modifier.testTag("part").size(20.dp).autocaptureScope("part" to "avatar"))
            }
        }
        waitForIdle()

        val onTheLeaf = assertNotNull(semanticsRoot().resolveTapAt(Offset(10f, 10f)))
        assertEquals("row", onTheLeaf.identifier, "the leaf is not clickable, so the row is reported")
        assertEquals("avatar", onTheLeaf.scope.str("part"), "but the leaf's own scope still reaches the tap")
        assertEquals("1", onTheLeaf.scope.str("row"))

        val elsewhere = assertNotNull(semanticsRoot().resolveTapAt(Offset(80f, 80f)))
        assertEquals("row", elsewhere.identifier)
        assertNull(elsewhere.scope.str("part"), "a tap outside the leaf must not pick its scope up")
    }

    /**
     * Re-emitting the same scope must produce an *equal* modifier, so a recomposition that changes
     * nothing costs nothing. `Modifier.semantics {}` cannot do this: it compares its lambda by
     * reference, and a lambda capturing the properties is a fresh instance every call, so each
     * recomposition would invalidate the row's semantics config. This is why [autocaptureScope]
     * carries its own element — see its private kdoc.
     */
    @Test
    fun rebuildingTheSameScopeProducesAnEqualModifier() {
        val json = JsonObject(mapOf("article_id" to JsonPrimitive("42")))
        assertEquals(Modifier.autocaptureScope(json), Modifier.autocaptureScope(json))
        // ...including through the vararg overload, which assembles a fresh JsonObject each call.
        assertEquals(
            Modifier.autocaptureScope("article_id" to "42"),
            Modifier.autocaptureScope("article_id" to "42"),
        )
        assertNotEquals(
            Modifier.autocaptureScope("article_id" to "42"),
            Modifier.autocaptureScope("article_id" to "43"),
            "a genuinely different scope must still compare unequal, or an update would be skipped",
        )
    }

    /**
     * The other half of that: a scope that *does* change must reach the semantics tree. Skipping the
     * update would leave a row reporting taps against its previous article — a wrong scope, which
     * this library treats as strictly worse than none.
     */
    @Test
    fun aChangedScopeReachesTheSemanticsTreeOnRecomposition() = runComposeUiTest {
        val id = mutableStateOf("42")
        setContent {
            Box(Modifier.testTag("row").size(10.dp).autocaptureScope("article_id" to id.value).clickable {})
        }
        waitForIdle()
        assertEquals("42", semanticsRoot().resolveTapAt(centreOf("row"))?.scope?.str("article_id"))

        id.value = "99"
        waitForIdle()
        assertEquals("99", semanticsRoot().resolveTapAt(centreOf("row"))?.scope?.str("article_id"))
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
