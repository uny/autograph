package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
        // The inner frame declares the outer as its parent — the lineage that lets scope merge along
        // a single chain (as opposed to two sibling scopes, which are ambiguous and dropped).
        val outer = stack.push(scope = props("a" to "outer", "b" to "outer"))
        stack.push(scope = props("b" to "inner", "c" to "inner"), parent = outer)
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
        stack.push(screen = "Outer")
        stack.push(section = "Header") // inner section marker refines the outer screen
        val ctx = stack.current()
        assertEquals("Outer", ctx.screen)
        assertEquals("Header", ctx.section)
    }

    @Test
    fun a_new_screen_frame_does_not_inherit_the_previous_screens_section() {
        val stack = ScopeStack()
        stack.push(screen = "Article", section = "Header")
        // A screen frame owns its section: "Comments" declared none, so the outer "Header" must not
        // bleed onto it. Otherwise a tap on the Comments screen would be mis-attributed to a section
        // belonging to the Article screen. A screen frame replaces both fields, not just the screen.
        stack.push(screen = "Comments")
        val ctx = stack.current()
        assertEquals("Comments", ctx.screen)
        assertNull(ctx.section)
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
    fun update_revises_a_frame_in_place_so_inner_frames_keep_precedence() {
        val stack = ScopeStack()
        val outer = stack.push(scope = props("k" to "outer1"))
        stack.push(scope = props("k" to "inner"), parent = outer)
        assertEquals("inner", stack.current().scope["k"]?.jsonPrimitive?.content)

        // The outer frame's value changes while the inner frame is still mounted. Updating in place
        // must keep the outer frame at its position: remove + re-push would move it to the top and
        // let it override the inner frame, silently mis-attributing captured events.
        stack.update(outer, scope = props("k" to "outer2"))
        assertEquals(
            "inner",
            stack.current().scope["k"]?.jsonPrimitive?.content,
            "an updated outer frame must not overtake a still-mounted inner frame",
        )
    }

    @Test
    fun update_replaces_the_frames_own_contents() {
        val stack = ScopeStack()
        val handle = stack.push(scope = props("a" to "1"), screen = "Home")
        stack.update(handle, scope = props("a" to "2"), screen = "Detail")
        val ctx = stack.current()
        assertEquals("2", ctx.scope["a"]?.jsonPrimitive?.content)
        assertEquals("Detail", ctx.screen)
        // Contents are REPLACED, not merged: an omitted argument reverts to its default.
        stack.update(handle, scope = props("a" to "3"))
        assertNull(stack.current().screen)
    }

    @Test
    fun updating_an_unknown_or_removed_handle_is_a_noop() {
        val stack = ScopeStack()
        val a = stack.push(scope = props("a" to "1"))
        stack.remove(a)
        stack.update(a, scope = props("a" to "zombie"))          // removed handle: no-op
        stack.update(ScopeStack().push(), scope = props("b" to "foreign")) // foreign handle: no-op
        assertEquals(JsonObject(emptyMap()), stack.current().enrich(JsonObject(emptyMap())))
    }

    @Test
    fun updating_a_foreign_handle_does_not_mutate_the_owning_stacks_frame() {
        val owner = ScopeStack()
        val foreign = owner.push(scope = props("b" to "orig"))
        // A frame's contents are mutable, so without the identity guard this would reach across and
        // silently rewrite another stack's frame — asserting only that the CALLING stack stayed
        // empty cannot catch that, since the frame was never in its list either way.
        ScopeStack().update(foreign, scope = props("b" to "hijacked"))
        // Force the owner to recompute, so a mutation performed behind its back would surface.
        owner.push()
        assertEquals(
            "orig",
            owner.current().scope["b"]?.jsonPrimitive?.content,
            "update must not touch a frame belonging to another stack",
        )
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

    @Test
    fun sibling_scopes_mounted_at_once_drop_scope_rather_than_guess() {
        val stack = ScopeStack()
        // Two scopes with neither enclosing the other — a list's rows each in their own scope,
        // split-pane content, a sheet over the screen beneath. The stack cannot tell which subtree a
        // captured tap hit, so it emits NO scope rather than pinning the tap to an arbitrary sibling
        // (#66: a wrong scope is worse than none, and irreversible in analytics).
        stack.push(scope = props("article_id" to "row1"))
        stack.push(scope = props("article_id" to "row2"))
        assertEquals(
            JsonObject(emptyMap()),
            stack.current().scope,
            "ambiguous sibling scopes resolve to no scope, not the last one mounted",
        )
    }

    @Test
    fun sibling_scopes_drop_scope_but_keep_screen_and_section() {
        val stack = ScopeStack()
        val screen = stack.push(screen = "Feed", section = "For You")
        // Two sibling scopes under the screen: scope is ambiguous and dropped, but the screen/section
        // are unambiguous (one screen is active at a time) and must still attribute the tap.
        stack.push(scope = props("article_id" to "row1"), parent = screen)
        stack.push(scope = props("article_id" to "row2"), parent = screen)
        val ctx = stack.current()
        assertEquals(JsonObject(emptyMap()), ctx.scope)
        assertEquals("Feed", ctx.screen)
        assertEquals("For You", ctx.section)
    }

    @Test
    fun a_screen_frame_between_two_nested_scopes_keeps_them_on_one_chain() {
        val stack = ScopeStack()
        // outer scope -> screen -> inner scope. The empty-scope screen frame sits in the lineage but
        // does not break the chain, so the two scopes still merge (inner wins the shared key).
        val outer = stack.push(scope = props("a" to "outer", "b" to "outer"))
        val screen = stack.push(screen = "Article", parent = outer)
        stack.push(scope = props("b" to "inner", "c" to "inner"), parent = screen)
        // Assert on scope directly: enrich would also inject the intermediate frame's screen key.
        assertEquals(
            props("a" to "outer", "b" to "inner", "c" to "inner"),
            stack.current().scope,
        )
    }

    @Test
    fun an_enclosing_scope_survives_the_ambiguous_siblings_below_it() {
        val stack = ScopeStack()
        // The layout the README recommends AND per-row scopes: a route scope wrapping rows that each
        // carry their own. Which row was tapped is unknowable, but the tap is under the route scope
        // whichever row it hit — so the route scope attributes and only the ambiguous rows drop.
        val route = stack.push(scope = props("tab" to "home"))
        stack.push(scope = props("row" to "1"), parent = route)
        stack.push(scope = props("row" to "2"), parent = route)
        assertEquals(props("tab" to "home"), stack.current().scope)
    }

    @Test
    fun a_chain_above_a_branch_survives_whole_and_inner_still_wins() {
        val stack = ScopeStack()
        // outer -> inner -> {leaf1, leaf2}: the two leaves branch, but the whole chain above them
        // encloses both, so it merges in full — including `inner` overriding `outer`'s shared key.
        val outer = stack.push(scope = props("a" to "outer", "b" to "outer"))
        val inner = stack.push(scope = props("b" to "inner"), parent = outer)
        stack.push(scope = props("leaf" to "1"), parent = inner)
        stack.push(scope = props("leaf" to "2"), parent = inner)
        assertEquals(props("a" to "outer", "b" to "inner"), stack.current().scope)
    }

    @Test
    fun removing_a_sibling_scope_resolves_the_ambiguity() {
        val stack = ScopeStack()
        val row1 = stack.push(scope = props("article_id" to "row1"))
        stack.push(scope = props("article_id" to "row2"))
        // While both siblings are mounted, scope is dropped...
        assertEquals(JsonObject(emptyMap()), stack.current().scope)
        // ...and once one leaves (its row scrolled off / disposed) the survivor is unambiguous again.
        stack.remove(row1)
        assertEquals("row2", stack.current().scope["article_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_hand_pushed_root_cancels_against_a_nested_scope() {
        // #237's repro, kept as written: a plain root is not comparable to a scope nested under the
        // provider's root, so BOTH drop. Pinned deliberately — app-wide is never inferred from "a root
        // nothing references" (a frame's meaning would change the moment something is pushed under
        // it); app-wide context is DefaultProperties on the tracker (#253).
        val stack = ScopeStack()
        val provider = stack.push()
        stack.push(scope = props("article_id" to "1"), parent = provider)
        stack.push(scope = props("tenant_id" to "acme"))
        assertEquals(JsonObject(emptyMap()), stack.current().scope)
    }

    /**
     * `parent` is public, so a caller wiring lineage by hand can name a frame the target already
     * encloses. That link is refused (the frame drops to a root) instead of being stored: the ancestry
     * walks behind [ScopeStack.current] run on the main thread inside every mutation, and a cycle
     * would spin them forever — this test HANGS rather than fails if the guard regresses.
     */
    @Test
    fun a_frame_reparented_under_itself_is_refused_and_becomes_a_root() {
        val stack = ScopeStack()
        val a = stack.push(scope = props("a" to "1"))
        stack.reparent(a, a)
        // Refused, so `a` is a root — and a second root scope is its ambiguous sibling, not its child.
        stack.push(scope = props("b" to "2"))
        assertEquals(JsonObject(emptyMap()), stack.current().scope)
    }

    @Test
    fun a_reparent_that_would_close_a_cycle_between_two_frames_is_refused() {
        val stack = ScopeStack()
        val outer = stack.push(scope = props("a" to "outer"))
        val inner = stack.push(scope = props("b" to "inner"), parent = outer)
        // Pointing the outer frame at its own descendant would close outer -> inner -> outer.
        stack.reparent(outer, inner)
        // Refused: `outer` stays a root and the chain outer -> inner still merges, unspun.
        assertEquals(props("a" to "outer", "b" to "inner"), stack.current().scope)
    }

    // --- setScreenMasked --------------------------------------------------------------------------

    @Test
    fun a_mask_hides_the_screen_beneath_it_and_its_section() {
        val stack = ScopeStack()
        stack.push(screen = "Feed", section = "top")
        val mask = stack.push()
        assertEquals("Feed", stack.current().screen, "an unmasked frame is inert")
        stack.setScreenMasked(mask, true)
        assertNull(stack.current().screen)
        assertNull(stack.current().section, "a mask owns its section too")
    }

    @Test
    fun a_mask_announces_itself_so_a_screen_fallback_can_be_suppressed() {
        // `screen == null` alone cannot tell a pipeline whether to substitute a screen it knows from
        // elsewhere: an empty stack means "nobody named one" (substitute), a mask means "the surface
        // on display HAS none" (do not). Without this flag `autograph-compose`'s tap observer read
        // the two the same way and reinstated ScreenHistory.lastScreen — the screen the user just
        // left, i.e. exactly the value the mask exists to prevent.
        val stack = ScopeStack()
        assertFalse(stack.current().screenMasked, "an empty stack is not a mask")
        stack.push(screen = "Feed")
        assertFalse(stack.current().screenMasked)
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        assertNull(stack.current().screen)
        assertTrue(stack.current().screenMasked)

        // A frame that names a screen for itself takes the assertion back: screen resolves again, so
        // there is nothing for a fallback to fill in and nothing to suppress.
        stack.push(screen = "Detail")
        assertFalse(stack.current().screenMasked)
    }

    @Test
    fun a_section_only_frame_does_not_take_back_a_mask() {
        // Section-only frames refine the surrounding screen rather than replacing it, so they leave
        // `screen` null — and must leave the mask's assertion standing with it.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        stack.push(section = "Header")
        assertNull(stack.current().screen)
        assertEquals("Header", stack.current().section)
        assertTrue(stack.current().screenMasked)
    }

    @Test
    fun a_deactivated_mask_stops_masking() {
        // The whole point of splitting the two switches: a mask that is still raised must stop
        // participating when its surface is demoted, without being lifted. Mutating the active filter to
        // `it.active || it.maskScreen` would leave a demoted unnamed surface suppressing the screen
        // of the surface the user came back to.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        assertNull(stack.current().screen)

        stack.setActive(mask, false)
        assertEquals("Feed", stack.current().screen, "a demoted mask must not keep hiding Feed")
        assertFalse(stack.current().screenMasked)

        stack.setActive(mask, true)
        assertNull(stack.current().screen, "and it masks again when its surface returns")
        assertTrue(stack.current().screenMasked)
    }

    @Test
    fun a_screen_pushed_after_a_mask_still_wins() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        // The masked surface's own content naming itself: a mask hides what is underneath, not the
        // declarations the surface makes for itself.
        stack.push(screen = "Detail", section = "body")
        assertEquals("Detail", stack.current().screen)
        assertEquals("body", stack.current().section)
    }

    @Test
    fun a_mask_leaves_scope_alone() {
        val stack = ScopeStack()
        stack.push(scope = props("article_id" to "42"), screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        assertNull(stack.current().screen)
        assertEquals(props("article_id" to "42"), stack.current().scope)
    }

    @Test
    fun update_does_not_clear_a_mask() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        stack.update(mask, scope = props("a" to "1"), screen = "Ignored")
        assertNull(stack.current().screen, "a mask is a switch, not part of the contents update replaces")
        assertEquals(props("a" to "1"), stack.current().scope)
    }

    @Test
    fun masking_an_unknown_removed_or_already_masked_handle_is_a_noop() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val removed = stack.push()
        stack.remove(removed)
        stack.setScreenMasked(removed, true)
        stack.setScreenMasked(ScopeStack().push(), true)
        assertEquals("Feed", stack.current().screen)
    }

    @Test
    fun masking_an_already_masked_frame_republishes_nothing() {
        // The `frame.maskScreen == masked` arm the test above is named for but never reached.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        val before = stack.current()
        stack.setScreenMasked(mask, true)
        assertSame(before, stack.current(), "no snapshot churn when nothing changed")
    }

    @Test
    fun a_foreign_handle_is_not_masked_on_its_own_stack_either() {
        // The guard is `frames.none { it === frame }` on the RECEIVER, so the interesting assertion is
        // on the other stack: a cross-stack call must not quietly flip a frame someone else owns.
        val other = ScopeStack()
        other.push(screen = "Feed")
        val foreign = other.push()
        ScopeStack().setScreenMasked(foreign, true)
        other.update(foreign, scope = props("a" to "1")) // forces a recompute on the owning stack
        assertEquals("Feed", other.current().screen)
    }

    // --- setActive --------------------------------------------------------------------------------

    @Test
    fun an_inactive_frame_contributes_nothing() {
        val stack = ScopeStack()
        stack.push(screen = "Feed", section = "top")
        val detail = stack.push(scope = props("id" to "7"), screen = "Detail", section = "body")
        assertEquals("Detail", stack.current().screen)

        stack.setActive(detail, false)
        val ctx = stack.current()
        assertEquals("Feed", ctx.screen, "the frame beneath answers again")
        assertEquals("top", ctx.section)
        assertEquals(JsonObject(emptyMap()), ctx.scope, "an inactive frame's scope drops out too")
    }

    @Test
    fun reactivating_restores_the_frame_at_its_original_position() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val detail = stack.push(screen = "Detail")
        stack.setActive(detail, false)
        assertEquals("Feed", stack.current().screen)
        stack.setActive(detail, true)
        assertEquals("Detail", stack.current().screen, "position survives the round trip")
    }

    @Test
    fun a_demoted_siblings_frame_no_longer_beats_the_surface_on_display() {
        // The measured defect this whole bit exists for. Two surfaces are mounted at once and the host
        // demotes the off-screen one instead of destroying it, so its frame stays where it was — later
        // in the list than the surface the user came back to, and therefore winning on position alone.
        val stack = ScopeStack()
        val page1 = stack.push(screen = "Page1")
        val page2 = stack.push(screen = "Page2")
        assertEquals("Page2", stack.current().screen)

        // Back to page 1. Page 2 is only demoted — its frame is not removed, and page 1's frame is not
        // re-pushed (re-pushing is what breaks when several surfaces resume at once).
        stack.setActive(page2, false)
        assertEquals("Page1", stack.current().screen)

        // ... and forward again, with nothing rebuilt on either side.
        stack.setActive(page1, false)
        stack.setActive(page2, true)
        assertEquals("Page2", stack.current().screen)
    }

    @Test
    fun a_demoted_sibling_cannot_out_rank_a_mask_reserved_before_it() {
        // The other measured defect: a mask can only hide frames BELOW it, so a named surface that
        // pushed after the mask used to win even while the masked surface was the one on display.
        // Selection is what settles it — the demoted page stops taking part, so position never applies.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val mask = stack.push()
        stack.setScreenMasked(mask, true)
        val later = stack.push(screen = "LaterPage")
        assertEquals("LaterPage", stack.current().screen)

        stack.setActive(later, false)
        assertNull(stack.current().screen, "the masked surface on display must carry no screen")
    }

    @Test
    fun setting_the_value_a_frame_already_has_is_a_noop() {
        val stack = ScopeStack()
        val handle = stack.push(screen = "Feed")
        val before = stack.current()
        stack.setActive(handle, true)
        assertSame(before, stack.current(), "no republish when nothing changed")
    }

    @Test
    fun deactivating_an_unknown_or_removed_handle_is_a_noop() {
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val removed = stack.push(screen = "Gone")
        stack.remove(removed)
        stack.setActive(removed, false)
        stack.setActive(ScopeStack().push(), false)
        assertEquals("Feed", stack.current().screen)
    }

    @Test
    fun a_foreign_handle_is_not_deactivated_on_its_own_stack_either() {
        val other = ScopeStack()
        other.push(screen = "Feed")
        val foreign = other.push(screen = "Detail")
        ScopeStack().setActive(foreign, false)
        // Force a recompute on the OWNING stack: `update` with unchanged contents early-returns and
        // republishes nothing, which would leave this asserting against a pre-hijack snapshot and
        // passing whether or not the guard exists. Pushing always recomputes.
        other.push()
        assertEquals("Detail", other.current().screen, "a cross-stack call must not deactivate")
    }

    @Test
    fun a_stack_whose_frames_are_all_inactive_reads_as_empty() {
        val stack = ScopeStack()
        val handle = stack.push(scope = props("a" to "1"), screen = "Feed", section = "top")
        stack.setActive(handle, false)
        val ctx = stack.current()
        assertNull(ctx.screen)
        assertNull(ctx.section)
        assertEquals(JsonObject(emptyMap()), ctx.scope)
    }

    @Test
    fun an_inactive_frame_still_carries_the_lineage_of_its_descendants() {
        // Containment is structural: a scope nested inside a demoted frame is still nested inside it,
        // so the two must stay comparable and merge rather than reading as ambiguous siblings. Only
        // the inactive frame's own contribution drops — FROM AN ORIGIN. Ambiently the demoted frame
        // takes its descendants with it (see the next test), so the origin overload is the one place
        // this fact is still observable, and the one place it matters: an origin is the pipeline
        // asserting the event happened under the demoted frame.
        // The discriminating shape is an ACTIVE ancestor reaching an active descendant THROUGH the
        // inactive frame. An earlier version of this test asserted an empty scope in both arms, which
        // an implementation that stops the ancestry walk at an inactive link passes just as happily.
        val stack = ScopeStack()
        val route = stack.push(scope = props("tab" to "home"))
        val demoted = stack.push(parent = route)
        val row = stack.push(scope = props("row" to "7"), parent = demoted)
        assertEquals(props("tab" to "home", "row" to "7"), stack.current(row).scope)

        stack.setActive(demoted, false)
        // `demoted` contributes nothing of its own, but it still LINKS: route encloses the row, so
        // the two remain comparable and merge. Break the walk at the inactive link — say
        // `generateSequence(other) { it.parent?.takeIf { p -> p.active } }` — and they become
        // ambiguous roots that drop to {}, which is what this assertion pins.
        assertEquals(props("tab" to "home", "row" to "7"), stack.current(row).scope)
    }

    @Test
    fun ambiently_an_inactive_frame_takes_its_descendants_with_it() {
        // The ambient read has no event to go by, so "is this surface on display?" governs everything
        // inside the surface: the row nested under the demoted frame leaves with it, and what
        // encloses the demoted frame stays. This is the #228 shape — a TrackedScreen composed inside
        // a demoted pager page — reduced to scope.
        val stack = ScopeStack()
        val route = stack.push(scope = props("tab" to "home"))
        val demoted = stack.push(parent = route)
        stack.push(scope = props("row" to "7"), parent = demoted)
        assertEquals(props("tab" to "home", "row" to "7"), stack.current().scope)

        stack.setActive(demoted, false)
        assertEquals(props("tab" to "home"), stack.current().scope, "the row is off display with its surface")

        stack.setActive(demoted, true)
        assertEquals(props("tab" to "home", "row" to "7"), stack.current().scope, "and back with it")
    }

    @Test
    fun a_frame_pushed_under_a_demoted_surface_is_born_silent_ambiently() {
        // The measured #228 defect in its exact form: a pager page composes at STARTED, before it is
        // ever shown, so its TrackedScreen is pushed AFTER the page was demoted. Insertion order and
        // the frame's own (fresh, active) bit would both name it; only the ancestor's bit can say no.
        val stack = ScopeStack()
        stack.push(screen = "PageA")
        val pageB = stack.push()
        stack.setActive(pageB, false)
        stack.push(screen = "PageB", parent = pageB)
        assertEquals("PageA", stack.current().screen)

        stack.setActive(pageB, true)
        assertEquals("PageB", stack.current().screen, "selecting the page brings its declaration in")
    }

    @Test
    fun only_an_inactive_ancestor_still_on_the_stack_gates_its_descendants() {
        // Mirrors the origin walk's rule: a parent link pointing off this stack — at a frame since
        // removed, or at another stack's frame — is transparent, never a gate. A stale inactive
        // ancestor must not silence content that is still very much on display.
        val stack = ScopeStack()
        val gone = stack.push()
        stack.setActive(gone, false)
        stack.push(screen = "Live", parent = gone)
        assertNull(stack.current().screen, "gated while the inactive ancestor is on the stack")

        stack.remove(gone)
        assertEquals("Live", stack.current().screen, "a removed ancestor gates nothing")

        val foreignStack = ScopeStack()
        val foreign = foreignStack.push()
        foreignStack.setActive(foreign, false)
        val other = ScopeStack()
        other.push(screen = "Live", parent = foreign)
        assertEquals("Live", other.current().screen, "another stack's frame gates nothing")
    }

    @Test
    fun an_inactive_frames_own_scope_still_drops_out() {
        // The other half: lineage survives deactivation, the contribution does not. From an origin
        // beneath it the inner scope is still reported (the event happened there); ambiently the
        // inner frame is off display along with its outer and nothing is left.
        val stack = ScopeStack()
        val outer = stack.push(scope = props("a" to "outer", "b" to "outer"))
        val inner = stack.push(scope = props("b" to "inner"), parent = outer)
        assertEquals(props("a" to "outer", "b" to "inner"), stack.current().scope)

        stack.setActive(outer, false)
        assertEquals(props("b" to "inner"), stack.current(inner).scope, "outer's own keys leave with it")
        assertEquals(JsonObject(emptyMap()), stack.current().scope, "ambiently inner leaves with outer")
    }

    @Test
    fun deactivating_a_frame_does_not_turn_an_ambiguous_drop_into_a_guess() {
        // Selection narrows the candidate set, and narrowing must not be mistaken for disambiguating:
        // two frames that branch away from each other are still ambiguous when a THIRD is demoted.
        // The demoted frame is a root of its own here — demoting `outer` would (correctly) take
        // `inner` off display with it and leave `sibling` alone, which is a resolution, not a guess.
        // A mutant that skipped the pairwise comparability filter once anything was inactive would
        // report a guessed sibling scope — the wrong-value outcome #66 forbids.
        val stack = ScopeStack()
        val outer = stack.push(scope = props("a" to "outer"))
        stack.push(scope = props("b" to "inner"), parent = outer)
        stack.push(scope = props("c" to "sibling"))
        val third = stack.push(scope = props("d" to "third"))
        assertEquals(JsonObject(emptyMap()), stack.current().scope)

        stack.setActive(third, false)
        assertEquals(
            JsonObject(emptyMap()),
            stack.current().scope,
            "inner and sibling still branch away from each other — the drop is preserved",
        )
    }

    @Test
    fun update_does_not_reactivate_a_demoted_frame() {
        // `update`'s kdoc promises it changes neither the mask NOR the active flag. Only the mask
        // half was pinned; adding `frame.active = true` to update broke no test. A pipeline revising
        // a demoted page's scope would then quietly bring the off-screen surface back into
        // resolution — the exact wrong-screen attribution setActive exists to fix.
        val stack = ScopeStack()
        stack.push(screen = "Page1")
        val page2 = stack.push(scope = props("page" to "2"), screen = "Page2")
        stack.setActive(page2, false)
        assertEquals("Page1", stack.current().screen)

        stack.update(page2, scope = props("page" to "2-revised"), screen = "Page2")
        assertEquals("Page1", stack.current().screen, "revising a demoted frame must not revive it")
        assertEquals(JsonObject(emptyMap()), stack.current().scope)
    }

    @Test
    fun reactivating_does_not_move_the_frame_to_the_top() {
        // Every other reactivation fixture toggles the LAST frame, so a mutation that re-appends on
        // reactivation is indistinguishable from leaving it in place. Toggling a MIDDLE frame is what
        // separates them.
        val stack = ScopeStack()
        stack.push(screen = "A")
        val b = stack.push(screen = "B")
        stack.push(screen = "C")
        assertEquals("C", stack.current().screen)

        stack.setActive(b, false)
        assertEquals("C", stack.current().screen)
        stack.setActive(b, true)
        assertEquals("C", stack.current().screen, "B came back UNDER C, not on top of it")
    }

    // --- setActive, batched -----------------------------------------------------------------------

    @Test
    fun the_batched_form_publishes_one_snapshot_for_the_whole_surface() {
        // A surface owns more than one frame. Switching them one at a time publishes an intermediate
        // context in which some of them answer and others do not; a tap captured against it would read
        // a state the app was never in.
        val stack = ScopeStack()
        stack.push(screen = "Feed")
        val screen = stack.push(screen = "Page2")
        val scope = stack.push(scope = props("page" to "2"))
        assertEquals("Page2", stack.current().screen)
        assertEquals(props("page" to "2"), stack.current().scope)

        stack.setActive(listOf(screen, scope), false)
        val ctx = stack.current()
        assertEquals("Feed", ctx.screen)
        assertEquals(JsonObject(emptyMap()), ctx.scope, "the surface's scope left with its screen")

        stack.setActive(listOf(screen, scope), true)
        assertEquals("Page2", stack.current().screen)
        assertEquals(props("page" to "2"), stack.current().scope)
    }

    @Test
    fun the_batched_form_skips_no_ops_and_applies_the_rest() {
        val stack = ScopeStack()
        val feed = stack.push(screen = "Feed")
        val detail = stack.push(screen = "Detail")
        val removed = stack.push()
        stack.remove(removed)
        stack.setActive(detail, false)

        // `detail` is already inactive, `removed` is gone, `ScopeStack().push()` is foreign — only
        // `feed` is a real change.
        stack.setActive(listOf(detail, removed, ScopeStack().push(), feed), false)
        assertNull(stack.current().screen)
    }

    @Test
    fun the_batched_form_does_not_deactivate_a_foreign_stacks_frame() {
        // The single-handle form has this test; the batched one did not, so deleting its
        // `frames.none { it === frame } ||` guard left the whole suite green. The hazard is only
        // visible on the OWNING stack — the batch's own assertions never read it.
        val other = ScopeStack()
        other.push(screen = "Feed")
        val foreign = other.push(screen = "Detail")

        val stack = ScopeStack()
        val mine = stack.push(screen = "Mine")
        stack.setActive(listOf(foreign, mine), false)

        other.push() // always recomputes; `update` with unchanged contents would early-return
        assertEquals("Detail", other.current().screen, "a batch must not reach into another stack")
    }

    @Test
    fun the_batched_form_republishes_nothing_when_every_handle_is_a_noop() {
        val stack = ScopeStack()
        val handle = stack.push(screen = "Feed")
        val before = stack.current()
        stack.setActive(listOf(handle, ScopeStack().push()), true)
        assertSame(before, stack.current())
    }
}
