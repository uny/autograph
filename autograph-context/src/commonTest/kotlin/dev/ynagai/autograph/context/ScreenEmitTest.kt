@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a native `Screen Viewed` carries besides `previous_screen`: the scope resolved from the
 * emitting surface's own lineage (#238), the way a native tap resolves it — and nothing from a
 * sibling surface, which is the whole reason it reads from an origin rather than ambiently.
 */
class ScreenEmitTest {

    private class RecordingTracker : Tracker {
        val screens = mutableListOf<Pair<String, JsonObject>>()
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            screens += name to JsonObject(properties)
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }

    private fun props(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun a_scope_on_the_surfaces_own_lineage_reaches_its_screen_view() {
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        val root = stack.push(scope = props("tenant" to "acme"))
        val screen = stack.pushSurface(parent = root, screen = "Detail")

        stack.emitScreenView(tracker, "Detail", origin = screen)

        assertEquals(listOf("Detail" to props("tenant" to "acme")), tracker.screens)
    }

    @Test
    fun a_sibling_surfaces_scope_does_not_reach_this_surfaces_screen_view() {
        // Two surfaces side by side (two Activities, or a UIKit screen beside a hand-pushed boundary):
        // the sibling declares a scope, this one does not. Ambiently the sibling's scope is visible;
        // the screen view must not carry it.
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        val sibling = stack.pushSurface(screen = "Sibling")
        stack.push(parent = sibling, scope = props("row" to "3"))
        val screen = stack.pushSurface(screen = "Detail")
        assertEquals("3", (stack.current().scope["row"] as? JsonPrimitive)?.content, "ambient read sees the sibling scope")

        stack.emitScreenView(tracker, "Detail", origin = screen)

        assertEquals(listOf("Detail" to JsonObject(emptyMap())), tracker.screens)
    }

    @Test
    fun on_a_boundary_free_stack_the_ambiguity_rule_is_what_keeps_sibling_scopes_apart() {
        // The iOS shape: both native pipelines push plain roots (`push(screen = name)`, no boundary),
        // so nothing walls a sibling off and the screen view resolves the same members a native tap
        // does. One scoped sibling reaches it; two siblings the stack cannot choose between drop.
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        val first = stack.push(screen = "First")
        stack.push(parent = first, scope = props("row" to "3"))
        val second = stack.push(screen = "Second")

        stack.emitScreenView(tracker, "Second", origin = second)
        assertEquals("Second" to props("row" to "3"), tracker.screens.single())

        val third = stack.push(screen = "Third")
        stack.push(parent = third, scope = props("row" to "7"))
        val fourth = stack.push(screen = "Fourth")

        stack.emitScreenView(tracker, "Fourth", origin = fourth)
        assertEquals("Fourth" to props("previous_screen" to "Second"), tracker.screens[1])
    }

    @Test
    fun scope_merges_under_previous_screen_and_never_writes_screen_or_section() {
        // `previous_screen` is a caller property, so it wins a key clash with the scope; and this is
        // scope only, not `enrich` — the reserved `screen` / `section` keys must stay out, because for a
        // screen event the name already is the screen.
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        val first = stack.pushSurface(screen = "First", section = "Tab A")
        stack.emitScreenView(tracker, "First", origin = first)
        stack.remove(first)
        val second = stack.pushSurface(
            screen = "Second",
            section = "Tab B",
            scope = props("previous_screen" to "Forged", "tenant" to "acme"),
        )

        stack.emitScreenView(tracker, "Second", origin = second)

        val (name, properties) = tracker.screens[1]
        assertEquals("Second", name)
        assertEquals(props("tenant" to "acme", "previous_screen" to "First"), properties)
        assertFalse("screen" in properties)
        assertFalse("section" in properties)
    }
}
