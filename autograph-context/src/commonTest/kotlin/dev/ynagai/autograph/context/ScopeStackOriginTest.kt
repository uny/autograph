package dev.ynagai.autograph.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The origin-taking [ScopeStack.current] and the `boundary` push: what an event sees is decided by
 * where it happened, not by which frame was pushed last. The shapes here are the ones the Android
 * pipelines produce — an Activity or fragment frame is a boundary, a Compose provider's root frame
 * is a boundary nested in the surface hosting it, and the `TrackedScreen`s inside are plain frames
 * beneath that root.
 */
class ScopeStackOriginTest {

    private fun props(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun a_sibling_surfaces_mask_does_not_reach_an_event_on_the_surface_hosting_it() {
        // #216 S2: a capturable Activity with an unnamed (masked) fragment added directly into it —
        // a mini-player. The mask must reach a tap in the fragment and NOT a tap on the Activity's
        // own view, whatever the insertion order says.
        val stack = ScopeStack()
        val activity = stack.push(screen = "Main", boundary = true)
        val miniPlayer = stack.push(parent = activity, boundary = true)
        stack.maskScreen(miniPlayer)

        // Ambiently the mask, being later, wins — the value this test exists to keep away from the
        // Activity's own taps.
        assertNull(stack.current().screen)

        val onActivity = stack.current(activity)
        assertEquals("Main", onActivity.screen)
        assertFalse(onActivity.screenMasked)

        val inMiniPlayer = stack.current(miniPlayer)
        assertNull(inMiniPlayer.screen)
        assertTrue(inMiniPlayer.screenMasked, "a tap in the masked surface is still masked")
    }

    @Test
    fun a_compose_root_beside_a_masked_sibling_keeps_its_hosts_screen() {
        // The Compose-side S2: a ComposeView in the Activity's own content, next to the mini-player.
        // Its provider frame is nested in the Activity, not in the mini-player.
        val stack = ScopeStack()
        val activity = stack.push(screen = "Main", boundary = true)
        val miniPlayer = stack.push(parent = activity, boundary = true)
        stack.maskScreen(miniPlayer)
        val provider = stack.push(parent = activity)

        assertEquals("Main", stack.current(provider).screen)
        assertFalse(stack.current(provider).screenMasked)
    }

    @Test
    fun a_declaration_inside_a_masked_surface_crosses_that_surfaces_own_mask() {
        // A Compose host that names no screen masks; a TrackedScreen composed inside it is the
        // current declaration of that very surface and must win over the mask.
        val stack = ScopeStack()
        val host = stack.push(boundary = true)
        stack.maskScreen(host)
        val provider = stack.push(parent = host)
        stack.push(screen = "ComposeScreen", parent = provider)

        val inComposition = stack.current(provider)
        assertEquals("ComposeScreen", inComposition.screen)
        assertFalse(inComposition.screenMasked)
    }

    @Test
    fun a_composition_inside_a_surface_is_that_surfaces_own_declaration() {
        // A native toolbar beside a ComposeView in one Compose host: the host is masked (Compose
        // declares its screen for it) and the composition's root frame is NOT a boundary, so the
        // toolbar tap carries the screen the composition declares — as it did ambiently.
        val stack = ScopeStack()
        val host = stack.push(boundary = true)
        stack.maskScreen(host)
        val provider = stack.push(parent = host)
        stack.push(screen = "Detail", parent = provider)

        assertEquals("Detail", stack.current(host).screen)
        assertEquals("Detail", stack.current(provider).screen)
    }

    @Test
    fun the_descent_from_an_origin_stops_at_a_nested_boundary() {
        // A named fragment inside a named Activity: a tap on the Activity's own view is not in the
        // fragment, and the native pipeline knows it — so the fragment's screen must not refine it.
        val stack = ScopeStack()
        val activity = stack.push(screen = "Main", boundary = true)
        val fragment = stack.push(screen = "Child", parent = activity, boundary = true)
        stack.push(screen = "Grandchild", parent = fragment) // a plain declaration under the fragment

        assertEquals("Main", stack.current(activity).screen)
        assertEquals("Grandchild", stack.current(fragment).screen)
    }

    @Test
    fun a_demoted_pager_pages_declarations_do_not_reach_the_selected_page() {
        // ViewPager2 with a Compose page each declaring its own TrackedScreen. Page B is attached
        // (its frames are on the stack, later in insertion order) but off display. A tap on page A
        // must resolve to A — which insertion order alone gets wrong.
        val stack = ScopeStack()
        val pageA = stack.push(boundary = true)
        val pageB = stack.push(boundary = true)
        val providerA = stack.push(parent = pageA)
        val providerB = stack.push(parent = pageB)
        stack.push(screen = "A", parent = providerA)
        stack.push(screen = "B", parent = providerB)
        stack.setActive(pageB, false)

        assertEquals("B", stack.current().screen, "ambiently, insertion order still names B")
        assertEquals("A", stack.current(providerA).screen)
        // And even with B selected too (the sibling is simply another surface):
        stack.setActive(pageB, true)
        assertEquals("A", stack.current(providerA).screen)
        assertEquals("B", stack.current(providerB).screen)
    }

    @Test
    fun a_frame_under_no_boundary_applies_to_every_origin() {
        // A root the app pushed by hand — an experiment scope at startup, or a screen for a surface
        // it opted out of the native capture — is localized by nothing, so it reaches every event
        // exactly as it does ambiently. Dropping it once a native capture claims the view tree
        // would be silent data loss on a supported API.
        val stack = ScopeStack()
        stack.push(scope = props("experiment" to "b"))
        val activity = stack.push(boundary = true) // opted out: names nothing
        stack.push(screen = "Checkout")

        val ctx = stack.current(activity)
        assertEquals(props("experiment" to "b"), ctx.scope)
        assertEquals("Checkout", ctx.screen)
    }

    @Test
    fun a_boundary_free_stack_resolves_identically_with_or_without_an_origin() {
        // iOS, desktop, an Android app without the native screen capture: no boundary anywhere, so
        // a provider resolving from its own frame sees exactly the ambient answer — including a
        // native pipeline's root screen frame pushed AFTER the composition's.
        val stack = ScopeStack()
        val provider = stack.push()
        stack.push(scope = props("article" to "42"), parent = provider)
        stack.push(screen = "RecipeDetail") // a UIKit screen, a root

        assertEquals(stack.current().screen, stack.current(provider).screen)
        assertEquals("RecipeDetail", stack.current(provider).screen)
        assertEquals(props("article" to "42"), stack.current(provider).scope)
    }

    @Test
    fun a_surface_adopted_late_ranks_no_later_than_the_content_it_hosts() {
        // An Activity that predates the native capture's install pushes its frame (a mask: it hosts
        // Compose) AFTER the composition it hosts. Insertion order alone lets that late mask blank
        // the TrackedScreen inside it — correct→absent on a supported path. The container ranks
        // where its content ranks instead.
        val stack = ScopeStack()
        val provider = stack.push()
        stack.push(screen = "Home", parent = provider)
        val lateHost = stack.push(boundary = true)
        stack.maskScreen(lateHost)
        stack.update(provider, parent = lateHost)

        assertNull(stack.current().screen, "ambiently the late mask wins")
        assertEquals("Home", stack.current(provider).screen)
        assertFalse(stack.current(provider).screenMasked)
    }

    @Test
    fun a_hand_pushed_root_still_ranks_where_it_was_pushed() {
        // The correction above must not promote a container over a root pushed BEFORE its content:
        // a global screen pushed at startup, then a fragment naming its own — the fragment wins, as
        // it does ambiently; a root pushed AFTER the fragment resumed wins over it, likewise.
        val stack = ScopeStack()
        stack.push(screen = "Global")
        val fragment = stack.push(screen = "Detail", boundary = true)
        assertEquals("Detail", stack.current(fragment).screen)

        stack.push(screen = "Later")
        assertEquals("Later", stack.current(fragment).screen)
    }

    @Test
    fun an_inactive_frame_on_the_lineage_contributes_nothing() {
        val stack = ScopeStack()
        val host = stack.push(screen = "Host", section = "top", scope = props("k" to "v"), boundary = true)
        val provider = stack.push(parent = host, boundary = true)
        stack.setActive(host, false)

        val ctx = stack.current(provider)
        assertNull(ctx.screen)
        assertNull(ctx.section)
        assertEquals(JsonObject(emptyMap()), ctx.scope)
    }

    @Test
    fun a_stale_origin_resolves_to_what_of_its_lineage_is_still_on_the_stack() {
        val stack = ScopeStack()
        val host = stack.push(screen = "Host", boundary = true)
        val provider = stack.push(parent = host, boundary = true)
        stack.remove(provider)

        assertEquals("Host", stack.current(provider).screen)
    }

    @Test
    fun an_origin_from_another_stack_resolves_to_nothing() {
        val other = ScopeStack()
        val foreign = other.push(screen = "Theirs", boundary = true)
        val stack = ScopeStack()
        stack.push(screen = "Mine", boundary = true)

        val ctx = stack.current(foreign)
        assertNull(ctx.screen)
        assertEquals(JsonObject(emptyMap()), ctx.scope)
    }

    @Test
    fun a_parent_off_this_stack_is_transparent_not_a_boundary() {
        // A hybrid app running two captures on two stacks can hand a composition a claim from the
        // OTHER stack; linking under it must not strand the composition (it is under no boundary of
        // this stack, so it stays global here), and a removed boundary is treated the same way.
        val other = ScopeStack()
        val foreign = other.push(boundary = true)
        val stack = ScopeStack()
        val provider = stack.push(parent = foreign)
        stack.push(screen = "Declared", parent = provider)
        val surface = stack.push(boundary = true)

        assertEquals("Declared", stack.current(surface).screen)

        val removed = stack.push(boundary = true)
        stack.update(provider, parent = removed)
        stack.remove(removed)
        assertEquals("Declared", stack.current(surface).screen)
    }

    @Test
    fun scope_beneath_an_origin_still_drops_ambiguous_siblings() {
        // Origin resolution narrows the candidates; it must not turn the #66 drop into a guess.
        val stack = ScopeStack()
        val provider = stack.push(scope = props("route" to "feed"), boundary = true)
        stack.push(scope = props("row" to "1"), parent = provider)
        stack.push(scope = props("row" to "2"), parent = provider)

        assertEquals(props("route" to "feed"), stack.current(provider).scope)
    }

    @Test
    fun scope_merges_along_the_lineage_across_a_boundary() {
        val stack = ScopeStack()
        val host = stack.push(scope = props("tab" to "home"), boundary = true)
        val provider = stack.push(parent = host, boundary = true)
        stack.push(scope = props("article" to "42"), parent = provider)

        assertEquals(props("tab" to "home", "article" to "42"), stack.current(provider).scope)
    }

    @Test
    fun a_boundary_frame_is_an_ordinary_frame_ambiently() {
        val stack = ScopeStack()
        stack.push(screen = "Feed", boundary = true)
        val inner = stack.push(screen = "Detail", boundary = true)
        assertEquals("Detail", stack.current().screen)
        stack.remove(inner)
        assertEquals("Feed", stack.current().screen)
    }
}
