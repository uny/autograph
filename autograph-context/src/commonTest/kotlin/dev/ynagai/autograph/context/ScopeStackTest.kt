package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ScopeStackTest {

    private fun props(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun empty_stack_enriches_to_identity() {
        val stack = ScopeStack()
        val ctx = stack.current()
        assertNull(ctx.screen)
        assertNull(ctx.section)
        assertEquals(props("a" to "1"), ctx.enrich(props("a" to "1")))
    }

    @Test
    fun scope_is_a_default_that_call_site_overrides() {
        val stack = ScopeStack()
        stack.push(scope = props("article_id" to "42", "source" to "scope"))
        val enriched = stack.current().enrich(props("source" to "callsite"))
        // scope fills article_id; explicit call-site "source" wins over the scope's.
        assertEquals(props("article_id" to "42", "source" to "callsite"), enriched)
    }

    @Test
    fun nested_scopes_accumulate_inner_overrides_outer() {
        val stack = ScopeStack()
        stack.push(scope = props("a" to "outer", "b" to "outer"))
        stack.push(scope = props("b" to "inner", "c" to "inner"))
        assertEquals(
            props("a" to "outer", "b" to "inner", "c" to "inner"),
            stack.current().enrich(JsonObject(emptyMap())),
        )
    }

    @Test
    fun screen_and_section_are_reserved_and_beat_call_site() {
        val stack = ScopeStack()
        stack.push(screen = "Article", section = "Body")
        val enriched = stack.current().enrich(props("screen" to "wrong", "section" to "wrong", "x" to "keep"))
        assertEquals(props("screen" to "Article", "section" to "Body", "x" to "keep"), enriched)
    }

    @Test
    fun innermost_screen_wins_section_tracks_independently() {
        val stack = ScopeStack()
        stack.push(screen = "Outer")           // outer screen, no section
        stack.push(section = "Header")         // inner section marker refines the outer screen
        val ctx = stack.current()
        assertEquals("Outer", ctx.screen)
        assertEquals("Header", ctx.section)
    }

    @Test
    fun removal_is_by_identity_not_lifo() {
        val stack = ScopeStack()
        val outer = stack.push(scope = props("a" to "1"))
        stack.push(scope = props("b" to "2"))
        // Remove the OUTER frame while the inner one is still present (non-LIFO order).
        stack.remove(outer)
        assertEquals(props("b" to "2"), stack.current().enrich(JsonObject(emptyMap())))
    }

    @Test
    fun removing_the_screen_frame_reverts_to_the_enclosing_screen() {
        val stack = ScopeStack()
        stack.push(screen = "Home")
        val detail = stack.push(screen = "Detail")
        assertEquals("Detail", stack.current().screen)
        stack.remove(detail)
        assertEquals("Home", stack.current().screen)
    }

    @Test
    fun removing_an_unknown_or_double_removed_handle_is_a_noop() {
        val stack = ScopeStack()
        val a = stack.push(scope = props("a" to "1"))
        stack.remove(a)
        stack.remove(a)                        // double remove: no-op
        stack.remove(ScopeStack().push())      // handle from another stack: no-op
        assertEquals(JsonObject(emptyMap()), stack.current().enrich(JsonObject(emptyMap())))
    }

    @Test
    fun mixed_frame_carries_scope_and_screen_together() {
        val stack = ScopeStack()
        stack.push(scope = props("article_id" to "7"), screen = "Article")
        val enriched = stack.current().enrich(props("action" to "like"))
        assertEquals(
            props("article_id" to "7", "action" to "like", "screen" to "Article"),
            enriched,
        )
    }
}
