package dev.ynagai.autograph.compose

import dev.ynagai.autograph.asJsonObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
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
import kotlinx.serialization.json.JsonElement
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
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        trackedProps += properties.asJsonObject()
        trackedTargets += target
    }
    override fun screen(name: String, properties: Map<String, JsonElement>) {}
    override fun identify(userId: String, traits: Map<String, JsonElement>) {}
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

/** The root-space centre of the element tagged [tag] — where a user tapping it would land. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centreOf(tag: String): Offset =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

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
 * guard and its `localToRoot` conversion have no counterpart here, so callers pass root coordinates
 * in directly. `autograph-compose` declares no Android host-test source set, so the Android `actual`
 * of `rememberElementResolver` — the only place those two run — executes in no test at all; nothing
 * here would notice if the position it hit-tests stopped being root-local. The composition root sits
 * at the window origin on the JVM, so these tests cannot tell root space from window space either.
 *
 * Unmerged matters twice over — on the merged tree a descendant's scope folds into its clickable
 * ancestor's config, and the sibling rows below would collapse into one node.
 *
 * Several tests below assert against the element whose `onClick` actually fired, as an oracle for
 * what Compose itself routed the pointer to. That is the bar this hit test is held to: agreeing with
 * Compose, not merely being self-consistent.
 */
// The modifier under test is deprecated in favour of AutographElementScope but keeps its behaviour
// until removal at 1.0, so these tests stay exactly as they are — and say so here rather than emitting
// ~40 deprecation warnings across four target compilations, which would bury the next real one.
@Suppress("DEPRECATION")
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

    // ---- minimum touch target (#127) ----

    /**
     * The defect #127 reported, with the identifier AND the scope both checked. Compose expands the
     * touch target of anything below `minimumInteractiveComponentSize`, so a tap 8px outside a 16dp
     * icon fires the icon; before this walk ranked expanded targets the tap was attributed to the
     * sheet drawn underneath — a wrong target, and a wrong scope with it.
     */
    @Test
    fun aTapInASmallClickablesExpandedTargetResolvesToThatClickable() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(
                Modifier.testTag("sheet").size(200.dp)
                    .autocaptureScope("sheet" to "s").clickable { fired = "sheet" },
            ) {
                Box(
                    Modifier.offset(x = 50.dp, y = 50.dp).testTag("icon").size(16.dp)
                        .autocaptureScope("icon" to "i").clickable { fired = "icon" },
                )
            }
        }
        waitForIdle()

        val besideTheIcon = Offset(42f, 58f)
        onRoot().performTouchInput { down(besideTheIcon); up() }
        waitForIdle()
        assertEquals("icon", fired, "precondition: Compose routes this to the icon, not the sheet")

        val resolved = assertNotNull(semanticsRoot().resolveTapAt(besideTheIcon))
        assertEquals("icon", resolved.identifier)
        assertEquals("i", resolved.scope.str("icon"), "and the icon's own scope travels with it")
        assertEquals("s", resolved.scope.str("sheet"))
    }

    /**
     * The edge of the expanded target, to the pixel. `Rect.contains` excludes the right and bottom
     * edges while Compose's minimum-target test includes them, so an off-by-one here would silently
     * hand the boundary column back to the element underneath.
     */
    @Test
    fun theExpandedTargetIncludesItsFarEdgeAndStopsImmediatelyAfter() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp).clickable { fired = "stage" }) {
                Box(Modifier.offset(x = 60.dp, y = 60.dp).testTag("b").size(16.dp).clickable { fired = "b" })
            }
        }
        waitForIdle()

        // Drawn 60..76, so the 48dp target runs 44..92 — inclusive at both ends.
        for ((x, expected) in listOf(44f to "b", 92f to "b", 43.9f to "stage", 92.001f to "stage")) {
            val point = Offset(x, 68f)
            onRoot().performTouchInput { down(point); up() }
            waitForIdle()
            assertEquals(expected, fired, "precondition at x=$x")
            assertEquals(expected, semanticsRoot().resolveTapAt(point)?.identifier, "at x=$x")
        }
    }

    /**
     * An element already at least the minimum touch target on both axes is never hit outside its own
     * bounds — Compose gives up on the minimum-target path for it entirely. Without that rule a large
     * element that is merely *scaled* or ancestor-clipped looks expanded and starts claiming taps
     * beside it, because its drawn rect and its reported touch bounds differ for that reason too.
     */
    @Test
    fun aClickableAtLeastTheMinimumSizeIsNeverHitOutsideItsOwnBounds() = runComposeUiTest {
        var fired: String? = null
        setContent {
            // Nothing clickable underneath, deliberately: a definite hit outranks a minimum-target
            // one, so an element below would mask a wrongly-expanded target instead of exposing it.
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(Modifier.offset(x = 20.dp, y = 20.dp).size(100.dp).clipToBounds()) {
                    Box(
                        Modifier.testTag("scaled").size(80.dp)
                            .graphicsLayer { scaleX = 2f; scaleY = 2f }
                            .clickable { fired = "scaled" },
                    )
                }
            }
        }
        waitForIdle()

        // Scaling and the ancestor clip pull the drawn rect (20..120) and the reported touch bounds
        // (-4..120) apart, so a rect-width comparison would call this expanded. Its measured size is
        // 80dp, well past the minimum, so Compose never routes here at all.
        val besideTheScaledElement = Offset(10f, 60f)
        onRoot().performTouchInput { down(besideTheScaledElement); up() }
        waitForIdle()
        assertNull(fired, "precondition: too large to have an expanded target, so nothing fires")
        assertNull(semanticsRoot().resolveTapAt(besideTheScaledElement))

        val insideIt = Offset(60f, 60f)
        onRoot().performTouchInput { down(insideIt); up() }
        waitForIdle()
        assertEquals("scaled", fired, "precondition: inside its own bounds it still takes the tap")
        assertEquals("scaled", semanticsRoot().resolveTapAt(insideIt)?.identifier)
    }

    /**
     * A definite hit outranks a minimum-target hit between branches, whatever the stacking order —
     * so a small icon drawn on top does not steal a tap that landed squarely inside the surface
     * beside it. This is the rule that keeps expanded targets from over-reaching.
     */
    @Test
    fun aTapInsideOneElementIsNotStolenByASmallSiblingDrawnOnTopOfIt() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(Modifier.testTag("big").size(120.dp).clickable { fired = "big" })
                // Declared second, so drawn on top; its expanded target reaches back over `big`.
                Box(
                    Modifier.offset(x = 120.dp, y = 40.dp).testTag("small").size(16.dp)
                        .clickable { fired = "small" },
                )
            }
        }
        waitForIdle()

        val insideBigAndInSmallsTarget = Offset(110f, 48f)
        onRoot().performTouchInput { down(insideBigAndInSmallsTarget); up() }
        waitForIdle()
        assertEquals("big", fired, "precondition: a definite hit outranks a minimum-target one")
        assertEquals("big", semanticsRoot().resolveTapAt(insideBigAndInSmallsTarget)?.identifier)
    }

    /**
     * Where two expanded targets overlap and neither element was really hit, the nearer one wins and
     * stacking order only breaks a tie. Ranking these by depth or by stacking — the rule that governs
     * definite hits — would give `b` the whole overlap.
     */
    @Test
    fun overlappingExpandedTargetsAreRankedByDistanceNotByStacking() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(Modifier.offset(x = 20.dp, y = 50.dp).testTag("a").size(16.dp).clickable { fired = "a" })
                // Drawn on top of `a`, and their targets overlap between x 44 and 52.
                Box(Modifier.offset(x = 60.dp, y = 50.dp).testTag("b").size(16.dp).clickable { fired = "b" })
            }
        }
        waitForIdle()

        for ((x, expected) in listOf(46f to "a", 50f to "b", 48f to "b")) {
            val point = Offset(x, 58f)
            onRoot().performTouchInput { down(point); up() }
            waitForIdle()
            assertEquals(expected, fired, "precondition at x=$x")
            assertEquals(expected, semanticsRoot().resolveTapAt(point)?.identifier, "at x=$x")
        }
    }

    /**
     * Compose measures that distance in each candidate's OWN coordinate space, so two differently
     * scaled elements cannot be ranked by their root-space distances — the winner inverts. Pins the
     * conversion back through each axis's scale.
     */
    @Test
    fun competingExpandedTargetsAreRankedInEachElementsOwnScale() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(
                    Modifier.offset(x = 40.dp, y = 90.dp).testTag("A").size(16.dp)
                        .graphicsLayer { scaleX = 3f; scaleY = 3f }.clickable { fired = "A" },
                )
                Box(Modifier.offset(x = 90.dp, y = 90.dp).testTag("B").size(16.dp).clickable { fired = "B" })
            }
        }
        waitForIdle()

        // Swept in the spike: every point in this band agrees once the distance is scale-corrected.
        for (x in 70..100) {
            val point = Offset(x.toFloat(), 98f)
            fired = null
            onRoot().performTouchInput { down(point); up() }
            waitForIdle()
            assertEquals(fired, semanticsRoot().resolveTapAt(point)?.identifier, "at x=$x")
        }
    }

    /**
     * The ranking distance divides by the element's drawn extent, and an ancestor clip shrinks that
     * extent without being a transform — so the conversion looks like it should over-count a clipped
     * element's distance and hand it taps it should lose. It does not, because `touchBoundsInRoot` is
     * itself grown from the *clipped* rect: measured here, the half-clipped element's drawn rect and
     * its expanded target share a centre. Pins that the two stay consistent.
     */
    @Test
    fun aPartiallyClippedSmallTargetIsRankedAgainstItsNeighbourCorrectly() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                // Cut to half its width by the ancestor, so drawn 30..38 while measured 16dp wide.
                Box(Modifier.offset(x = 30.dp, y = 90.dp).size(width = 8.dp, height = 16.dp).clipToBounds()) {
                    Box(Modifier.testTag("clipped").requiredSize(16.dp).clickable { fired = "clipped" })
                }
                Box(
                    Modifier.offset(x = 70.dp, y = 90.dp).testTag("plain").requiredSize(16.dp)
                        .clickable { fired = "plain" },
                )
            }
        }
        waitForIdle()

        // Swept across the whole band where the two expanded targets meet and overlap.
        for (x in 20..100) {
            val point = Offset(x.toFloat(), 97f)
            fired = null
            onRoot().performTouchInput { down(point); up() }
            waitForIdle()
            assertEquals(fired, semanticsRoot().resolveTapAt(point)?.identifier, "at x=$x")
        }
    }

    /**
     * The distance is Euclidean, not per-axis. Every fixture above is effectively one-dimensional and
     * would pass just as well against a Manhattan or largest-axis metric; this one places the
     * candidates diagonally so those disagree.
     */
    @Test
    fun competingExpandedTargetsAreRankedByEuclideanDistance() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(
                    Modifier.offset(x = 60.dp, y = 92.dp).testTag("near").requiredSize(16.dp)
                        .clickable { fired = "near" },
                )
                Box(
                    Modifier.offset(x = 58.dp, y = 108.dp).testTag("diag").requiredSize(16.dp)
                        .clickable { fired = "diag" },
                )
            }
        }
        waitForIdle()

        // (50,100) is 10 away from `near` on one axis and 8 away from `diag` on both: Euclidean picks
        // `near` (10 < 11.3) where a largest-axis metric would pick `diag` (8 < 10).
        val point = Offset(50f, 100f)
        onRoot().performTouchInput { down(point); up() }
        waitForIdle()
        assertEquals("near", fired, "precondition")
        assertEquals("near", semanticsRoot().resolveTapAt(point)?.identifier)

        for (x in 30..90) {
            for (y in listOf(96, 100, 104, 112)) {
                val p = Offset(x.toFloat(), y.toFloat())
                fired = null
                onRoot().performTouchInput { down(p); up() }
                waitForIdle()
                assertEquals(fired, semanticsRoot().resolveTapAt(p)?.identifier, "at ($x,$y)")
            }
        }
    }

    /**
     * The subtlest rule, and until now covered only against a hand-built tree: an ancestor's own hit
     * inside its real bounds settles its branch — stopping the scan before the sibling underneath is
     * considered — while the node actually reported is the deeper one, reached only through its
     * expanded target. Reporting the ancestor instead, or letting the scan continue to the sibling,
     * each names an element Compose did not route to.
     */
    @Test
    fun anAncestorsOwnHitSettlesItsBranchWhileTheDeeperElementIsReported() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp)) {
                Box(Modifier.testTag("sibling").size(120.dp).clickable { fired = "sibling" })
                // Drawn over `sibling` and covering it exactly.
                Box(Modifier.testTag("parent").size(120.dp).clickable { fired = "parent" }) {
                    Box(
                        Modifier.offset(x = 100.dp, y = 40.dp).testTag("child").size(16.dp)
                            .clickable { fired = "child" },
                    )
                }
            }
        }
        waitForIdle()

        // Inside both `sibling` and `parent`, but only in `child`'s expanded target.
        val point = Offset(90f, 48f)
        onRoot().performTouchInput { down(point); up() }
        waitForIdle()
        assertEquals("child", fired, "precondition: the deepest element in the settled branch")
        assertEquals("child", semanticsRoot().resolveTapAt(point)?.identifier)

        // Beyond the child's target the parent keeps its own tap, so this is a settlement and not a
        // subtree-wide handover.
        val beyond = Offset(50f, 48f)
        onRoot().performTouchInput { down(beyond); up() }
        waitForIdle()
        assertEquals("parent", fired, "precondition")
        assertEquals("parent", semanticsRoot().resolveTapAt(beyond)?.identifier)
    }

    /**
     * The [#128](https://github.com/uny/autograph/issues/128) veto has to cover the expanded margin
     * too: a disabled element consumes the pointer out there exactly as it does inside its bounds, so
     * the answer is still no event rather than the enabled sheet underneath.
     */
    @Test
    fun aDisabledClickableSwallowsTapsInItsExpandedTargetAsWell() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("sheet").size(200.dp).clickable { fired = "sheet" }) {
                Box(
                    Modifier.offset(x = 50.dp, y = 50.dp).testTag("icon").requiredSize(16.dp)
                        .clickable(enabled = false) { fired = "icon" },
                )
            }
        }
        waitForIdle()

        val besideTheDisabledIcon = Offset(42f, 58f)
        onRoot().performTouchInput { down(besideTheDisabledIcon); up() }
        waitForIdle()
        assertNull(fired, "precondition: the disabled icon consumes the tap out here too")
        assertNull(semanticsRoot().resolveTapAt(besideTheDisabledIcon))

        val beyondItsTarget = Offset(20f, 58f)
        onRoot().performTouchInput { down(beyondItsTarget); up() }
        waitForIdle()
        assertEquals("sheet", fired, "precondition: past the target the sheet takes it")
        assertEquals("sheet", semanticsRoot().resolveTapAt(beyondItsTarget)?.identifier)
    }

    /**
     * `toggleable` publishes the same click action and gets the same expansion, so it is covered by
     * the same rule — pinned because it reaches the walk through a different modifier.
     */
    @Test
    fun aSmallToggleableIsReachedThroughItsExpandedTargetToo() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("stage").size(200.dp).clickable { fired = "stage" }) {
                Box(
                    Modifier.offset(x = 60.dp, y = 60.dp).testTag("toggle").size(16.dp)
                        .toggleable(value = false, onValueChange = { fired = "toggle" }),
                )
            }
        }
        waitForIdle()

        val besideTheToggle = Offset(52f, 68f)
        onRoot().performTouchInput { down(besideTheToggle); up() }
        waitForIdle()
        assertEquals("toggle", fired, "precondition")
        assertEquals("toggle", semanticsRoot().resolveTapAt(besideTheToggle)?.identifier)
    }

    /**
     * A `clickable(enabled = false)` really does swallow the pointer — Compose fires nothing, not the
     * enabled sibling underneath it — so the only honest answer is no event at all. Reporting the
     * disabled element was a click that never happened; falling through to `button` would be worse
     * still, naming an element the tap never reached.
     */
    @Test
    fun aDisabledClickableSwallowsTheTapAndReportsNothing() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.size(100.dp)) {
                Box(Modifier.testTag("button").size(50.dp).clickable { fired = "button" })
                // Declared second, so drawn on top: it covers the enabled button entirely.
                Box(
                    Modifier.testTag("disabled").size(50.dp)
                        .autocaptureScope("disabled" to "yes")
                        .clickable(enabled = false) { fired = "disabled" },
                )
            }
        }
        waitForIdle()

        val point = Offset(25f, 25f)
        onRoot().performTouchInput { down(point); up() }
        waitForIdle()
        assertNull(fired, "precondition: Compose fires nothing — the disabled element consumed the pointer")

        assertNull(semanticsRoot().resolveTapAt(point), "so neither the disabled element nor the button may be reported")
    }

    /**
     * The case that decides the veto's *shape*: a disabled child blocks its enabled clickable
     * ancestor as well, so the walk must stop at the disabled element rather than skip past it to
     * the ancestor. Skipping would report `outer` for a tap that fired nothing — trading a phantom
     * event for a wrong one, which this library treats as strictly worse.
     */
    @Test
    fun aDisabledChildBlocksItsEnabledAncestorSoTheTapResolvesToNothing() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("outer").size(100.dp).clickable { fired = "outer" }) {
                Box(Modifier.testTag("disabled").size(50.dp).clickable(enabled = false) { fired = "disabled" })
            }
        }
        waitForIdle()

        val onTheDisabledChild = Offset(25f, 25f)
        onRoot().performTouchInput { down(onTheDisabledChild); up() }
        waitForIdle()
        assertNull(fired, "precondition: the disabled child swallows the tap, its enabled ancestor included")
        assertNull(semanticsRoot().resolveTapAt(onTheDisabledChild))

        // ...while the rest of the ancestor is unaffected, which is what makes the above a veto and
        // not a subtree-wide exclusion.
        val elsewhereOnTheAncestor = Offset(80f, 80f)
        onRoot().performTouchInput { down(elsewhereOnTheAncestor); up() }
        waitForIdle()
        assertEquals("outer", fired, "precondition: Compose routes this one to the ancestor")
        assertEquals("outer", semanticsRoot().resolveTapAt(elsewhereOnTheAncestor)?.identifier)
    }

    /**
     * The mirror image, and why the veto is confined to the node being returned: a disabled
     * *container* does not block an enabled clickable child. Compose fires the child, so a
     * subtree-wide veto would drop a real click.
     */
    @Test
    fun aDisabledAncestorDoesNotSuppressAnEnabledClickableChild() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Box(Modifier.testTag("outer").size(100.dp).clickable(enabled = false) { fired = "outer" }) {
                Box(Modifier.testTag("inner").size(50.dp).clickable { fired = "inner" })
            }
        }
        waitForIdle()

        val point = Offset(25f, 25f)
        onRoot().performTouchInput { down(point); up() }
        waitForIdle()
        assertEquals("inner", fired, "precondition: the enabled child still receives the tap")

        assertEquals("inner", semanticsRoot().resolveTapAt(point)?.identifier)
    }

    /**
     * A disabled `clickable` drawn outside its parent covers a neighbouring row and swallows its
     * tap. Before [#126](https://github.com/uny/autograph/issues/126)'s walk the badge was invisible
     * to the hit test and the tap was attributed to `row1`; now the badge is reached, and being
     * disabled it resolves to nothing — which is what Compose does.
     */
    @Test
    fun aDisabledOverhangSwallowsTheCoveredRowsTapWithoutBeingReported() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Column(Modifier.size(200.dp)) {
                Box(Modifier.testTag("row1").size(200.dp, 100.dp).clickable { fired = "row1" })
                Box(
                    Modifier.testTag("row2").size(200.dp, 100.dp)
                        .autocaptureScope("row" to "2").clickable { fired = "row2" },
                ) {
                    Box(
                        Modifier.offset(y = (-60).dp).testTag("badge").requiredSize(40.dp)
                            .clickable(enabled = false) { fired = "badge" },
                    )
                }
            }
        }
        waitForIdle()

        val overlap = Offset(20f, 60f)
        onRoot().performTouchInput { down(overlap); up() }
        waitForIdle()
        assertNull(fired, "precondition: the disabled badge consumes the tap, so neither row fires")

        assertNull(semanticsRoot().resolveTapAt(overlap), "neither the badge, nor row1 under it, nor row2 above it")
    }

    /**
     * The one shape the veto costs, pinned so it stays a known gap rather than becoming a surprise:
     * a live `Modifier.clickable` whose semantics were hand-marked `disabled()` does fire, and is
     * dropped. The declaration contradicts itself — the accessibility tree says disabled while the
     * pointer input is live — and semantics carries no way to tell that apart from a real
     * `clickable(enabled = false)`, both keys landing on one node's config in either modifier order.
     * A drop is the right side to err on; if this ever stops being a drop, this test says so.
     */
    @Test
    fun anEnabledClickableHandMarkedDisabledIsDroppedInEitherModifierOrder() = runComposeUiTest {
        var fired: String? = null
        setContent {
            Column {
                Box(
                    Modifier.testTag("clickThenDisabled").size(50.dp)
                        .clickable { fired = "clickThenDisabled" }
                        .semantics { disabled() },
                )
                Box(
                    Modifier.testTag("disabledThenClick").size(50.dp)
                        .semantics { disabled() }
                        .clickable { fired = "disabledThenClick" },
                )
            }
        }
        waitForIdle()

        for (tag in listOf("clickThenDisabled", "disabledThenClick")) {
            val centre = centreOf(tag)
            fired = null
            onRoot().performTouchInput { down(centre); up() }
            waitForIdle()
            assertEquals(tag, fired, "precondition: the pointer input is live, so Compose does fire")
            assertNull(semanticsRoot().resolveTapAt(centre), "but the hand-written disabled() drops it")
        }
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
