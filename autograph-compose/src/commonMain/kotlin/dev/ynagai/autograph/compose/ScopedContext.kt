package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.ynagai.autograph.Tracker
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
    CompositionLocalProvider(LocalTracker provides scoped, content = content)
}

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
