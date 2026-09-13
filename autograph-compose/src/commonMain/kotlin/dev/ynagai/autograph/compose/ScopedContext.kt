package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.context.AmbientContext
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Attaches [properties] to every event emitted from [content] — a screen-scoped context such as
 * the `article_id` on an `articles/{article_id}` route.
 *
 * ```kotlin
 * composable("articles/{article_id}") { entry ->
 *     val id = entry.arguments!!.getString("article_id")!!
 *     AutographScope("article_id" to id) {
 *         ArticleScreen()   // every event below carries article_id
 *     }
 * }
 * ```
 *
 * The scope is installed by wrapping the ambient [LocalTracker] in a decorator that merges these
 * properties into each event, so it applies uniformly to everything nested in [content] that reads
 * the tracker — `Modifier.trackClick` / `trackImpression`, [TrackScreenView] / [TrackedScreen], and
 * plain `LocalTracker.current.track(...)` calls — with no per-call-site wiring.
 *
 * Scopes nest and compose: an inner [AutographScope] adds to (and, on a key clash, overrides) the
 * enclosing one. A property the call site passes explicitly always wins over the ambient scope, so
 * the scope acts as a default that a specific event can still refine. Screen/section from
 * [TrackedScreen] compose independently and are unaffected.
 *
 * `identify` traits are intentionally **not** scoped: they describe the user, not the screen the
 * event was fired on.
 */
@Composable
public fun AutographScope(
    vararg properties: Pair<String, String>,
    content: @Composable () -> Unit,
) {
    AutographScope(
        properties = JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }),
        content = content,
    )
}

/**
 * [AutographScope] overload taking a [JsonObject], for scope values that aren't strings (numbers,
 * booleans, nested objects) or that are already assembled as a [JsonObject].
 */
@Composable
public fun AutographScope(
    properties: JsonObject,
    content: @Composable () -> Unit,
) {
    val parent = LocalTracker.current
    // Keyed on the parent tracker and the (structurally-compared) scope so the decorator instance
    // stays stable across recompositions — otherwise effects keyed on the tracker
    // (LaunchedEffect/DisposableEffect in the screen composables) would restart on every frame.
    val scoped = remember(parent, properties) {
        // Flatten nested scopes into a single decorator over the original tracker: constant wrapper
        // depth and one merge per event no matter how deeply routes nest. An inner key overrides the
        // enclosing scope's same key (`outer + inner`, right wins).
        if (parent is ScopedTracker) {
            ScopedTracker(parent.delegate, JsonObject(parent.scope + properties))
        } else {
            ScopedTracker(parent, properties)
        }
    }
    // Mirror this scope into the ambient stack so autocapture — which observes taps ABOVE this
    // decorator, at the provider root, and never sees the LocalTracker we install below — attributes
    // them with the scope too. Only this level's own [properties] is pushed; the stack re-accumulates
    // nested frames along their lineage, so no double-counting despite the decorator above being
    // pre-flattened. The decorator stays the source of truth for explicit `track` calls (lexical
    // scope); the stack serves the capture path (dynamic scope). See [ScopeStack].
    MirrorAmbientFrame(LocalScopeStack.current, scope = properties) {
        CompositionLocalProvider(LocalTracker provides scoped, content = content)
    }
}

/**
 * Mirrors one frame into [stack] for the lifetime of [content]: pushed on enter, removed on exit,
 * and revised IN PLACE when [scope]/[screen]/[section] change. The frame is linked to the nearest
 * enclosing [MirrorAmbientFrame] as its parent, so the stack can resolve scope by lineage (an
 * enclosing scope refines a nested one; sibling scopes mounted at once are ambiguous and dropped —
 * see [ScopeStack]). [content] is wrapped so nested frames pick this one up as their parent.
 *
 * The in-place revision is load-bearing, not an optimization. Re-pushing a frame whose value changed
 * would move it to the top of the insertion order and let an OUTER screen/section wrongly override a
 * still-mounted INNER one — e.g. an outer `TrackedScreen` whose section updates while an inner screen
 * is on stack. Pushing once (keyed on [stack] alone) and updating keeps each frame at its position
 * for as long as it is mounted. Only the capture path reads this; explicit `track` keeps its lexical
 * scope via the decorator either way.
 */
@Composable
internal fun MirrorAmbientFrame(
    stack: ScopeStack,
    scope: JsonObject = EmptyJsonObject,
    screen: String? = null,
    section: String? = null,
    content: @Composable () -> Unit,
) {
    // A plain box, not state: written from the effect below and read only by the SideEffect, so it
    // must not itself invalidate the composition.
    val handle = remember(stack) { arrayOfNulls<ScopeHandle>(1) }
    // The enclosing frame's holder. Its DisposableEffect ran before our SideEffect (it composed
    // first, and all DisposableEffects in an apply phase run before any SideEffect), so by the time
    // our SideEffect reads `parentHolder[0]` below it is already filled — see [LocalScopeParent].
    val parentHolder = LocalScopeParent.current
    // Pushed EMPTY and filled by the SideEffect below (which runs in this same apply phase, long
    // before any tap can read the stack) so this effect references no changing value and therefore
    // never restarts — restarting is exactly what would reorder the frame.
    DisposableEffect(stack) {
        val pushed = stack.push()
        handle[0] = pushed
        onDispose {
            stack.remove(pushed)
            handle[0] = null
        }
    }
    // scope/screen/section AND the parent link are all set here, in place, on every recomposition.
    // Routing the parent through `update` (not the one-shot `push` above) is what keeps a
    // `movableContentOf` subtree correct: when it relocates under a different frame, this SideEffect
    // re-runs with the new `parentHolder` and reparents in place, rather than keeping the stale link
    // a push-time capture would freeze in. `update` no-ops when nothing changed, so this is cheap.
    SideEffect {
        handle[0]?.let {
            stack.update(it, scope = scope, screen = screen, section = section, parent = parentHolder?.get(0))
        }
    }
    CompositionLocalProvider(LocalScopeParent provides handle, content = content)
}

/**
 * The root frame of one [AutographProvider]'s composition: pushed for the lifetime of [content] and
 * provided as the [LocalScopeParent] of everything inside, so every [MirrorAmbientFrame] in this
 * composition nests under it.
 *
 * It exists so the tap observer has an **origin** to resolve from. A composition is the finest
 * thing the observer can localize a tap to — it hit-tests its own semantics tree, but a
 * `TrackedScreen` or [AutographScope] has no node of its own, so which of them a tap fell under is
 * not something it can read — and that is exactly the granularity `ScopeStack.current(origin)`
 * wants: the lineage of this frame (the surface hosting the composition, and what it declares or
 * masks) plus the declarations beneath it, and nothing a sibling surface pushed. See
 * [ProviderOrigin] for how the frame is linked to its host and read at tap time.
 *
 * Deliberately **not** a `boundary` (`ScopeStack.push`), although the observer can localize to it.
 * A composition is how the surface hosting it declares its screen, not a surface of its own: a
 * native tap on a toolbar beside the `ComposeView`, or on an `AndroidView` interop button inside
 * it, resolves from the *host's* frame and must still see the `TrackedScreen` in here — and a
 * provider nested inside another's composition (a tracker swapped for a subtree) is content the
 * outer observer cannot localize out of, so its declarations must stay visible to the outer
 * provider's taps too. Each of those was a correct→absent or correct→wrong regression when this
 * frame was a boundary; all three are pinned.
 *
 * The frame carries no contents and is never [ScopeStack.update]d with any, so on its own it
 * changes nothing about the ambient [ScopeStack.current] — an empty frame contributes nothing.
 * What it does carry is the composition's **selection**: the frame is active exactly while the
 * composition's [LocalLifecycleOwner] is `RESUMED`, and ambiently the bit reaches everything nested
 * under it, so the `TrackedScreen`s and [AutographScope]s of a composition go silent together with
 * the surface showing it and come back with it. `RESUMED ↔ STARTED` is the demotion signal every
 * host emits: a `ViewPager2` page moved off display, an Activity behind a permission prompt, a
 * Compose `UIViewController` after `viewDidDisappear` (which Compose Multiplatform maps to
 * `CREATED`). Measured before this: a pager of Compose-declared pages read ambiently as the page most
 * recently *composed*, not the one on display, because a page composes at `STARTED` — before it is
 * ever shown — and its frames were born active (#228). Seeding from the owner's current state is
 * what makes such a page start silent; the observer then follows the transitions. Taps are not what
 * this is for: they resolve from an origin, which does not propagate the bit (see
 * `ScopeStack.current(origin)`), so a tap on a page the host reports as demoted — one peeking beside
 * the current page — still attributes to that page's own screen. That holds only because
 * [ProviderOrigin.resolve] never reads the ambient snapshot while this frame exists, claimed host
 * or not.
 */
@Composable
internal fun ProviderFrame(
    stack: ScopeStack,
    content: @Composable (origin: ProviderOrigin) -> Unit,
) {
    val holder = remember(stack) { arrayOfNulls<ScopeHandle>(1) }
    // A provider nested inside another's composition is not a separate surface: its frame nests
    // under the enclosing provider's, and that is also where its taps resolve from — the view walk
    // is the OUTERMOST provider's job alone, since every provider in one composition shares the
    // same host view.
    val enclosing = LocalScopeParent.current
    val hostSurface = if (enclosing == null) rememberHostSurfaceLookup() else remember(enclosing) { { enclosing[0] } }
    val origin = remember(holder, hostSurface) { ProviderOrigin(holder, hostSurface) }
    DisposableEffect(stack) {
        val pushed = stack.push(parent = enclosing?.get(0))
        holder[0] = pushed
        onDispose {
            stack.remove(pushed)
            holder[0] = null
        }
    }
    // Declared AFTER the push effect so it runs after it in the same apply phase and finds the
    // handle. Seeded explicitly rather than left to `addObserver`'s catch-up dispatch, which replays
    // the events up to the current state and so would leave a STARTED owner's frame active.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(stack, lifecycle) {
        holder[0]?.let { stack.setActive(it, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.let { stack.setActive(it, true) }
                Lifecycle.Event.ON_PAUSE -> holder[0]?.let { stack.setActive(it, false) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (enclosing == null) KeepLinkedToHost(stack, origin)
    CompositionLocalProvider(LocalScopeParent provides holder) { content(origin) }
}

/**
 * Links the [ProviderFrame] under the surface hosting the composition as soon as that surface has
 * claimed its view, and again whenever the host view re-attaches to a window.
 *
 * [ProviderOrigin.resolve] links at tap time, which covers every tap *in this composition*. What it
 * cannot cover is an event somewhere else: a frame under no boundary applies to every origin, so a
 * composition that has not been tapped yet — the off-screen page of a pager — would, unlinked,
 * lend its `TrackedScreen` to a tap on the page beside it. Measured: page A carried `PageB`. The
 * link is made from a posted runnable because the claim on a late-added fragment's view lands
 * after this composition is created (see [ProviderOrigin]) — and re-made when a surface above the
 * host view claims or releases its root later still (a native capture installed after this
 * composition existed), which the capture announces through `View.addAutographScopeOwnerListener`.
 * No-op off Android.
 */
@Composable
internal expect fun KeepLinkedToHost(stack: ScopeStack, origin: ProviderOrigin)

/**
 * Where a tap in this composition is resolved from: the [ProviderFrame]'s handle, linked to the
 * frame of the surface hosting the composition **at the moment of the tap**.
 *
 * Linking at tap time rather than once at composition is deliberate, and measured. A composition
 * is created when its view attaches to the window, and for a fragment added while its Activity is
 * already showing that is *before* `onFragmentViewCreated` — the point at which the native screen
 * capture claims the fragment's view. A lookup at composition time then finds the **Activity's**
 * claim instead of the fragment's: a wrong owner, not a missing one. By the time any tap can arrive
 * the claim is in place, and re-reading it per tap also survives a surface being re-claimed under a
 * fresh frame (a fragment's view re-created) or the native capture being installed late. The link
 * is written through [ScopeStack.update], which no-ops when nothing changed, so a steady state
 * costs one ancestry walk per tap and no snapshot churn.
 *
 * With no surface claiming the host view — no native screen capture installed, a platform with no
 * such capture at all, or a `Dialog`/`Popup` window whose view tree sits under no surface's root —
 * the tap still resolves from the composition's own frame, unlinked: there is nothing to localize
 * against, so every frame under no boundary applies (a frame the app pushes by hand stays visible,
 * exactly as it does under a claimed host; a claimed native surface's frame beside it does not —
 * absent, never borrowed), the composition's own declarations apply whatever this frame's bit says,
 * and another composition's on the same stack apply only while *its* provider frame is active — see
 * `ScopeStack.current(origin)`. Falling back to the ambient [ScopeStack.current] here instead was
 * measured wrong:
 * that read propagates a demoted host's bit down to the composition (see [ProviderFrame]), so a tap
 * on a visible page whose host reports `STARTED` — a `ViewPager2` neighbour peeking beside the
 * current page — lost the page's own `TrackedScreen` and, on a shared stack, took the current
 * page's instead. Nothing claims a host view on iOS or on Android without the native capture, so
 * that fallback was the path of every Compose tap there, not an edge case.
 */
internal class ProviderOrigin(
    private val root: Array<ScopeHandle?>,
    private val hostSurface: () -> ScopeHandle?,
) {
    /** Re-reads the host surface and links the frame under it in place; returns the host. */
    fun link(stack: ScopeStack): ScopeHandle? {
        val frame = root[0] ?: return null
        val host = hostSurface()
        stack.update(frame, parent = host)
        return host
    }

    /**
     * The context for a tap in this composition. Main thread, like the tap dispatch it runs in.
     * Always from this composition's frame while it exists — see the class kdoc for why an unclaimed
     * host must not fall back to the ambient read.
     */
    fun resolve(stack: ScopeStack): AmbientContext {
        val frame = root[0] ?: return stack.current()
        link(stack)
        return stack.current(frame)
    }
}

/**
 * The frame of the surface hosting this composition, read **at call time** — the native screen
 * capture's claim on the nearest claimed ancestor of the host view. Null where nothing claims it.
 * See [ProviderOrigin] for why this is a lookup and not a value.
 */
@Composable
internal expect fun rememberHostSurfaceLookup(): () -> ScopeHandle?

/**
 * The nearest enclosing [MirrorAmbientFrame]'s handle holder, or null at the root. Carries the parent
 * link down so a nested frame can declare its lineage to [ScopeStack] (which resolves scope by
 * ancestry, not insertion order). The value is the enclosing frame's `remember`ed holder array, whose
 * identity is stable across recompositions, so providing it triggers no extra recomposition; its
 * single slot is filled by that frame's `DisposableEffect`, which — because the parent composes
 * first — has already run by the time a child frame's own effect reads it.
 */
internal val LocalScopeParent: ProvidableCompositionLocal<Array<ScopeHandle?>?> =
    staticCompositionLocalOf { null }

private val fallbackScopeStack = ScopeStack()

/**
 * Per-[AutographProvider] ambient [ScopeStack]. A provider-owned stack is scoped to the provider
 * (keyed on the tracker) because context must not leak between independent trackers and must reset
 * when the tracker is replaced (e.g. after logout) — and since the stack carries screen history too,
 * that scoping is also what resets `previous_screen`. A stack the caller passed to
 * [AutographProvider] instead — the hybrid-app case, where a native pipeline holds the same instance
 * — is used as-is and never swapped out; that lifetime is the caller's. Outside a provider — where
 * events are dropped anyway — reads fall back to a shared instance.
 */
internal val LocalScopeStack: ProvidableCompositionLocal<ScopeStack> =
    staticCompositionLocalOf { fallbackScopeStack }

/**
 * A [Tracker] decorator that merges an ambient [scope] into the properties of every `track` /
 * `screen` event before delegating. Installed by [AutographScope] via [LocalTracker].
 */
internal class ScopedTracker(
    val delegate: Tracker,
    val scope: JsonObject,
) : Tracker {

    override fun track(name: String, properties: Map<String, JsonElement>, target: String?): Unit =
        delegate.track(name, mergeScope(scope, properties), target)

    override fun screen(name: String, properties: Map<String, JsonElement>): Unit =
        delegate.screen(name, mergeScope(scope, properties))

    // Traits describe the user, not the screen — the scope is deliberately not applied.
    override fun identify(userId: String, traits: Map<String, JsonElement>): Unit =
        delegate.identify(userId, traits)

    override fun notifyForeground(): Unit = delegate.notifyForeground()
    override fun notifyBackground(): Unit = delegate.notifyBackground()
    override fun flush(): Unit = delegate.flush()
    override fun reset(): Unit = delegate.reset()

    // A scoped view owns no resources of its own; disposing it must not tear down the real tracker.
    // Close the tracker you passed to AutographProvider instead.
    override fun close() {}
}

/**
 * Returns [properties] with [scope] merged underneath: a scope entry fills a key the call site did
 * not set, and an explicit call-site entry wins on a clash (`scope + properties`, right wins).
 */
internal fun mergeScope(scope: JsonObject, properties: Map<String, JsonElement>): JsonObject =
    if (scope.isEmpty()) properties.asJsonObject() else JsonObject(scope + properties)
