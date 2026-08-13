package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject

/**
 * The very node [autocaptureScope] applies, not a reimplementation of it — so the wrapper and the
 * modifier it replaces are behaviourally identical here by construction rather than by test, and the
 * deprecation cannot change what an Android app already reports.
 *
 * No traversal group: Android resolves the scope off the semantics ancestry directly, so grouping
 * would buy nothing and would change TalkBack's traversal for no reason.
 *
 * `@Composable` only because the `expect` is; nothing here needs a composition.
 */
@Composable
internal actual fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier =
    this then AutocaptureScopeElement(properties)
