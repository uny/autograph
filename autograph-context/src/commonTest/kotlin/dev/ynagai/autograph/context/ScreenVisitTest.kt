@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EventIdGenerator
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.Tracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The visit id (#242): one per `Screen Viewed` that [emitScreenView] emits, carried by that screen
 * view and answered by the stack — [AmbientContext.screenViewId] — for the events captured during
 * the visit. The id always comes from the frame that names the screen, and is absent rather than
 * borrowed when that frame has none or the stack cannot tell which surface's visit it is in.
 */
class ScreenVisitTest {

    /** Hands out `v1`, `v2`, … so a test can name the id it expects. */
    private class CountingIds : EventIdGenerator {
        var count = 0
        override fun next(): String = "v${++count}"
    }

    /** Records each screen view's visit id, and what the stack answered while the tracker ran. */
    private class RecordingTracker(private val stack: ScopeStack) : Tracker {
        val visits = mutableListOf<String?>()
        val ambientDuringScreen = mutableListOf<String?>()
        val keys = mutableListOf<Set<String>>()
        var throwOnScreen = false
        override fun track(name: String, properties: Map<String, JsonElement>, target: String?) = Unit
        override fun screen(name: String, properties: Map<String, JsonElement>) {
            keys += properties.keys
            visits += properties[RESERVED_METADATA_KEY]?.let { raw ->
                (Json.parseToJsonElement(raw.jsonPrimitive.content) as JsonObject)["screen_view_id"]?.jsonPrimitive?.content
            }
            ambientDuringScreen += stack.current().screenViewId
            if (throwOnScreen) error("tracker failed")
        }
        override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
    }

    private fun stack() = ScopeStack().apply { screenViewIdGenerator = CountingIds() }

    @Test
    fun the_screen_view_and_the_events_of_its_visit_carry_the_same_id() {
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val screen = stack.push(screen = "Home")

        stack.emitScreenView(tracker, "Home", origin = screen)

        assertEquals(listOf<String?>("v1"), tracker.visits)
        assertEquals("v1", stack.current().screenViewId)
        assertEquals("v1", stack.current(screen).screenViewId)
        // Started before the tracker ran, so what the tracker's own code captures can join it.
        assertEquals(listOf<String?>("v1"), tracker.ambientDuringScreen)
    }

    @Test
    fun the_default_generator_produces_an_id() {
        val stack = ScopeStack()
        val tracker = RecordingTracker(stack)
        val screen = stack.push(screen = "Home")

        stack.emitScreenView(tracker, "Home", origin = screen)

        val id = assertNotNull(tracker.visits.single())
        assertEquals(id, stack.current().screenViewId)
    }

    @Test
    fun every_screen_view_starts_a_new_visit_even_of_the_same_screen_on_the_same_frame() {
        // The Android return to a demoted surface: the same frame emits again, and it is a new visit.
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val screen = stack.pushSurface(screen = "Home")
        stack.emitScreenView(tracker, "Home", origin = screen)
        stack.setActive(screen, false)
        stack.setActive(screen, true)

        stack.emitScreenView(tracker, "Home", origin = screen)

        assertEquals(listOf<String?>("v1", "v2"), tracker.visits)
        assertEquals("v2", stack.current().screenViewId)
    }

    @Test
    fun a_surface_off_display_and_back_without_a_screen_view_keeps_its_visit() {
        // A dialog pausing the screen underneath: no new view, so the same visit.
        val stack = stack()
        val screen = stack.pushSurface(screen = "Home")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.setActive(screen, false)
        assertNull(stack.current().screenViewId)
        stack.setActive(screen, true)

        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun ending_a_visit_clears_the_id_and_nothing_else() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Home", section = "Top")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.endScreenVisit(screen)

        val context = stack.current()
        assertNull(context.screenViewId)
        assertEquals("Home", context.screen)
        assertEquals("Top", context.section)
    }

    @Test
    fun ending_a_visit_the_frame_does_not_hold_republishes_nothing() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Home")
        val before = stack.current()

        stack.endScreenVisit(screen)

        assertSame(before, stack.current())
    }

    @Test
    fun a_section_change_or_an_unchanged_update_keeps_the_visit() {
        val stack = stack()
        val screen = stack.push(screen = "Home", section = "A")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.update(screen, screen = "Home", section = "A")
        assertEquals("v1", stack.current().screenViewId)
        stack.update(screen, screen = "Home", section = "B")
        assertEquals("v1", stack.current().screenViewId)
        stack.reparent(screen, stack.push())
        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun an_update_that_renames_the_screen_ends_the_visit() {
        val stack = stack()
        val screen = stack.push(screen = "Home")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.update(screen, screen = "Settings")

        assertEquals("Settings", stack.current().screen)
        assertNull(stack.current().screenViewId)
        // And it does not come back with the old name: the visit is over.
        stack.update(screen, screen = "Home")
        assertNull(stack.current().screenViewId)
    }

    @Test
    fun an_update_that_drops_the_screen_ends_the_visit() {
        val stack = stack()
        val screen = stack.push(screen = "Home")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.update(screen, screen = null)
        stack.update(screen, screen = "Home")

        assertNull(stack.current().screenViewId)
    }

    @Test
    fun a_mask_clears_the_id_with_the_screen() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Home")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        val mask = stack.pushSurface(parent = screen)
        stack.setScreenMasked(mask, true)
        assertNull(stack.current().screenViewId)

        stack.setScreenMasked(mask, false)
        assertEquals("v1", stack.current().screenViewId)

        stack.setScreenMasked(screen, true)
        assertNull(stack.current(screen).screenViewId)
    }

    @Test
    fun a_screen_named_after_the_visits_frame_does_not_borrow_its_id() {
        // A `TrackedScreen` composed inside a native screen names the screen, and has no visit of its
        // own yet: the id must be absent, not the native screen's.
        val stack = stack()
        val native = stack.pushSurface(screen = "Host")
        stack.emitScreenView(RecordingTracker(stack), "Host", origin = native)

        val composed = stack.push(parent = native, screen = "Inner")

        assertEquals("Inner", stack.current().screen)
        assertNull(stack.current().screenViewId)
        assertNull(stack.current(native).screenViewId)
        stack.remove(composed)
        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun a_section_only_frame_after_the_visits_frame_keeps_it() {
        val stack = stack()
        val screen = stack.push(screen = "Home")
        stack.emitScreenView(RecordingTracker(stack), "Home", origin = screen)

        stack.push(parent = screen, section = "Tab")

        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun on_a_stack_without_surfaces_the_id_follows_the_screen() {
        // The iOS shape: native screens are roots, and a sheet is a root beside the screen it covers.
        // The screen the stack answers is the sheet, and so is the visit.
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val home = stack.push(screen = "Home")
        stack.emitScreenView(tracker, "Home", origin = home)
        val sheet = stack.push(screen = "Sheet")
        stack.emitScreenView(tracker, "Sheet", origin = sheet)

        assertEquals("Sheet", stack.current().screen)
        assertEquals("v2", stack.current().screenViewId)

        stack.remove(sheet)
        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun surfaces_side_by_side_give_an_ambient_read_no_id_and_each_origin_its_own() {
        // Two Activities resumed at once (multi-window), or two fragments shown beside each other.
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val left = stack.pushSurface(screen = "Left")
        stack.emitScreenView(tracker, "Left", origin = left)
        val right = stack.pushSurface(screen = "Right")
        stack.emitScreenView(tracker, "Right", origin = right)

        assertEquals(listOf<String?>("v1", "v2"), tracker.visits)
        assertEquals("Right", stack.current().screen, "the screen is still reported")
        assertNull(stack.current().screenViewId)
        assertEquals("v1", stack.current(left).screenViewId)
        assertEquals("v2", stack.current(right).screenViewId)

        // One of them off display: no longer ambiguous.
        stack.setActive(left, false)
        assertEquals("v2", stack.current().screenViewId)
    }

    @Test
    fun a_surface_beside_the_screen_that_declares_nothing_does_not_make_it_ambiguous() {
        // A content fragment beside a headless worker fragment, or an excluded one that names and
        // masks nothing: one screen is on display, and the empty sibling is not a visit of its own.
        val stack = stack()
        val activity = stack.pushSurface()
        val content = stack.pushSurface(parent = activity, screen = "Detail")
        stack.emitScreenView(RecordingTracker(stack), "Detail", origin = content)
        stack.pushSurface(parent = activity)

        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun a_sibling_surface_whose_content_names_a_screen_makes_it_ambiguous() {
        // The other side is a Compose host: the surface itself is empty, the screen is named inside it.
        val stack = stack()
        val activity = stack.pushSurface()
        val composeHost = stack.pushSurface(parent = activity)
        stack.push(parent = composeHost, screen = "Composed")
        val content = stack.pushSurface(parent = activity, screen = "Detail")
        stack.emitScreenView(RecordingTracker(stack), "Detail", origin = content)

        assertEquals("Detail", stack.current().screen)
        assertNull(stack.current().screenViewId)
        assertEquals("v1", stack.current(content).screenViewId)
    }

    @Test
    fun surfaces_nested_in_one_another_are_not_ambiguous() {
        // An Activity hosting a fragment: one chain, so the innermost screen's visit wins ambiently,
        // and a tap on the Activity's own view gets the Activity's.
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val activity = stack.pushSurface(screen = "Main")
        stack.emitScreenView(tracker, "Main", origin = activity)
        val fragment = stack.pushSurface(parent = activity, screen = "Detail")
        stack.emitScreenView(tracker, "Detail", origin = fragment)

        assertEquals("v2", stack.current().screenViewId)
        assertEquals("v2", stack.current(fragment).screenViewId)
        assertEquals("v1", stack.current(activity).screenViewId)
    }

    @Test
    fun a_failing_generator_leaves_the_visit_without_an_id_and_still_emits() {
        val stack = ScopeStack()
        var fail = false
        stack.screenViewIdGenerator = EventIdGenerator { if (fail) error("no id") else "ok" }
        val tracker = RecordingTracker(stack)
        val screen = stack.push(screen = "Home")
        stack.emitScreenView(tracker, "Home", origin = screen)

        fail = true
        stack.emitScreenView(tracker, "Home", origin = screen)

        assertEquals(listOf("ok", null), tracker.visits)
        assertFalse(RESERVED_METADATA_KEY in tracker.keys[1], "no metadata at all without an id")
        // Not the previous visit's id: that visit is over.
        assertNull(stack.current().screenViewId)
    }

    @Test
    fun an_empty_id_counts_as_none() {
        val stack = ScopeStack()
        stack.screenViewIdGenerator = EventIdGenerator { "" }
        val tracker = RecordingTracker(stack)
        val screen = stack.push(screen = "Home")

        stack.emitScreenView(tracker, "Home", origin = screen)

        assertEquals(listOf<String?>(null), tracker.visits)
        assertNull(stack.current().screenViewId)
    }

    @Test
    fun a_throwing_tracker_does_not_undo_the_visit() {
        val stack = stack()
        val tracker = RecordingTracker(stack).apply { throwOnScreen = true }
        val screen = stack.push(screen = "Home")

        runCatching { stack.emitScreenView(tracker, "Home", origin = screen) }

        assertEquals("v1", stack.current().screenViewId)
    }

    @Test
    fun the_screen_view_carries_its_own_frames_id_not_what_the_stack_answers() {
        // A frame pushed after the emitting one names the screen ambiently; the screen view still
        // carries the visit it started, which reading it back through the stack would not give.
        val stack = stack()
        val tracker = RecordingTracker(stack)
        val first = stack.push(screen = "First")
        stack.push(screen = "Overlay")

        stack.emitScreenView(tracker, "First", origin = first)

        assertEquals(listOf<String?>("v1"), tracker.visits)
        assertEquals("Overlay", stack.current().screen)
        assertNull(stack.current().screenViewId)
    }
}
