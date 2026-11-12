package dev.ynagai.autograph.compose

import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only: applies the scope marker directly on this modifier chain, as the removed
 * `Modifier.autocaptureScope` did (#283). [AutographElementScope] applies the same node on Android,
 * on a wrapper of its own; tests use this form to pin the hit test and resolution underneath it.
 */
internal fun Modifier.scopeMarker(vararg properties: Pair<String, String>): Modifier =
    scopeMarker(JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }))

internal fun Modifier.scopeMarker(properties: JsonObject): Modifier = this then AutocaptureScopeElement(properties)
