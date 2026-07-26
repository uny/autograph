package dev.ynagai.autograph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ynagai.autograph.EmptyJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private data class TestNode(
    val name: String,
    val bounds: Rect,
    val children: List<TestNode> = emptyList(),
    val clickable: Boolean = false,
    /** The expanded minimum touch target, when this node is small enough to have one. */
    val touchTarget: Rect? = null,
)

/** Mirrors the Compose-side rule: inclusive edges, squared distance from the node's drawn rect. */
private fun TestNode.minTargetDistanceSquared(point: Offset): Float? {
    val target = touchTarget ?: return null
    val inside = point.x >= target.left && point.x <= target.right &&
        point.y >= target.top && point.y <= target.bottom
    if (!inside) return null
    val dx = maxOf(bounds.left - point.x, 0f, point.x - bounds.right)
    val dy = maxOf(bounds.top - point.y, 0f, point.y - bounds.bottom)
    return dx * dx + dy * dy
}

private fun findClickableHit(root: TestNode, point: Offset): TestNode? = findClickableHit(
    root,
    point,
    bounds = { it.bounds },
    minTargetDistanceSquared = { node, at -> node.minTargetDistanceSquared(at) },
    children = { it.children },
    clickable = { it.clickable },
)

private fun scope(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

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
    fun findDeepestHitDoesNotDescendPastAParentWhoseBoundsMissThePoint() {
        // Deliberate, and load-bearing — but as the SECOND stage only, run from the element
        // findClickableHit already picked. There it bounds the chain: every node it returns below
        // that element is one the point is genuinely inside, so a scope or an ignore collected from
        // below the reported element belongs to the tap.
        //
        // Run from the root it would instead model DRAWING while resolveAutocaptureTarget walks UP
        // to a clickable, so an overhanging decoration inside row2 covering row1 would hand row1's
        // tap to row2 — a wrong target. That is why the entry point is findClickableHit.
        val overflowing = TestNode("badge", Rect(40f, 0f, 50f, 10f))
        val parent = TestNode("row", Rect(0f, 0f, 10f, 10f), children = listOf(overflowing))
        val root = TestNode("screen", Rect(0f, 0f, 100f, 100f), children = listOf(parent))

        val hit = findDeepestHit(root, Offset(45f, 5f), bounds = { it.bounds }, children = { it.children })

        assertEquals("screen", hit?.name, "the overhanging child is out of reach of this walk")
    }

    @Test
    fun findClickableHitReachesAClickableDrawnOutsideItsParent() {
        // The same tree, entered through stage 1: the parent's bounds no longer gate the descent,
        // because Compose routes the real pointer to the overhanging child too.
        val overflowing = TestNode("badge", Rect(40f, 0f, 50f, 10f), clickable = true)
        val parent = TestNode("row", Rect(0f, 0f, 10f, 10f), children = listOf(overflowing))
        val root = TestNode("screen", Rect(0f, 0f, 100f, 100f), children = listOf(parent))

        assertEquals("badge", findClickableHit(root, Offset(45f, 5f))?.name)
    }

    @Test
    fun findClickableHitSkipsANodeWhoseOwnBoundsMissThePoint() {
        // The other half of the rule, and what keeps descending everywhere from misattributing: an
        // overhanging DECORATION is entered but wins nothing, so the clickable row it covers keeps
        // its own tap rather than handing it to the decoration's row.
        val decoration = TestNode("avatar", Rect(0f, 40f, 40f, 80f))
        val row2 = TestNode("row2", Rect(0f, 100f, 200f, 200f), children = listOf(decoration), clickable = true)
        val row1 = TestNode("row1", Rect(0f, 0f, 200f, 100f), clickable = true)
        val root = TestNode("screen", Rect(0f, 0f, 200f, 200f), children = listOf(row1, row2))

        assertEquals("row1", findClickableHit(root, Offset(20f, 60f))?.name)
    }

    @Test
    fun findClickableHitTakesTheInnermostClickableWithinOneBranch() {
        // Children before self, and later siblings before earlier ones — the same ordering
        // findDeepestHit uses, so the visually topmost element still takes an overlapping tap.
        val inner = TestNode("inner", Rect(0f, 0f, 20f, 20f), clickable = true)
        val behind = TestNode("behind", Rect(0f, 0f, 50f, 50f), clickable = true)
        val front = TestNode("front", Rect(0f, 0f, 50f, 50f), children = listOf(inner), clickable = true)
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(behind, front), clickable = true)

        assertEquals("inner", findClickableHit(root, Offset(10f, 10f))?.name)
        assertEquals("front", findClickableHit(root, Offset(30f, 30f))?.name, "topmost of the overlapping pair")
        assertEquals("card", findClickableHit(root, Offset(70f, 70f))?.name, "outside both, the container takes it")
    }

    @Test
    fun findClickableHitLetsTheTopmostBranchWinOverADeeperClickableBeneathIt() {
        // Depth decides only WITHIN a branch; between branches, stacking does. `front` is a shallow
        // clickable drawn over `behind`, which holds a deeper one — and `front` still takes the tap,
        // because a pointer stops at the topmost element that accepts it however deep the covered
        // subtree goes. Pins the ordering the walk actually has: "deepest, then topmost" would pick
        // `deep` here and be wrong about what Compose does.
        val deep = TestNode("deep", Rect(0f, 0f, 50f, 50f), clickable = true)
        val middle = TestNode("middle", Rect(0f, 0f, 50f, 50f), children = listOf(deep))
        val behind = TestNode("behind", Rect(0f, 0f, 50f, 50f), children = listOf(middle))
        val front = TestNode("front", Rect(0f, 0f, 50f, 50f), clickable = true)
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(behind, front))

        assertEquals("front", findClickableHit(root, Offset(25f, 25f))?.name)
    }

    @Test
    fun findClickableHitReturnsNullWhenNothingClickableIsUnderThePoint() {
        val leaf = TestNode("label", Rect(0f, 0f, 20f, 20f))
        val root = TestNode("card", Rect(0f, 0f, 100f, 100f), children = listOf(leaf))

        assertNull(findClickableHit(root, Offset(10f, 10f)))
    }

    @Test
    fun findDeepestHitSkipsAClippedOutSiblingBecauseItsBoundsAreEmpty() {
        // What keeps a scrolled-off row from taking a tap is its bounds: boundsInWindow is already
        // clipped to Rect.Zero by the scrolling ancestor, and Rect.Zero contains no point at all —
        // so it is skipped even though asReversed() tries it first.
        val scrolledOff = TestNode("row2", Rect.Zero)
        val visible = TestNode("row0", Rect(0f, 0f, 50f, 50f))
        val root = TestNode("list", Rect(0f, 0f, 50f, 50f), children = listOf(visible, scrolledOff))

        val hit = findDeepestHit(root, Offset(25f, 25f), bounds = { it.bounds }, children = { it.children })

        assertEquals("row0", hit?.name)
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
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "share_button", clickable = true, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "card", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertEquals("share_button", resolveAutocaptureTarget(chain)?.identifier)
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenNothingInTheChainIsClickable() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "card", clickable = false, ignored = false, instrumented = false, disabled = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenTheHitNodeItselfIsInstrumented() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = "inner", clickable = true, ignored = false, instrumented = true, disabled = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenTheReturnedClickableIsDisabled() {
        // clickable(enabled = false) publishes the click action too, so it is picked as the nearest
        // clickable — and reporting it would be a click that never fired.
        val chain = sequenceOf(
            AutocaptureNode(identifier = "disabled", clickable = true, ignored = false, instrumented = false, disabled = true),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetStopsAtADisabledClickableRatherThanWalkingUpToAnEnabledAncestor() {
        // The veto must not degrade into "skip it and keep looking": a disabled element blocks its
        // enabled clickable ancestor too (measured — Compose fires nothing), so reporting the
        // ancestor would name an element the tap never reached.
        val chain = sequenceOf(
            AutocaptureNode(identifier = "disabled", clickable = true, ignored = false, instrumented = false, disabled = true),
            AutocaptureNode(identifier = "outer", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetIgnoresADisabledAncestorAboveTheReturnedClickable() {
        // The mirror image, and why the veto is confined to the returned node: a disabled container
        // does NOT block an enabled clickable child (measured — Compose fires the child), so it must
        // not suppress it either.
        val chain = sequenceOf(
            AutocaptureNode(identifier = "inner", clickable = true, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "outer", clickable = true, ignored = false, instrumented = false, disabled = true),
        )
        assertEquals("inner", resolveAutocaptureTarget(chain)?.identifier)
    }

    @Test
    fun resolveAutocaptureTargetIgnoresADisabledDescendantThatIsNotTheReturnedClickable() {
        // A disabled non-clickable leaf inside a live row (a greyed-out label, say) must not take the
        // row's tap away from it.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = true),
            AutocaptureNode(identifier = "row", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertEquals("row", resolveAutocaptureTarget(chain)?.identifier)
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenAnAncestorBetweenTheHitNodeAndTheClickableIsIgnored() {
        // autographIgnore()'s own marker on an intermediate node must veto the tap even though a
        // clickable ancestor exists further up.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = true, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "share_button", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetReturnsNullWhenAnAncestorAboveTheClickableIsIgnored() {
        // autographIgnore() is subtree-wide: a container ABOVE an already-clickable descendant
        // (e.g. Box(Modifier.autographIgnore()) { Button(...) }) must still suppress the tap, even
        // though the walk would otherwise return the clickable's identifier before reaching it.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = "button", clickable = true, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = true, instrumented = false, disabled = false),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    @Test
    fun resolveAutocaptureTargetIgnoresAnInstrumentedDescendantThatIsNotTheReturnedClickable() {
        // trackImpression() sets `instrumented` on a non-clickable descendant (e.g. an inner
        // Image). It must NOT veto an outer plain Modifier.clickable that was never itself
        // instrumented -- only the node actually being returned should be checked.
        val chain = sequenceOf(
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false, disabled = false),
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = true, disabled = false),
            AutocaptureNode(identifier = "card", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertEquals("card", resolveAutocaptureTarget(chain)?.identifier)
    }

    @Test
    fun resolveAutocaptureTargetCarriesNoScopeWhenNothingOnTheChainDeclaresOne() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = "button", clickable = true, ignored = false, instrumented = false, disabled = false),
        )
        assertEquals(EmptyJsonObject, resolveAutocaptureTarget(chain)?.scope)
    }

    @Test
    fun resolveAutocaptureTargetMergesScopesAlongTheChainInnerWinningAClash() {
        // The chain is a single ancestry path, so nested autocaptureScope()s are never ambiguous the
        // way simultaneously-mounted AutographScope siblings are: they compose outer -> inner, and the
        // inner value wins a shared key — the same rule nested scopes follow everywhere else.
        val chain = sequenceOf(
            AutocaptureNode(
                identifier = "row",
                clickable = true,
                ignored = false,
                instrumented = false,
                disabled = false,
                scope = scope("article_id" to "42", "surface" to "row"),
            ),
            AutocaptureNode(
                identifier = null,
                clickable = false,
                ignored = false,
                instrumented = false,
                disabled = false,
                scope = scope("section" to "for_you", "surface" to "list"),
            ),
        )

        val resolved = resolveAutocaptureTarget(chain)

        assertEquals("row", resolved?.identifier)
        assertEquals("42", resolved?.scope?.str("article_id"))
        assertEquals("for_you", resolved?.scope?.str("section"), "the enclosing scope must still contribute")
        assertEquals("row", resolved?.scope?.str("surface"), "the inner scope must win a shared key")
    }

    @Test
    fun resolveAutocaptureTargetCollectsAScopeDeclaredBelowTheReportedClickable() {
        // autocaptureScope is subtree-wide like autographIgnore: "taps under here carry this". A
        // scope on a non-clickable leaf inside a clickable row therefore still contributes, rather
        // than being silently lost because the reported element sits above it.
        val chain = sequenceOf(
            AutocaptureNode(
                identifier = null,
                clickable = false,
                ignored = false,
                instrumented = false,
                disabled = false,
                scope = scope("part" to "avatar"),
            ),
            AutocaptureNode(identifier = "row", clickable = true, ignored = false, instrumented = false, disabled = false),
        )

        val resolved = resolveAutocaptureTarget(chain)

        assertEquals("row", resolved?.identifier)
        assertEquals("avatar", resolved?.scope?.str("part"))
    }

    @Test
    fun resolveAutocaptureTargetReportsNoScopeAtAllWhenTheChainIsIgnored() {
        // A scope must never leak out of a subtree the caller excluded from autocapture: the veto is
        // on the whole tap, not just on the identifier.
        val chain = sequenceOf(
            AutocaptureNode(
                identifier = "row",
                clickable = true,
                ignored = true,
                instrumented = false,
                disabled = false,
                scope = scope("article_id" to "42"),
            ),
        )
        assertNull(resolveAutocaptureTarget(chain))
    }

    // ---- minimum touch target ranking (#127) ----

    @Test
    fun findClickableHitPrefersAMinimumTargetDescendantOverItsAncestorsDefiniteHit() {
        // The #127 shape: a small icon inside a large clickable surface. Compose fires the icon for a
        // tap in its expanded margin even though the sheet's own bounds contain the point.
        val icon = TestNode(
            "icon",
            Rect(50f, 50f, 66f, 66f),
            clickable = true,
            touchTarget = Rect(34f, 34f, 82f, 82f),
        )
        val sheet = TestNode("sheet", Rect(0f, 0f, 200f, 200f), children = listOf(icon), clickable = true)

        assertEquals("icon", findClickableHit(sheet, Offset(58f, 58f))?.name, "inside the icon")
        assertEquals("icon", findClickableHit(sheet, Offset(42f, 58f))?.name, "8px outside, in its target")
        assertEquals("sheet", findClickableHit(sheet, Offset(20f, 58f))?.name, "beyond the target, the sheet")
    }

    @Test
    fun findClickableHitPrefersADefiniteHitBelowOverAMinimumTargetHitDrawnOnTop() {
        // `small` is declared last, so it is drawn on top, and its expanded target covers the point.
        // A definite hit outranks a minimum-target hit regardless of stacking, so `big` keeps the tap.
        val big = TestNode("big", Rect(0f, 0f, 120f, 120f), clickable = true)
        val small = TestNode(
            "small",
            Rect(120f, 40f, 136f, 56f),
            clickable = true,
            touchTarget = Rect(104f, 24f, 152f, 72f),
        )
        val stage = TestNode("stage", Rect(0f, 0f, 200f, 200f), children = listOf(big, small))

        assertEquals("big", findClickableHit(stage, Offset(110f, 48f))?.name)
        assertEquals("small", findClickableHit(stage, Offset(125f, 48f))?.name, "inside its own bounds")
    }

    @Test
    fun findClickableHitLetsAnAncestorsDefiniteHitSettleTheBranchWhileReportingTheDeeperNode() {
        // `parent` is drawn over `sibling`; both contain the point. The reported node is `child`,
        // hit only through its expanded target — but `parent`'s own definite hit is what stops the
        // scan before `sibling` is ever considered.
        val child = TestNode(
            "child",
            Rect(100f, 40f, 116f, 56f),
            clickable = true,
            touchTarget = Rect(84f, 24f, 132f, 72f),
        )
        val parent = TestNode("parent", Rect(0f, 0f, 120f, 120f), children = listOf(child), clickable = true)
        val sibling = TestNode("sibling", Rect(0f, 0f, 120f, 120f), clickable = true)
        val settled = TestNode("stage", Rect(0f, 0f, 200f, 200f), children = listOf(sibling, parent))

        assertEquals("child", findClickableHit(settled, Offset(90f, 48f))?.name)

        // With the same parent non-clickable its branch never settles, so the scan reaches `sibling`
        // and that definite hit outranks the child's minimum-target one.
        val unsettled = settled.copy(
            children = listOf(sibling, parent.copy(clickable = false)),
        )
        assertEquals("sibling", findClickableHit(unsettled, Offset(90f, 48f))?.name)
    }

    @Test
    fun findClickableHitRanksCompetingMinimumTargetsByDistanceNotByStacking() {
        // Expanded targets overlap between x 44 and 52. `b` is drawn on top, but at x 46 the point is
        // nearer `a`, and Compose fires `a`. Stacking only breaks a tie.
        val a = TestNode("a", Rect(20f, 50f, 36f, 66f), clickable = true, touchTarget = Rect(4f, 34f, 52f, 82f))
        val b = TestNode("b", Rect(60f, 50f, 76f, 66f), clickable = true, touchTarget = Rect(44f, 34f, 92f, 82f))
        val stage = TestNode("stage", Rect(0f, 0f, 200f, 200f), children = listOf(a, b))

        assertEquals("a", findClickableHit(stage, Offset(46f, 58f))?.name, "10px from a, 14px from b")
        assertEquals("b", findClickableHit(stage, Offset(50f, 58f))?.name, "14px from a, 10px from b")
        assertEquals("b", findClickableHit(stage, Offset(48f, 58f))?.name, "equidistant — the topmost wins")
    }

    @Test
    fun findClickableHitDoesNotReachANodeThatHasNoExpandedTarget() {
        // Anything already at least the minimum touch target on both axes is never hit outside its
        // own bounds, so a null target must not be read as an unbounded one.
        val big = TestNode("big", Rect(0f, 0f, 120f, 120f), clickable = true)
        val stage = TestNode("stage", Rect(0f, 0f, 200f, 200f), children = listOf(big))

        assertEquals("big", findClickableHit(stage, Offset(119f, 60f))?.name)
        assertNull(findClickableHit(stage, Offset(121f, 60f)))
    }

    @Test
    fun identifierFromPrefersTestTagOverRoleOverLabel() {
        assertEquals("tag", identifierFrom(testTag = "tag", role = "Button", label = "Share"))
        assertEquals("Button", identifierFrom(testTag = null, role = "Button", label = "Share"))
        assertEquals("Share", identifierFrom(testTag = null, role = null, label = "Share"))
        assertNull(identifierFrom(testTag = null, role = null, label = null))
    }

    @Test
    fun identifierFromTreatsBlankAsAbsentAndFallsThrough() {
        // An empty or whitespace-only value is skipped so the chain continues rather than reporting blank.
        assertEquals("Button", identifierFrom(testTag = "", role = "Button", label = "Share"))
        assertEquals("Button", identifierFrom(testTag = "   ", role = "Button", label = "Share"))
        assertEquals("Share", identifierFrom(testTag = "", role = "", label = "Share"))
        assertNull(identifierFrom(testTag = "", role = "", label = ""))
        // A value with content passes through byte-for-byte — surrounding whitespace is never trimmed.
        assertEquals(" spaced tag ", identifierFrom(testTag = " spaced tag ", role = null, label = null))
    }
}
