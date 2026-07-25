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

private data class TestNode(val name: String, val bounds: Rect, val children: List<TestNode> = emptyList())

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
        // Deliberate, and load-bearing. A child drawn outside its parent (offset/overhang) is NOT
        // reachable, so a tap there resolves to nothing — even though Compose does route a real
        // pointer to such a child. That is a known drop, tracked in #126.
        //
        // Descending anyway was measured and rejected: it makes the walk model DRAWING while
        // resolveAutocaptureTarget then walks UP to a clickable, so an overhanging decoration inside
        // row2 that visually covers row1 hands the tap to row2 — a WRONG target, where Compose
        // itself fired row1. This library trades a missing attribution for a wrong one every time
        // (see ScopeStack's kdoc), and the prune is what buys that here: every node on the returned
        // chain contains the point, so the reported clickable is one the pointer really was inside.
        val overflowing = TestNode("badge", Rect(40f, 0f, 50f, 10f))
        val parent = TestNode("row", Rect(0f, 0f, 10f, 10f), children = listOf(overflowing))
        val root = TestNode("screen", Rect(0f, 0f, 100f, 100f), children = listOf(parent))

        val hit = findDeepestHit(root, Offset(45f, 5f), bounds = { it.bounds }, children = { it.children })

        assertEquals("screen", hit?.name, "the overhanging child is out of reach; the walk stops above it")
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
            AutocaptureNode(identifier = null, clickable = false, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "share_button", clickable = true, ignored = false, instrumented = false),
            AutocaptureNode(identifier = "card", clickable = true, ignored = false, instrumented = false),
        )
        assertEquals("share_button", resolveAutocaptureTarget(chain)?.identifier)
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
        assertEquals("card", resolveAutocaptureTarget(chain)?.identifier)
    }

    @Test
    fun resolveAutocaptureTargetCarriesNoScopeWhenNothingOnTheChainDeclaresOne() {
        val chain = sequenceOf(
            AutocaptureNode(identifier = "button", clickable = true, ignored = false, instrumented = false),
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
                scope = scope("article_id" to "42", "surface" to "row"),
            ),
            AutocaptureNode(
                identifier = null,
                clickable = false,
                ignored = false,
                instrumented = false,
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
                scope = scope("part" to "avatar"),
            ),
            AutocaptureNode(identifier = "row", clickable = true, ignored = false, instrumented = false),
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
                scope = scope("article_id" to "42"),
            ),
        )
        assertNull(resolveAutocaptureTarget(chain))
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
