package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [ScopeStack.setScreenMasked] and [ScopeStack.reparent] — the two revisions that used to be missing
 * or folded into [ScopeStack.update] (#255): lifting a mask, and moving a frame without restating
 * what it says.
 */
class ScopeStackRevisionTest {

    private fun props(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    // --- setScreenMasked ---------------------------------------------------------------------------

    @Test
    fun lifting_a_mask_lets_the_screen_beneath_show_through_again() {
        val stack = ScopeStack()
        stack.push(screen = "Feed", section = "top")
        val container = stack.push()
        stack.setScreenMasked(container, true)
        assertNull(stack.current().screen)

        stack.setScreenMasked(container, false)
        assertEquals("Feed", stack.current().screen)
        assertEquals("top", stack.current().section)
        assertFalse(stack.current().screenMasked)
    }

    @Test
    fun a_screen_written_under_a_mask_resolves_once_the_mask_is_lifted() {
        // The order setScreenMasked's kdoc prescribes for masking -> naming: name first, lift second,
        // so the snapshot in between still masks rather than showing the screen underneath.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val container = stack.push()
        stack.maskScreen(container)

        stack.update(container, screen = "Settings", section = "account")
        assertNull(stack.current().screen, "the mask still wins while it is up")
        assertTrue(stack.current().screenMasked)

        stack.setScreenMasked(container, false)
        assertEquals("Settings", stack.current().screen)
        assertEquals("account", stack.current().section)
    }

    @Test
    fun lifting_a_mask_applies_to_an_origin_read_as_well() {
        val stack = ScopeStack()
        val host = stack.pushSurface(screen = "Host")
        stack.maskScreen(host)
        assertNull(stack.current(host).screen)

        stack.setScreenMasked(host, false)
        assertEquals("Host", stack.current(host).screen)
    }

    @Test
    fun masking_and_lifting_never_change_whether_the_frame_is_active() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val page = stack.push(screen = "Page")
        stack.setActive(page, false)

        stack.setScreenMasked(page, true)
        assertEquals("Feed", stack.current().screen, "a demoted mask masks nothing")
        stack.setScreenMasked(page, false)
        assertEquals("Feed", stack.current().screen, "lifting a mask must not revive a demoted frame")
    }

    @Test
    fun setScreenMasked_is_a_no_op_for_an_unchanged_value_a_removed_handle_or_a_foreign_one() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        val removed = stack.push()
        stack.remove(removed)
        val before = stack.current()

        stack.setScreenMasked(mask, true)
        stack.setScreenMasked(removed, false)
        stack.setScreenMasked(removed, true)
        stack.setScreenMasked(ScopeStack().push(), false)
        assertSame(before, stack.current(), "nothing changed, so nothing is republished")

        val unmasked = stack.push(screen = "Detail")
        val afterPush = stack.current()
        stack.setScreenMasked(unmasked, false)
        assertSame(afterPush, stack.current())
    }

    // --- reparent ----------------------------------------------------------------------------------

    @Test
    fun reparent_moves_the_link_and_keeps_what_the_frame_says() {
        // The contrast with update: `update(handle, parent = p)` replaces the contents with its
        // defaults, so a caller had to restate them. reparent touches the link only.
        val stack = ScopeStack()
        val route = stack.push(scope = props("route" to "home"))
        val detached = stack.push(scope = props("row" to "1"), screen = "Detail", section = "body")
        // Two roots: each other's ambiguous sibling, so neither scope attributes.
        assertEquals(JsonObject(emptyMap()), stack.current().scope)

        stack.reparent(detached, route)
        assertEquals(props("route" to "home", "row" to "1"), stack.current().scope)
        assertEquals("Detail", stack.current().screen)
        assertEquals("body", stack.current().section)
    }

    @Test
    fun reparent_to_null_makes_the_frame_a_root() {
        val stack = ScopeStack()
        val route = stack.push(scope = props("route" to "home"))
        val row = stack.push(scope = props("row" to "1"), parent = route)
        assertEquals(props("route" to "home", "row" to "1"), stack.current().scope)

        stack.reparent(row, null)
        assertEquals(JsonObject(emptyMap()), stack.current().scope, "two roots are ambiguous siblings")
    }

    @Test
    fun reparent_adopts_a_frame_late_without_restating_it() {
        // ScopeStackOriginTest's late-adoption case, where update(parent =) forced the caller to
        // restate `screen = "Home"`: the container still ranks with its content, and the content —
        // unrestated — still names the screen.
        val stack = ScopeStack()
        val home = stack.push(screen = "Home")
        val late = stack.pushSurface()
        stack.maskScreen(late)
        stack.reparent(home, late)

        assertEquals("Home", stack.current(home).screen)
        assertFalse(stack.current(home).screenMasked)
    }

    /** Like update, reparent refuses a cycle — this test HANGS rather than fails if the guard regresses. */
    @Test
    fun reparent_under_itself_or_a_descendant_is_refused_and_the_frame_becomes_a_root() {
        val stack = ScopeStack()
        val outer = stack.push(scope = props("a" to "outer"))
        val inner = stack.push(scope = props("b" to "inner"), parent = outer)

        stack.reparent(outer, inner)
        assertEquals(props("a" to "outer", "b" to "inner"), stack.current().scope, "outer -> inner still merges")

        stack.reparent(inner, inner)
        assertEquals(JsonObject(emptyMap()), stack.current().scope, "inner is now a root beside outer")
    }

    @Test
    fun a_global_frame_refuses_every_parent() {
        val stack = ScopeStack()
        val a = stack.pushSurface(screen = "A")
        val b = stack.pushSurface(screen = "B")
        val global = stack.pushGlobal(props("tenant_id" to "acme"))

        stack.reparent(global, a)
        assertEquals(props("tenant_id" to "acme"), stack.current(b).scope, "still seen from a sibling origin")
    }

    @Test
    fun reparent_never_changes_the_mask_or_the_active_bit() {
        val stack = ScopeStack()
        val host = stack.push(screen = "Host")
        val mask = stack.push()
        stack.maskScreen(mask)
        val page = stack.push(screen = "Page")
        stack.setActive(page, false)

        stack.reparent(mask, host)
        stack.reparent(page, host)
        assertNull(stack.current().screen, "still masked, and the demoted page is still out")
        assertTrue(stack.current().screenMasked)
    }

    @Test
    fun reparent_is_a_no_op_for_an_unchanged_link_a_removed_handle_or_a_foreign_one() {
        val stack = ScopeStack()
        val route = stack.push(scope = props("route" to "home"))
        val row = stack.push(scope = props("row" to "1"), parent = route)
        val removed = stack.push()
        stack.remove(removed)
        val before = stack.current()

        stack.reparent(row, route)
        stack.reparent(removed, route)
        stack.reparent(ScopeStack().push(), route)
        assertSame(before, stack.current(), "nothing changed, so nothing is republished")
    }
}
