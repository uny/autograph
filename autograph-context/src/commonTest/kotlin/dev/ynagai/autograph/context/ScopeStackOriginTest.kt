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

        assertEquals("A", stack.current().screen, "ambiently too: the demoted page takes its declaration with it (#228)")
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
    fun a_late_container_directly_over_its_content_still_ranks_before_it() {
        // The framework-independent route: a native pipeline pushes `screen =` frames itself and
        // adopts a surface late through `update(parent =)`. The container and its content then tie
        // on rank, and the tie must go to the container — a stable sort alone kept insertion order
        // and let the late mask blank the content (measured).
        val stack = ScopeStack()
        val home = stack.push(screen = "Home")
        val late = stack.push(boundary = true)
        stack.maskScreen(late)
        stack.update(home, screen = "Home", parent = late) // update replaces contents, so restate them

        assertEquals("Home", stack.current(home).screen)
        assertFalse(stack.current(home).screenMasked)
    }

    @Test
    fun a_root_pushed_after_a_surfaces_content_still_wins_over_it() {
        // The ranking corrects containers only: a root the app pushes AFTER a surface resumed keeps
        // ranking where it was pushed, above that surface's screen.
        val stack = ScopeStack()
        val fragment = stack.push(screen = "Detail", boundary = true)
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
    fun an_origin_under_a_demoted_surface_still_sees_the_declarations_beneath_it() {
        // The split that keeps the ambient propagation honest: ambiently a demoted surface silences
        // what is composed inside it, but an event resolved FROM that composition is the pipeline
        // asserting the event happened there — a tap on a pager page peeking beside the current one,
        // reported by the host as STARTED — and the page's own TrackedScreen is the right answer,
        // not the current page's and not nothing. Propagate the bit here too and this reads null.
        val stack = ScopeStack()
        val current = stack.push(boundary = true)
        stack.push(screen = "Current", parent = stack.push(parent = current))
        val peeking = stack.push(boundary = true)
        val provider = stack.push(parent = peeking)
        stack.push(screen = "Peeking", parent = provider)
        stack.setActive(peeking, false)

        assertEquals("Current", stack.current().screen, "ambiently the demoted page is silent")
        assertEquals("Peeking", stack.current(provider).screen, "from its own origin it is not")
    }

    @Test
    fun a_demoted_sibling_off_the_origins_lineage_is_silenced_for_that_origin_too() {
        // The other side of the exemption above, on a stack with no boundary at all — one shared
        // ScopeStack under several Compose surfaces with nothing claiming them (iOS; Android without
        // the native capture). Every frame is a survivor of the boundary walk, so only the active bit
        // can keep the neighbour page's screen off a tap on the current one; per-frame it cannot,
        // because the demoted page's TrackedScreen carries its own, fresh, active bit and was pushed
        // later. Propagating the bit down every lineage BUT the origin's own is what gets both taps
        // right: the current page's tap reads its screen, the peeking page's tap reads its own.
        val stack = ScopeStack()
        val current = stack.push()
        stack.push(screen = "Current", parent = current)
        val peeking = stack.push()
        stack.push(screen = "Peeking", parent = peeking)
        stack.setActive(peeking, false)

        assertEquals("Current", stack.current(current).screen, "a demoted sibling's declaration does not reach this tap")
        assertEquals("Peeking", stack.current(peeking).screen, "the event happened under the demoted frame: its own screen")
        assertEquals("Current", stack.current().screen)
    }

    @Test
    fun a_demoted_frame_beneath_the_origin_in_its_own_surface_does_not_gate_what_is_under_it() {
        // A native tap on a pager page peeking beside the current one, resolved from the PAGE's frame
        // (the native capture's claim), where the page mixes a native button with a ComposeView. The
        // Compose provider's frame sits under the page frame with no boundary between and mirrors the
        // page's demotion — its owner is the same fragment view lifecycle. Exempt only the origin's
        // ancestors and that provider frame gates the TrackedScreen composed inside the very surface
        // the event happened in: measured as a masked null where the base read "PageB".
        val stack = ScopeStack()
        val shell = stack.push(boundary = true)
        stack.maskScreen(shell)
        val page = stack.push(parent = shell, boundary = true)
        stack.setActive(page, false)
        val provider = stack.push(parent = page)
        stack.setActive(provider, false)
        stack.push(screen = "PageB", parent = provider)

        assertEquals("PageB", stack.current(page).screen, "the composition inside the tapped page still speaks")
        assertNull(stack.current().screen, "ambiently the demoted page and everything in it are silent")
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
    fun a_global_frame_reaches_an_origin_under_a_boundary_and_merges_with_its_scope() {
        // #237 from a native surface: the app's global frame is under no boundary, so it is a
        // survivor for every origin, and being global it merges with the surface's own chain rather
        // than cancelling against it.
        val stack = ScopeStack()
        val provider = stack.push(boundary = true)
        stack.push(scope = props("article_id" to "1"), parent = provider)
        stack.pushGlobal(scope = props("tenant_id" to "acme"))
        val activity = stack.push(boundary = true)
        stack.push(scope = props("checkout_step" to "2"), parent = activity)

        assertEquals(props("tenant_id" to "acme", "article_id" to "1"), stack.current(provider).scope)
        assertEquals(props("tenant_id" to "acme", "checkout_step" to "2"), stack.current(activity).scope)
    }

    @Test
    fun a_global_frame_reparented_by_update_still_reaches_every_origin() {
        // `isUnderABoundary` walks the parent link, so a global frame given a parent under a surface
        // would vanish from every other surface's taps. `update` refuses the link for a global frame.
        val stack = ScopeStack()
        val global = stack.pushGlobal(scope = props("tenant_id" to "acme"))
        val a = stack.push(boundary = true)
        stack.update(global, scope = props("tenant_id" to "acme"), parent = a)
        val b = stack.push(boundary = true)
        val inB = stack.push(scope = props("checkout_step" to "2"), parent = b)
        assertEquals(props("tenant_id" to "acme", "checkout_step" to "2"), stack.current(inB).scope)
    }

    @Test
    fun a_global_frame_survives_ambiguous_siblings_from_an_origin_too() {
        // The ambient twin lives in ScopeStackTest; the origin overload builds a different survivor
        // set before handing it to the same resolveScope, so pin it here as well.
        val stack = ScopeStack()
        stack.pushGlobal(scope = props("tenant_id" to "acme"))
        val provider = stack.push(scope = props("route" to "feed"), boundary = true)
        stack.push(scope = props("row" to "1"), parent = provider)
        stack.push(scope = props("row" to "2"), parent = provider)
        assertEquals(props("tenant_id" to "acme", "route" to "feed"), stack.current(provider).scope)
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
