package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject

/**
 * The very node ([AutocaptureScopeElement]) the removed `Modifier.autocaptureScope` applied, not a
 * reimplementation of it, so migrating from that modifier changed nothing an Android app reports.
 *
 * No traversal group: Android resolves the scope off the semantics ancestry directly, so grouping
 * would buy nothing and would change TalkBack's traversal for no reason.
 *
 * `@Composable` only because the `expect` is; nothing here needs a composition.
 */
@Composable
internal actual fun Modifier.autographElementScopeMarker(properties: JsonObject): Modifier =
    this then AutocaptureScopeElement(properties)
