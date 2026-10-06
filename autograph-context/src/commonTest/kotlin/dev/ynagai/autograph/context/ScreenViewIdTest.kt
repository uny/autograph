@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.context

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EventIdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which visit [AmbientContext.screenViewId] reports (#242): the one the frame naming the screen
 * started, through both reads — and none at all rather than a wrong one.
 */
class ScreenViewIdTest {

    private fun stack(): ScopeStack {
        var next = 0
        return ScopeStack(EventIdGenerator { "id-${++next}" })
    }

    @Test
    fun both_reads_report_the_visit_of_the_frame_that_names_the_screen() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Detail")
        stack.beginScreenView(screen)

        assertEquals("id-1", stack.current().screenViewId, "the ambient snapshot is republished")
        assertEquals("id-1", stack.current(screen).screenViewId)
    }

    @Test
    fun a_nested_screen_reports_its_own_visit_not_its_hosts() {
        val stack = stack()
        val host = stack.pushSurface(screen = "Host")
        stack.beginScreenView(host)
        val fragment = stack.pushSurface(parent = host, screen = "Detail")
        stack.beginScreenView(fragment)

        assertEquals("Detail", stack.current().screen)
        assertEquals("id-2", stack.current().screenViewId)
        assertEquals("id-2", stack.current(fragment).screenViewId)
    }

    @Test
    fun a_screen_with_no_visit_of_its_own_does_not_borrow_its_hosts() {
        // A Compose screen inside a native Activity, until Compose screens start visits of their own.
        val stack = stack()
        val host = stack.pushSurface(screen = "Host")
        stack.beginScreenView(host)
        stack.push(parent = host, screen = "ComposeScreen")

        assertEquals("ComposeScreen", stack.current().screen)
        assertNull(stack.current().screenViewId)
        assertNull(stack.current(host).screenViewId)
    }

    @Test
    fun two_screens_on_display_side_by_side_tie_the_event_to_neither() {
        // The iOS shape: two root view controllers, each a root frame that started its own visit.
        val stack = stack()
        val left = stack.push(screen = "Left")
        stack.beginScreenView(left)
        val right = stack.push(screen = "Right")
        stack.beginScreenView(right)

        val ambient = stack.current()
        assertEquals("Right", ambient.screen, "the name still resolves by insertion order")
        assertNull(ambient.screenViewId)
        assertNull(stack.current(left).screenViewId, "boundary-free roots are both candidates of the origin read too")
    }

    @Test
    fun a_sibling_surface_behind_a_boundary_does_not_make_the_origin_read_ambiguous() {
        // Android multi-window: two Activities' surface frames. A tap localized to one of them resolves.
        val stack = stack()
        val first = stack.pushSurface(screen = "First")
        stack.beginScreenView(first)
        val second = stack.pushSurface(screen = "Second")
        stack.beginScreenView(second)

        assertEquals("id-1", stack.current(first).screenViewId)
        assertEquals("id-2", stack.current(second).screenViewId)
        assertNull(stack.current().screenViewId, "the ambient read sees both and picks neither")
    }

    @Test
    fun a_mask_clears_the_visit_along_with_the_screen() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Detail")
        stack.beginScreenView(screen)
        stack.push(parent = screen).also { stack.setScreenMasked(it, true) }

        assertNull(stack.current().screen)
        assertNull(stack.current().screenViewId)
    }

    @Test
    fun an_ended_visit_reports_no_id_until_the_next_one_begins() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Detail")
        stack.beginScreenView(screen)

        stack.endScreenView(screen)
        assertNull(stack.current().screenViewId)
        assertEquals("Detail", stack.current().screen, "ending the visit leaves the frame and its name")

        stack.beginScreenView(screen)
        assertEquals("id-2", stack.current().screenViewId)
    }

    @Test
    fun deactivating_and_reactivating_a_frame_keeps_its_visit() {
        // A pause under a dialog is not the end of a visit.
        val stack = stack()
        val screen = stack.pushSurface(screen = "Detail")
        stack.beginScreenView(screen)

        stack.setActive(screen, false)
        assertNull(stack.current().screenViewId)
        stack.setActive(screen, true)

        assertEquals("id-1", stack.current().screenViewId)
    }

    @Test
    fun ending_a_visit_on_a_removed_frame_or_another_stacks_is_a_no_op() {
        val stack = stack()
        val screen = stack.pushSurface(screen = "Detail")
        stack.beginScreenView(screen)
        val other = stack()
        other.endScreenView(screen)

        assertEquals("id-1", stack.current().screenViewId)
    }
}
