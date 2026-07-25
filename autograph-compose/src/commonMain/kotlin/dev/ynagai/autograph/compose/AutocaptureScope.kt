package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Carries an element's autocapture scope down the semantics tree. See [autocaptureScope]. */
internal val AutographScopeKey: SemanticsPropertyKey<JsonObject> = SemanticsPropertyKey("AutographScope")

/**
 * Attaches [properties] to autocaptured taps that land in this element's subtree — the per-element
 * counterpart of [AutographScope], for the case that composable cannot serve: sibling scopes mounted
 * at the same time, such as a list's rows each carrying their own `article_id`.
 *
 * ```kotlin
 * LazyColumn {
 *     items(articles) { article ->
 *         Row(
 *             Modifier
 *                 .autocaptureScope("article_id" to article.id)
 *                 .clickable { open(article) },
 *         ) { ArticleRow(article) }
 *     }
 * }
 * ```
 *
 * **This affects autocapture only** — unlike [AutographScope], which reaches every event emitted
 * from its content. A `Modifier` cannot install a `CompositionLocal` for the element's children, so
 * `Modifier.trackClick` / `trackImpression` and plain `LocalTracker.current.track(...)` calls inside
 * this subtree do *not* pick these properties up; pass them explicitly, or wrap the content in
 * [AutographScope] as well. This is a companion to [AutographScope], not a replacement for it.
 *
 * Scopes compose along the tapped element's ancestry: an enclosing `autocaptureScope` contributes to
 * a nested one, the inner winning a key clash, and [AutographScope] frames enclosing the element
 * contribute underneath both. Screen and section from [TrackedScreen] still win over all of them, as
 * they do for any other event.
 *
 * **Android only, for now.** Resolution reads the marker back off the tapped element's ancestry in
 * the semantics tree, which is what makes it exact — mount order and geometry never enter, so
 * `Modifier.clip`, z-order and mid-animation positions cannot mislead it. Compose Multiplatform's
 * iOS accessibility bridge carries none of the custom semantics an element declares, so on iOS this
 * modifier currently contributes nothing and taps keep resolving as they did before it
 * ([AutographScope] siblings there stay ambiguous and drop, per `ScopeStack`). The failure is a
 * missing property rather than a wrong one, which is the tradeoff this library takes deliberately
 * — see [#68](https://github.com/uny/autograph/issues/68). Expect an `article_id` on Android and no
 * `article_id` on iOS for the same tap until that gap closes.
 */
public fun Modifier.autocaptureScope(vararg properties: Pair<String, String>): Modifier =
    autocaptureScope(JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }))

/**
 * [autocaptureScope] overload taking a [JsonObject], for scope values that aren't strings (numbers,
 * booleans, nested objects) or that are already assembled as a [JsonObject].
 */
public fun Modifier.autocaptureScope(properties: JsonObject): Modifier =
    semantics { this[AutographScopeKey] = properties }

/** The scope this node declares directly, or empty. Hit-testing convenience; see [autocaptureScope]. */
internal fun SemanticsConfiguration.autocaptureScopeOrEmpty(): JsonObject =
    getOrNull(AutographScopeKey) ?: EmptyJsonObject
