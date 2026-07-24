package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
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

    override fun track(name: String, properties: JsonObject, target: String?): Unit =
        delegate.track(name, mergeScope(scope, properties), target)

    override fun screen(name: String, properties: JsonObject): Unit =
        delegate.screen(name, mergeScope(scope, properties))

    // Traits describe the user, not the screen — the scope is deliberately not applied.
    override fun identify(userId: String, traits: JsonObject): Unit =
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
internal fun mergeScope(scope: JsonObject, properties: JsonObject): JsonObject =
    if (scope.isEmpty()) properties else JsonObject(scope + properties)
