@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [ScopeStack.globalScope] — the narrow read an explicit emit may use (#250). What it must return is
 * settled by what it exists to avoid: everything the ambient snapshot would add.
 */
class ScopeStackGlobalScopeTest {

    private fun props(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun it_is_empty_when_nothing_global_is_pushed() {
        val stack = ScopeStack()
        stack.push(screen = "Home", scope = props("row" to "3"))
        assertEquals(JsonObject(emptyMap()), stack.globalScope)
    }

    @Test
    fun it_returns_global_frames_and_nothing_else() {
        // The whole point: a declaration's scope and a surface's scope are visible to the ambient
        // read and must not be visible here.
        val stack = ScopeStack()
        stack.pushGlobal(props("tenant" to "acme"))
        val surface = stack.pushSurface(screen = "Feed", scope = props("surface" to "s"))
        stack.push(parent = surface, scope = props("row" to "3"))

        assertEquals(props("tenant" to "acme"), stack.globalScope)
        assertEquals("s", (stack.current().scope["surface"] as? JsonPrimitive)?.content, "the ambient read does see them")
    }

    @Test
    fun several_global_frames_merge_in_insertion_order() {
        val stack = ScopeStack()
        stack.pushGlobal(props("tenant" to "acme", "ring" to "a"))
        stack.pushGlobal(props("ring" to "b"))
        assertEquals(props("tenant" to "acme", "ring" to "b"), stack.globalScope)
    }

    @Test
    fun a_removed_global_frame_stops_contributing() {
        val stack = ScopeStack()
        val handle = stack.pushGlobal(props("tenant" to "acme"))
        stack.remove(handle)
        assertEquals(JsonObject(emptyMap()), stack.globalScope)
    }

    @Test
    fun an_inactive_global_frame_contributes_nothing() {
        // Respects the active bit exactly as every other read does.
        val stack = ScopeStack()
        val handle = stack.pushGlobal(props("tenant" to "acme"))
        stack.setActive(handle, false)
        assertEquals(JsonObject(emptyMap()), stack.globalScope)

        stack.setActive(handle, true)
        assertEquals(props("tenant" to "acme"), stack.globalScope)
    }

    @Test
    fun an_updated_global_frame_reads_back_its_new_scope() {
        val stack = ScopeStack()
        val handle = stack.pushGlobal(props("tenant" to "acme"))
        stack.update(handle, scope = props("tenant" to "globex"))
        assertEquals(props("tenant" to "globex"), stack.globalScope)
    }
}
