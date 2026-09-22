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
        val screen = stack.push(parent = root, screen = "Detail", boundary = true)

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
        val sibling = stack.push(screen = "Sibling", boundary = true)
        stack.push(parent = sibling, scope = props("row" to "3"))
        val screen = stack.push(screen = "Detail", boundary = true)
        assertEquals("3", (stack.current().scope["row"] as? JsonPrimitive)?.content, "ambient read sees the sibling scope")

        stack.emitScreenView(tracker, "Detail", origin = screen)

        assertEquals(listOf("Detail" to JsonObject(emptyMap())), tracker.screens)
    }

    @Test
    fun a_global_frame_reaches_a_native_screen_view() {
        // #237: an app-wide frame is exempt from the sibling rule and reaches every event — a native
        // screen view included.
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        stack.pushGlobal(props("install" to "i-1"))
        val screen = stack.push(screen = "Home", boundary = true)

        stack.emitScreenView(tracker, "Home", origin = screen)

        assertEquals(listOf("Home" to props("install" to "i-1")), tracker.screens)
    }

    @Test
    fun scope_merges_under_previous_screen_and_never_writes_screen_or_section() {
        // `previous_screen` is a caller property, so it wins a key clash with the scope; and this is
        // scope only, not `enrich` — the reserved `screen` / `section` keys must stay out, because for a
        // screen event the name already is the screen.
        val stack = ScopeStack()
        val tracker = RecordingTracker()
        val first = stack.push(screen = "First", section = "Tab A", boundary = true)
        stack.emitScreenView(tracker, "First", origin = first)
        stack.remove(first)
        val second = stack.push(
            screen = "Second",
            section = "Tab B",
            scope = props("previous_screen" to "Forged", "tenant" to "acme"),
            boundary = true,
        )

        stack.emitScreenView(tracker, "Second", origin = second)

        val (name, properties) = tracker.screens[1]
        assertEquals("Second", name)
        assertEquals(props("tenant" to "acme", "previous_screen" to "First"), properties)
        assertFalse("screen" in properties)
        assertFalse("section" in properties)
    }
}
