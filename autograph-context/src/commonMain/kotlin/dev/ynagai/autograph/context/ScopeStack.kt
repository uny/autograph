package dev.ynagai.autograph.context

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.asJsonObject
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ambient scope + screen context of the currently visible UI, as a stack of [push]ed frames.
 *
 * This is the non-Compose home for what `AutographScope` and screen context express inside Compose
 * via `CompositionLocal`: a place that autocapture pipelines can read at the moment a tap or screen
 * view is observed, regardless of which UI framework produced it (Compose, UIKit/SwiftUI, or the
 * Android View system). Each surface pushes a frame when a screen or scope comes into view and
 * removes it when it leaves; the capture pipeline calls [current] and [AmbientContext.enrich] to
 * attribute the event.
 *
 * **Read this only from auto-capture code, not from explicit `track` calls.** The stack answers
 * "what was on screen when the user acted", which is the right question for an autocaptured tap but
 * the *wrong* one for an arbitrary `track(...)` — a background event (e.g. a network response that
 * completes after navigation) must not silently inherit whatever screen happens to be visible.
 * Explicit instrumentation keeps its own lexical scope; see the `autograph-compose` decorator.
 *
 * **Own an instance; don't share one globally.** Scope a [ScopeStack] to the tracker it feeds so
 * context can't leak across trackers or outlive a tracker swap on logout — [screenHistory] rides
 * along and inherits that scoping, which is what resets `previous_screen` when the tracker is
 * replaced. Sharing one instance across the *surfaces* of a single hybrid app is the exception, and
 * the point: pass it to `AutographProvider` so the Compose and native pipelines attribute against the
 * same context and share one `previous_screen` chain. That stack is then yours to replace when the
 * tracker is — the provider will not swap a caller-supplied stack out from under the native side.
 *
 * **Threading.** [push], [pushGlobal], [update], [remove], [maskScreen], [setActive] and the
 * origin-taking [current] must be called from the main thread ([push], [pushGlobal] and [remove]
 * mutate the frame list; the others mutate a frame's contents and republish the snapshot, or read
 * the list as it stands). The no-argument [current] is lock-free and safe from any thread: it
 * returns an immutable snapshot that is republished atomically on every mutation, so a background
 * reader always sees a whole, consistent context — never a half-applied one.
 */
public class ScopeStack {

    /**
     * The screen history that travels with this stack.
     *
     * Carried here rather than handed over separately so that sharing one stack across a hybrid app's
     * surfaces — the sharing this class already exists to enable — is *all* it takes to keep
     * `previous_screen` continuous across a Compose↔native transition. A second object to thread
     * through would be one more thing to forget, and forgetting it would produce a silently
     * discontinuous `previous_screen` rather than an error.
     */
    public val screenHistory: ScreenHistory = ScreenHistory()

    // Insertion-ordered; "innermost wins" for screen/section means later frames override earlier.
    // For scope, precedence follows the frame LINEAGE (parent links) rather than insertion order —
    // see [resolveScope]. Mutated only from the main thread (see class kdoc), so a plain list needs
    // no guard of its own.
    private val frames = ArrayList<ScopeFrame>()

    @Volatile
    private var snapshot: AmbientContext = AmbientContext.Empty

    /**
     * Pushes a frame contributing [scope] properties (low precedence — an explicit call-site
     * property always wins over them) and/or a [screen]/[section] (reserved keys, high precedence).
     * Returns a [ScopeHandle] to [update] or [remove] it with. A frame may carry scope only (an
     * `AutographScope` analogue), screen only (a `TrackedScreen` analogue), or both.
     *
     * A frame that names a [screen] owns its [section]: pushing `screen = "X"` with no section means
     * "screen X, no section", and an inner screen that declares none does not inherit the section of
     * an outer one still on the stack. A frame with a [section] but no [screen] is a section-only
     * marker that refines the surrounding screen instead.
     *
     * [parent] declares this frame's enclosing frame, forming a lineage the *scope* merge follows:
     * an enclosing scope contributes to a nested one, but two frames that are neither's ancestor
     * (siblings mounted at once — a list's rows, split-pane, a sheet over content) are ambiguous, and
     * [current] then drops *those* rather than guessing between them, keeping whatever encloses them
     * all — a route scope above ambiguous rows still attributes (see [resolveScope]). Pass the
     * [ScopeHandle] of the enclosing frame; `null` (the default) marks a root — its own tree, an
     * ambiguous *sibling* of anything nested under another root, not an ancestor of it; app-wide
     * context is declared with [pushGlobal] instead. Lineage is framework-independent — a native surface
     * declares it the same way — so this does not tie the stack to Compose. It affects only scope;
     * [screen]/[section] still resolve by insertion order ambiently (the origin-taking [current]
     * additionally ranks a container with its content).
     */
    public fun push(
        scope: Map<String, JsonElement> = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
        parent: ScopeHandle? = null,
    ): ScopeHandle = push(scope, screen, section, parent, boundary = false)

    /**
     * [push] for a frame that is also an **attribution boundary**: the pipeline pushing it can tell,
     * for every event it captures, whether the event happened inside this frame, and resolves such
     * events through [current] with the frame — or one beneath it — as the event's origin.
     *
     * A boundary changes what an origin-based resolution sees, in both directions. What is declared
     * *beneath* a boundary is visible only to events whose origin is at or beneath it, never to an
     * event resolved from outside: a native surface's frame is a boundary, so the screen a fragment
     * names, or the mask an unnamed one raises, reaches the taps that land in that fragment and not
     * the taps on the surface hosting it beside it. And a boundary is where the descent of
     * [current] stops: an event whose origin is a surface does not pick up the declarations of the
     * surfaces nested inside it, because its pipeline has already established the event is not in
     * them. See [current] for the exact rule.
     *
     * Which frames should be boundaries follows from that: those a pipeline can *localize* an event
     * to **and** that stand for a surface of their own. Every surface a native capture reserves a
     * frame for is one (an Activity, a fragment). The root frame a Compose provider pushes for its
     * composition is deliberately *not*, although the observer can localize a tap to it: a
     * composition is how the surface hosting it declares its screen, not a surface of its own, so a
     * native tap on a toolbar *beside* the `ComposeView` must still see the `TrackedScreen` inside
     * it. A plain [push] is the right call for any declaration that is not a surface — and a frame
     * under no boundary at all applies to every event, whatever its origin (see [current]).
     *
     * Static for the life of the frame; [update] does not revise it. [boundary] has no default so
     * that a call spelling none of the arguments still resolves to the plain [push] overload.
     */
    public fun push(
        scope: Map<String, JsonElement> = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
        parent: ScopeHandle? = null,
        boundary: Boolean,
    ): ScopeHandle {
        val frame = ScopeFrame(scope.asJsonObject(), screen, section, parent?.frame, boundary = boundary)
        frames.add(frame)
        snapshot = recompute()
        return ScopeHandle(frame)
    }

    /**
     * Pushes a frame whose [scope] is **app-wide**: it applies whatever subtree the event happened in,
     * and so it takes no part in the ambiguity rule of [resolveScope] — it neither makes another
     * scope-bearing frame ambiguous nor is dropped as one.
     *
     * "Every event" is the intent and not yet the whole truth, so read it as: every event that reads
     * this stack, plus the `Screen Viewed` emits that read [globalScope] directly. That is every
     * autocaptured tap on both pipelines, every native `Screen Viewed`, and — since #250 — every
     * Compose `Screen Viewed`. It is **not** an explicit `trackClick` / `trackImpression` / `track`
     * call, which carries its lexical scope and never reads this stack at all; those need the property
     * passed at the call site, or a merge at the transport. See
     * [#253](https://github.com/uny/autograph/issues/253). A tenant, an install id, an
     * experiment assignment pushed once at startup are the shape; it is the frame a host sharing one
     * stack between `AutographProvider` and the native captures reaches for.
     *
     * A plain [push] with no `parent` does not mean this. That root is its own tree in the forest —
     * nothing declares it as a parent, it declares none — so beside a provider's root it is an
     * ambiguous sibling of the `AutographScope` frames nested under that provider, and [current]
     * drops both rather than guess (#237). Inferring "a root nothing references is global" was
     * rejected: a frame's meaning would change retroactively the moment something is pushed under
     * it, and it would silently reinterpret the roots existing pipelines push (the iOS native screen
     * frames are roots). So app-wide is declared, here.
     *
     * Global frames merge **outermost**, in insertion order among themselves, so a screen's own
     * scope still wins a key clash and an explicit call-site property wins over both — the same
     * precedence a scope nested outside every other has. Global is fixed for the life of the frame,
     * like `boundary`: [update] revises the scope but not the flag, and refuses a `parent` — the
     * frame stays a root, because a parent under a boundary would hide it from every other origin,
     * which is the one thing a global frame must never be — and refuses a `screen` or `section`,
     * because a frame every origin sees would name the screen of every surface at once. The frame
     * is otherwise ordinary: it is under no boundary, so the origin-taking [current] sees it from
     * every origin; [remove] and [setActive] apply as to any frame.
     */
    public fun pushGlobal(scope: Map<String, JsonElement>): ScopeHandle {
        val frame = ScopeFrame(scope.asJsonObject(), screen = null, section = null, global = true)
        frames.add(frame)
        snapshot = recompute()
        return ScopeHandle(frame)
    }

    /**
     * Replaces the contents of the frame [handle] refers to, in place — keeping its position, and
     * thus its precedence, in the stack. Use this instead of [remove] + [push] when a still-mounted
     * frame's [scope]/[screen]/[section]/[parent] changes: re-pushing would move the frame to the top
     * and let it wrongly override inner frames that are still on the stack. A no-op (and no snapshot
     * churn) if the contents are unchanged, the handle was already removed, or it belongs to another
     * stack.
     *
     * A [parent] that is this frame itself, or one of its descendants, cannot describe a real nesting
     * and is refused: the frame becomes a root instead. See the note at the assignment below. A
     * [pushGlobal] frame refuses every [parent], [screen] and [section]: it is a scope-only frame
     * every origin sees, so it must neither be hidden from one nor name a screen for all (see there).
     *
     * This revises scope/screen/section and the parent link only. It never clears a mask set by
     * [maskScreen], and never changes whether the frame is active — those are switches on the frame
     * rather than part of the contents this replaces, and a pipeline revising a frame must not flip
     * either of them by accident. Note the consequence while a frame is masked: a [screen] written
     * here is stored but does not resolve, because [maskScreen] wins in [recompute].
     *
     * **There is no way to un-mask, and [remove] + [push] is not one.** Re-pushing is the very thing
     * the paragraph above rules out: the replacement lands at the end of the list, so a container
     * that un-masked this way would out-rank the inner frames its own content is still holding —
     * measured, `screen` went to the container while its `Detail` content was on display. It also
     * strands anything pushed with `parent =` the removed frame on a dangling link, which drops that
     * subtree's scope entirely (`{container, child}` became `{}`). A surface that may name a screen
     * must not mask in the first place; see [maskScreen] on why the switch is one-way.
     */
    public fun update(
        handle: ScopeHandle,
        scope: Map<String, JsonElement> = EmptyJsonObject,
        screen: String? = null,
        section: String? = null,
        parent: ScopeHandle? = null,
    ) {
        val frame = handle.frame
        if (frames.none { it === frame }) return
        val newScope = scope.asJsonObject()
        // A parent that this frame already encloses would close the lineage into a cycle, and the
        // ancestry walks in [resolveScope] — which run on the main thread inside every mutation —
        // would spin forever on it. Only reparenting can produce one (at [push] the frame does not
        // exist yet, so nothing can point at it), and no real nesting looks like this, so the link is
        // refused rather than trusted: the frame becomes a root. Dropping to a root is the
        // fail-closed reading — a root that branches off its siblings resolves to no scope — whereas
        // keeping the previous parent would leave a lineage the caller has just contradicted, which
        // can still merge and report a scope this stack exists to avoid guessing (#66). Refusing here
        // also keeps "parent links form a forest" true for every reader of [frames].
        val requested = parent?.frame
        val parentFrame = if (frame.global || (requested != null && frame.encloses(requested))) null else requested
        // A global frame is scope-only, and stays so: it survives resolution for every origin, so a
        // screen or section stored on it would name the screen of every surface at once.
        val newScreen = if (frame.global) null else screen
        val newSection = if (frame.global) null else section
        if (frame.scope == newScope && frame.screen == newScreen && frame.section == newSection &&
            frame.parent === parentFrame
        ) {
            return
        }
        frame.scope = newScope
        frame.screen = newScreen
        frame.section = newSection
        // Revised in place, like the other fields: a frame moved to a new lineage (e.g. a
        // `movableContentOf` subtree relocated under a different parent) must pick up its new parent
        // without being removed and re-pushed — re-pushing would change its identity and orphan any
        // frames still pointing to it as *their* parent. See [resolveScope] for how the link is used.
        frame.parent = parentFrame
        snapshot = recompute()
    }

    /**
     * Turns the frame [handle] refers to into a **mask**: a frame that declares *there is no screen
     * here*, clearing both screen and section rather than naming one. Frames after it still win, so a
     * mask hides what is *underneath* it, not everything.
     *
     * It exists for a surface that comes to the foreground and names no screen of its own — a native
     * container hosting content that reports its own screens. Without a mask, the frame of the screen
     * *underneath* stays the innermost one and every event captured on the unnamed surface is
     * attributed to the screen the user just left: a wrong value, not a missing one, and one that
     * survives every schema check.
     *
     * **One-way, and deliberately so.** A mask is a statement about the frame's *contents* — this
     * surface names no screen — which does not stop being true while the surface is off-screen. What
     * changes then is whether the frame participates at all, and that is [setActive]'s job. Two
     * switches for two different questions; folding them into one is what makes a mask outlive the
     * moment it was right for.
     *
     * A no-op if the frame is already masked, was already removed, or belongs to another stack.
     */
    public fun maskScreen(handle: ScopeHandle) {
        val frame = handle.frame
        if (frames.none { it === frame } || frame.maskScreen) return
        frame.maskScreen = true
        snapshot = recompute()
    }

    /**
     * Marks the frame [handle] refers to as taking part in resolution, or not. An inactive frame keeps
     * its position and its contents but contributes nothing — no screen, no section, no scope — as if
     * it were not on the stack at all. The frames nested under it drop out with it — ambiently
     * always, from an origin unless the frame belongs to the origin's own surface; see [recompute]
     * and the origin-taking [current] for the split.
     *
     * **This is the "is this surface the one on display?" bit, and position cannot answer it.**
     * Position stands in for *recency of becoming foreground*, which only holds while a frame leaves
     * when its surface does. A host that keeps several surfaces mounted at once and merely demotes the
     * ones off-screen — a pager caching neighbouring pages, a container that pauses rather than
     * destroys — breaks that: the demoted surface's frame stays where it was, and, being later in the
     * list than the one the user came back to, wins. Removing the frame instead would be wrong for the
     * opposite reason: it must come back, at the same position, without the surface being rebuilt.
     *
     * A frame is active when pushed. Toggling it does not move it, so a caller may reserve a position
     * once, early, and switch the frame on and off for the life of the surface.
     *
     * Handles that were already removed, or belong to another stack, are skipped. Passing a value the
     * frame already has is a no-op and republishes nothing.
     */
    public fun setActive(handle: ScopeHandle, active: Boolean) {
        val frame = handle.frame
        if (frames.none { it === frame } || frame.active == active) return
        frame.active = active
        snapshot = recompute()
    }

    /**
     * [setActive] for several frames at once, publishing **one** snapshot for the whole batch.
     *
     * A surface owns more than one frame — its screen, its mask, the scopes under it — and they have
     * to change together. Toggling them one at a time republishes an intermediate context in which
     * some of a surface's frames answer and others do not; a tap captured against that snapshot reads
     * a state the app was never in. Callers that own a surface should switch its frames through this,
     * not in a loop.
     *
     * No-ops (unknown handles, values already set) drop out; nothing is republished if none remain.
     *
     * Takes a [List] rather than the wider `Collection` it only needs, because this class is exported
     * into the Swift-facing `Autograph.xcframework` and Kotlin/Native maps only [List]/[Set]/[Map] to
     * an Objective-C collection. A `Collection` parameter degrades to an untyped `id`, i.e. `Any` in
     * Swift — so `setActive(handles: "oops", active: false)` would compile — and `autograph-context`
     * is SemVer-ABI-stable ([ADR 0001](docs/adr/0001-public-api-evolution.md) §1), so narrowing it
     * afterwards would need a major bump. Measured in the generated header, both before and after.
     */
    public fun setActive(handles: List<ScopeHandle>, active: Boolean) {
        var changed = false
        for (handle in handles) {
            val frame = handle.frame
            if (frames.none { it === frame } || frame.active == active) continue
            frame.active = active
            changed = true
        }
        if (changed) snapshot = recompute()
    }

    /**
     * Removes the frame [handle] refers to, by identity and independent of position — screen
     * transitions (a Compose `Crossfade`, an iOS interactive-pop that the user cancels) do not
     * guarantee frames leave in push order, so a positional pop would remove the wrong one.
     * Removing a handle that was already removed, or one from another stack, is a no-op.
     */
    public fun remove(handle: ScopeHandle) {
        if (frames.remove(handle.frame)) {
            snapshot = recompute()
        }
    }

    /**
     * The current merged ambient context, over every frame on the stack. Lock-free; safe from any
     * thread.
     *
     * This is the *ambient* answer — what is on display, as far as the stack can tell without knowing
     * where an event came from. A pipeline that does know should ask [current] with the event's
     * origin instead, which is what stops a declaration on one surface from attributing an event on
     * another.
     *
     * [setActive] takes a frame out of this answer **together with everything nested under it**: a
     * surface off display takes what is inside it off display too, so a screen declared in a
     * composition inside a demoted pager page or a paused Activity — a `TrackedScreen` — is absent
     * here for as long as its surface is, and so is a frame pushed under that surface while it is
     * demoted. The origin-taking overload does the same, except within the origin's own surface — see
     * [recompute] — and it is what makes this answer track the display rather than the most recent
     * composition (#228). A host app enriching its own events should still prefer the origin-taking
     * overload where it has one: this read cannot tell a sibling surface's declaration from the
     * event's own.
     */
    public fun current(): AmbientContext = snapshot

    /**
     * The ambient context **as seen from [origin]** — the innermost frame the calling pipeline could
     * attribute an event to. **Main thread only**, unlike the no-argument [current]: this reads the
     * frame list rather than a published snapshot, because the answer depends on the origin and is
     * computed per event.
     *
     * Three sets of frames take part, and only those:
     * - the **lineage** of [origin] — itself and every frame it is nested in, following the parent
     *   links declared at [push] / [update] — so a screen named by the surface hosting the origin,
     *   or a mask raised by it, applies;
     * - the **subtree** beneath [origin], stopping at (and excluding) any frame pushed as a
     *   `boundary` together with everything under it. What the origin's own pipeline could not
     *   localize further — the `TrackedScreen`s inside a composition — still applies, and what it
     *   could — a surface nested inside the origin, which the pipeline has established the event is
     *   not in — does not; and
     * - every frame that is **under no boundary at all**: a root the app pushed by hand, a screen a
     *   native pipeline that claims no views pushes (iOS), a Compose declaration outside any claimed
     *   surface. Nothing localizes those, so they apply to every event, exactly as they do ambiently
     *   — as *members*: a hand-pushed root's scope is still subject to [resolveScope]'s ambiguity
     *   rule unless it was pushed with [pushGlobal], which is what makes it app-wide.
     *   A boundary-free stack therefore resolves with the same *members* with or without an origin
     *   — except what is nested under an inactive frame of another surface, see below — (and
     *   in the same order, unless a frame was reparented under one pushed after it — see the ranking
     *   below).
     *
     * Everything else is out, and that is the point: a sibling surface's declaration, or its mask,
     * never reaches an event that did not happen in it, whatever the two frames' insertion order.
     * The survivors resolve in insertion order — with one correction: **a frame ranks no later than
     * the content nested in it.** A surface adopted late pushes its frame *after* the composition it
     * hosts (an Activity that predates the native capture's install is picked up at its next
     * resume), and insertion order alone would let that late mask blank the `TrackedScreen` inside
     * it; ranking the container where its earliest nested survivor ranks keeps the content winning,
     * as it did before the surface was adopted, while a root the app pushed by hand still ranks
     * where it was pushed. [maskScreen] and [resolveScope] then apply exactly as in [current]: a mask
     * raised on the lineage clears the screen beneath it, content pushed after it inside the same
     * surface still wins, and a subtree that branches is ambiguous just as it is ambiently. The
     * active bit reaches down the lineage here too — a frame nested under an inactive frame is out —
     * with one exemption: an inactive frame **of the origin's own surface** (on its lineage, or
     * beneath it with no boundary between) drops its contribution and nothing else. The origin is
     * the pipeline asserting the event happened in that surface, so what is declared inside it stays
     * (a tap on a pager page the host reports as demoted still attributes to the page's own
     * `TrackedScreen`, whether it lands on the Compose content or on a native button beside it —
     * the composition's provider frame mirrors the same demotion), while a *sibling* surface's demotion silences
     * that sibling's declarations for this event exactly as it does ambiently — on a shared,
     * boundary-free stack (iOS; Android without the native capture) that is the only thing keeping a
     * demoted neighbour page's screen off a tap on the current one. The ambient read is the
     * lineage-less case of the same rule; see [recompute].
     *
     * A frame in the lineage that is no longer on the stack (its surface was torn down but a stale
     * handle survived) contributes nothing, and the walk continues past it; an [origin] that is not
     * on the stack at all therefore resolves to whatever of its lineage still is. Fail-closed both
     * ways: a stale origin yields less context, never someone else's.
     */
    public fun current(origin: ScopeHandle): AmbientContext {
        val originFrame = origin.frame
        val lineage = generateSequence(originFrame) { it.parent }.toHashSet()
        // A parent link may point off this stack — at a frame since removed, or at another stack's
        // frame, which a hybrid app running two captures on two stacks can hand a composition. Such
        // a frame is transparent here: it contributes nothing and it is not a boundary, so what is
        // linked under it is neither hidden by it nor stranded. Only frames on this stack decide.
        val onStack = frames.toHashSet()
        // A demoted frame gates what is nested under it here too — unless it belongs to the origin's
        // own surface: its lineage, or the frames beneath it that no boundary separates from it. The
        // pipeline has placed the event in that surface, and the surface's own demotion (its host
        // paused, the pager page it is on peeking beside the current one) is not evidence against
        // that. A Compose provider's frame inside the surface mirrors the same demotion, so without
        // the second half a native tap on a demoted page lost the `TrackedScreen` composed beside it.
        val ownSurface = { frame: ScopeFrame -> frame in lineage || frame.isBeneathWithoutBoundary(originFrame, onStack) }
        val survivors = frames.filter { frame ->
            (ownSurface(frame) || !frame.isUnderABoundary(onStack)) && !frame.hasInactiveAncestorOn(onStack, exempt = ownSurface)
        }
        // A container ranks where its earliest nested survivor ranks (see the kdoc). Frames sharing a
        // rank are always one ancestor chain — the rank comes from one frame's index, and only its
        // ancestors can borrow it — so their depths are distinct and (rank, depth) totally orders
        // them, outermost first. That tie-break is load-bearing, not tidiness: the container is the
        // frame pushed LATER in the case this exists for, so a stable sort alone put the late mask
        // after the content and blanked it (measured).
        val index = HashMap<ScopeFrame, Int>(survivors.size * 2)
        survivors.forEachIndexed { i, frame -> index[frame] = i }
        val rank = HashMap<ScopeFrame, Int>(survivors.size * 2)
        for (frame in survivors) {
            var ancestor: ScopeFrame? = frame
            while (ancestor != null) {
                index[ancestor]?.let { own -> rank[ancestor] = minOf(rank[ancestor] ?: own, index.getValue(frame)) }
                ancestor = ancestor.parent
            }
        }
        return resolve(survivors.sortedWith(compareBy({ rank.getValue(it) }, { it.depth() })))
    }

    /**
     * Whether [ancestor] encloses this frame with no `boundary` frame on the path between them (this
     * frame itself may not be one either: a boundary is where a descent stops, so it is excluded
     * along with everything beneath it).
     */
    private fun ScopeFrame.isBeneathWithoutBoundary(ancestor: ScopeFrame, onStack: Set<ScopeFrame>): Boolean {
        var frame: ScopeFrame? = this
        while (frame != null && frame !== ancestor) {
            if (frame.boundary && frame in onStack) return false
            frame = frame.parent
        }
        return frame === ancestor && this !== ancestor
    }

    /** Whether this frame, or any frame it is nested in, is a `boundary` on this stack. */
    private fun ScopeFrame.isUnderABoundary(onStack: Set<ScopeFrame>): Boolean =
        generateSequence(this) { it.parent }.any { it.boundary && it in onStack }

    /**
     * The merged scope of every live [pushGlobal] frame, and nothing else — no screen, no section, no
     * frame that a surface or a declaration pushed.
     *
     * The narrow read for a path that must **not** consult the ambient snapshot. `ScopeStack`'s rule is
     * that only autocapture reads it: an explicit `track` call has a lexical scope of its own, and the
     * ambient one would attribute it to whatever surface happens to be on display. Global frames are
     * exempt from what motivates that rule — they carry no screen or section, and they take no part in
     * [resolveScope]'s sibling-ambiguity rule, which is the mechanism that could attribute a neighbour's
     * declaration to this event. So a global frame is the one thing on this stack an explicit emit can
     * safely read, and this is the only way to read it without also getting everything else (#250).
     *
     * `autograph-compose`'s `TrackedScreen` / `TrackScreenView` / `NavController.TrackScreenViews` use
     * it, so that a `Screen Viewed` from Compose carries an app-wide tenant or install id exactly as
     * the native pipelines' `Screen Viewed` does — the two differed until #250, invisibly, on the same
     * screen whose autocaptured taps carried it.
     *
     * Merged in insertion order among themselves, so a later global frame wins a key clash, matching
     * [resolveScope]. Respects the active bit exactly as every other read does: an inactive global
     * frame, or one nested under an inactive frame, contributes nothing. Empty when nothing global is
     * pushed, which is the common case.
     *
     * **Threading.** Main thread only, like the rest of this class.
     */
    public val globalScope: JsonObject
        get() {
            val onStack = frames.toHashSet()
            return frames
                .filter { it.global && it.active && it.scope.isNotEmpty() }
                .filterNot { it.hasInactiveAncestorOn(onStack, exempt = { false }) }
                .fold(EmptyJsonObject) { acc, frame -> acc.merge(frame.scope) }
        }

    /**
     * The ambient snapshot. Here the active bit reaches DOWN the lineage without exemption: a frame
     * nested under an inactive frame on this stack is out too, whatever its own bit says. Ambiently,
     * "is this surface on display?" has to govern everything inside the surface — a `TrackedScreen`
     * composed inside a demoted pager page, or a frame pushed under it *while* it is demoted (a page
     * composes at STARTED, before it is ever shown), would otherwise keep naming the ambient screen;
     * measured, a pager of Compose-declared pages read as the page most recently composed rather than
     * the one on display (#228). The origin-taking [current] applies the same rule but exempts the
     * origin's own surface: an origin is the pipeline asserting the event happened in that surface,
     * which an ambient read cannot claim. Only ancestors on THIS stack count, as in the origin walk
     * — a link off the stack is transparent, never a gate.
     */
    private fun recompute(): AmbientContext {
        val onStack = frames.toHashSet()
        return resolve(frames.filter { frame -> !frame.hasInactiveAncestorOn(onStack, exempt = { false }) })
    }

    /**
     * Whether any frame this one is nested in, still on the stack and not [exempt], is inactive.
     * [exempt] marks an origin's own surface: a demoted frame the event is known to have happened
     * under, or inside, does not gate what is beneath it.
     */
    private fun ScopeFrame.hasInactiveAncestorOn(onStack: Set<ScopeFrame>, exempt: (ScopeFrame) -> Boolean): Boolean =
        generateSequence(parent) { it.parent }.any { !it.active && it in onStack && !exempt(it) }

    private fun resolve(candidates: List<ScopeFrame>): AmbientContext {
        // Inactive frames are skipped ONCE, here, and the survivors are what both screen/section and
        // [resolveScope] see — so "does this frame take part?" is answered in one place rather than
        // being re-derived per field. See [setActive] for why position alone cannot answer it.
        // Here the bit is a frame's OWN: an inactive frame's contribution drops, what is nested
        // inside it keeps its lineage and its voice (pinned on the origin overload by ScopeStackTest's
        // `an_inactive_frame_still_carries_the_lineage_of_its_descendants`).
        // Whether the bit also reaches the frames nested under it is the caller's question — the
        // ambient [recompute] says yes, the origin-taking [current] says yes except within the
        // origin's own surface — and that is decided in the candidate list handed in, not here.
        val live = candidates.filter { it.active }
        if (live.isEmpty()) return AmbientContext.Empty
        var screen: String? = null
        var section: String? = null
        // A mask and an empty stack both leave [screen] null, but they say different things — see
        // [AmbientContext.screenMasked]. Tracked alongside screen because it is set and cleared by
        // exactly the frames that set and clear screen.
        var screenMasked = false
        for (frame in live) {
            // A frame that names a screen OWNS its section — it replaces both, so a section carried by
            // an outer screen cannot bleed onto an inner one that declared none (`push(screen = "X")`
            // means "screen X, no section", not "keep whatever section was showing"). A frame with no
            // screen is a section-only marker that refines the surrounding screen's context, so it
            // still updates section alone. This keeps screen and section composing independently in the
            // marker case while stopping the cross-screen leak in the replacement case. Screen/section
            // resolve by insertion order (one screen is active at a time, so "last mounted wins" is
            // right for them); only scope is lineage-aware — see [resolveScope].
            // A mask clears both, for the same reason a named frame replaces both: it says "the surface
            // on display names no screen", which cannot leave the outgoing screen's section behind
            // either. Frames after it still win, so content that does name a screen is unaffected.
            if (frame.maskScreen) {
                screen = null
                section = null
                screenMasked = true
            } else if (frame.screen != null) {
                screen = frame.screen
                section = frame.section
                screenMasked = false
            } else if (frame.section != null) {
                section = frame.section
            }
        }
        return AmbientContext(resolveScope(live), screen, section, screenMasked)
    }

    /**
     * The merged scope, resolved along the frame lineage rather than by insertion order.
     *
     * A scope-bearing frame may attribute a captured tap only if the tap is under it *whichever*
     * subtree it landed in — that is, only if the frame is comparable (ancestor, descendant, or self)
     * to every other scope-bearing frame. Those survivors are pairwise comparable, so they form a
     * single chain and merge outer→inner, the inner frame winning a key clash, exactly as nested
     * `AutographScope`s compose. Frames that branch away from one another — a list's rows each in
     * their own scope, split-pane content, a sheet or dialog over the screen beneath — are the
     * ambiguous part and drop out; what encloses them all stays, so a route scope above a list of
     * ambiguous rows still attributes every tap under it. Nothing is ever guessed, so no *wrong*
     * scope is reported (#66). Resolving which sibling a tap actually hit needs the tap position,
     * which this framework-independent stack does not have; that is a separate, additive layer —
     * `Modifier.autocaptureScope` in `autograph-compose`, which reads a marker off the tapped
     * element's own ancestry. Android only: the iOS accessibility bridge carries no such marker, so
     * the drop above is what an ambiguous iOS tap gets (#68).
     *
     * A [pushGlobal] frame is exempt from all of that: it encloses every subtree by declaration, so
     * it is neither compared nor dropped. Global frames merge first — outermost, so the lowest
     * precedence — in insertion order among themselves, and the unambiguous chain merges on top.
     * Without the exemption an app-wide root and a screen's own scope were two roots of different
     * trees, incomparable, and both dropped (#237).
     *
     * [live] is the active subset computed by [recompute]: a demoted surface's scope must not
     * attribute a tap on the surface that replaced it, so selection filters scope exactly as it
     * filters screen. Lineage is unaffected — [encloses] walks the real parent chain, inactive links
     * included, because containment is structural and does not stop being true off-screen.
     */
    private fun resolveScope(live: List<ScopeFrame>): JsonObject {
        val scoped = live.filter { it.scope.isNotEmpty() }
        if (scoped.isEmpty()) return EmptyJsonObject
        val (global, local) = scoped.partition { it.global }
        val base = global.fold(EmptyJsonObject) { acc, frame -> acc.merge(frame.scope) }
        val unambiguous = if (local.size <= 1) local else local.filter { frame ->
            local.all { other -> frame.encloses(other) || other.encloses(frame) }
        }
        // Ascending depth is outer→inner, so the deeper frame wins a shared key. Depths are distinct:
        // the survivors lie on one chain, which strictly increases in depth.
        return unambiguous.sortedBy { it.depth() }.fold(base) { acc, frame -> acc.merge(frame.scope) }
    }

    private fun JsonObject.merge(inner: JsonObject): JsonObject = if (isEmpty()) inner else JsonObject(this + inner)

    /**
     * Whether this frame is [other]'s ancestor, or [other] itself. Terminates because [update] refuses
     * exactly the links that would close a cycle, so the parent graph is always a forest.
     */
    private fun ScopeFrame.encloses(other: ScopeFrame): Boolean =
        generateSequence(other) { it.parent }.any { it === this }

    private fun ScopeFrame.depth(): Int {
        var depth = 0
        var ancestor = parent
        while (ancestor != null) {
            depth++
            ancestor = ancestor.parent
        }
        return depth
    }
}

/**
 * One pushed contribution to a [ScopeStack]. Opaque to callers; they hold a [ScopeHandle].
 *
 * Contents are mutable so [ScopeStack.update] can revise a frame without moving it (see there);
 * mutated only from the main thread, and every mutation republishes an immutable [AmbientContext].
 * [parent] records the frame's enclosing frame for the scope-lineage resolution in
 * [ScopeStack.resolveScope]; it is revisable (a frame can be reparented in place — see
 * [ScopeStack.update]) rather than fixed, so a relocated subtree does not keep a stale lineage.
 */
internal class ScopeFrame(
    var scope: JsonObject,
    var screen: String?,
    var section: String?,
    var parent: ScopeFrame? = null,
    /** See [ScopeStack.maskScreen]: this frame declares "no screen here" rather than naming one. */
    var maskScreen: Boolean = false,
    /** See [ScopeStack.setActive]: whether this frame takes part in resolution at all. */
    var active: Boolean = true,
    /** See the `boundary` [ScopeStack.push] overload. Fixed at push. */
    val boundary: Boolean = false,
    /** See [ScopeStack.pushGlobal]: exempt from the ambiguity rule, merged outermost. Fixed at push. */
    val global: Boolean = false,
)

/**
 * An opaque token identifying a pushed frame, for [ScopeStack.update], [ScopeStack.remove],
 * [ScopeStack.maskScreen] and [ScopeStack.setActive].
 */
public class ScopeHandle internal constructor(internal val frame: ScopeFrame)

/**
 * An immutable snapshot of a [ScopeStack]: the accumulated [scope] defaults plus the effective
 * [screen] / [section]. Use [enrich] to apply it to an event's properties.
 */
public class AmbientContext internal constructor(
    public val scope: JsonObject,
    public val screen: String?,
    public val section: String?,
    /**
     * Whether a [ScopeStack.maskScreen] frame is what left [screen] null — i.e. the surface on
     * display *asserts* it has no screen, rather than simply never having named one.
     *
     * Both cases read as `screen == null`, and Autograph's own pipelines treat them the same way:
     * they report no screen, and never substitute one from elsewhere. The distinction is published
     * for a pipeline that holds a screen name from another source and has to decide whether the
     * silence is an absence it may fill or an assertion it must respect — a mask is the latter, and
     * filling it reinstates the screen the user just left ([ScopeStack.maskScreen]).
     *
     * It is deliberately *not* a signal about history. `autograph-compose`'s tap observer once fell
     * back to [ScreenHistory.lastScreen] here and gated the fallback on this flag; the fallback is
     * gone. History records what has been *seen* and outlives it by design, so it cannot answer what
     * is on display, masked or not — every declaration in `autograph-compose` pushes a frame instead
     * (`TrackedScreen`, a bare `TrackScreenView`, and `NavController.TrackScreenViews` all do).
     */
    public val screenMasked: Boolean,
) {
    /**
     * Returns [properties] enriched with this context: [scope] merged underneath (so an explicit
     * property in [properties] wins over a scope default), then [screen] / [section] written on top
     * under those reserved keys (overwriting any same-named entry) — the same precedence the Compose
     * path applies via its scope decorator and `withScreenContext`.
     */
    public fun enrich(properties: Map<String, JsonElement>): JsonObject {
        var result = if (scope.isEmpty()) properties.asJsonObject() else JsonObject(scope + properties)
        if (screen != null) result = JsonObject(result + ("screen" to JsonPrimitive(screen)))
        if (section != null) result = JsonObject(result + ("section" to JsonPrimitive(section)))
        return result
    }

    internal companion object {
        val Empty: AmbientContext = AmbientContext(EmptyJsonObject, null, null, screenMasked = false)
    }
}
