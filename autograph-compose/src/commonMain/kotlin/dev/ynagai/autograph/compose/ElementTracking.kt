package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.semantics.semantics
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fires [name] the first time this element becomes visible — at least [minFractionVisible] of its
 * bounds inside the viewport for at least [minDurationMs] — and never again for the lifetime of
 * this composable instance, even if it later scrolls out of view and back. Built on the stable
 * [androidx.compose.ui.layout.onVisibilityChanged]; that API itself re-fires on every visibility
 * transition, so the "only once" bookkeeping happens here.
 *
 * Screen/section from the ambient [ScreenContext] (see [TrackedScreen]) are merged into
 * [properties] automatically when this element is nested inside one.
 */
public fun Modifier.trackImpression(
    name: String,
    properties: JsonObject = EmptyJsonObject,
    target: String? = null,
    minDurationMs: Long = 500L,
    minFractionVisible: Float = 0.5f,
): Modifier = composed {
    val tracker = LocalTracker.current
    val screenContext = LocalScreenContext.current
    var fired by remember { mutableStateOf(false) }
    onVisibilityChanged(minDurationMs = minDurationMs, minFractionVisible = minFractionVisible) { visible ->
        if (visible && !fired) {
            fired = true
            tracker.track(name, withScreenContext(properties, screenContext), target)
        }
    }.semantics { this[AutographInstrumentedKey] = true }
        .registerAutocaptureClaim(AutocaptureClaimKind.INSTRUMENTED)
}

/**
 * Fires [name] on click, then invokes [onClick]. Screen/section from the ambient [ScreenContext]
 * (see [TrackedScreen]) are merged into [properties] automatically when this element is nested
 * inside one.
 */
public fun Modifier.trackClick(
    name: String,
    properties: JsonObject = EmptyJsonObject,
    target: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val tracker = LocalTracker.current
    val screenContext = LocalScreenContext.current
    clickable {
        tracker.track(name, withScreenContext(properties, screenContext), target)
        onClick()
    }.semantics { this[AutographInstrumentedKey] = true }
        .registerAutocaptureClaim(AutocaptureClaimKind.INSTRUMENTED)
}

/**
 * Merges [context]'s screen (and section, if any) into [properties] under reserved `"screen"` /
 * `"section"` keys, overwriting any explicit same-named entries — mirrors how [Tracker.track]'s
 * own `target` parameter takes precedence over an explicit `properties["target"]`.
 */
internal fun withScreenContext(properties: JsonObject, context: ScreenContext?): JsonObject {
    if (context == null) return properties
    val withScreen = JsonObject(properties + ("screen" to JsonPrimitive(context.screen)))
    return context.section?.let { JsonObject(withScreen + ("section" to JsonPrimitive(it))) } ?: withScreen
}
