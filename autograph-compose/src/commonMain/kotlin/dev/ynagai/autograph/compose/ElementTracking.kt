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
 *
 * **Does not suppress autocapture.** Unlike [trackClick], this marks nothing as instrumented: it
 * reports a *visibility* event and never reports a click, so autocapture reporting a tap on this
 * element duplicates nothing. Marking it was the original design and it cost an event rather than
 * saving one — a tappable element that also reported an impression produced no click event at all,
 * on either platform ([#158](https://github.com/uny/autograph/issues/158)). An element that should
 * report neither is what [autographIgnore] is for.
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
    }
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
    val claims = LocalAutocaptureClaims.current
    clickable {
        tracker.track(name, withScreenContext(properties, screenContext), target)
        // After the explicit event is recorded and before the caller's handler, because the mark's
        // whole meaning is "this tap already produced an event, so autocapture must not add one".
        // If `track` throws there is no explicit event, the mark never happens, and autocapture
        // reports the tap — the right way round: a duplicate is recoverable, a missing event is not.
        //
        // This is what the iOS resolver reads in place of the geometry it used to compare (see
        // [AutocaptureClaims]). Android ignores it and reads [AutographInstrumentedKey] below off the
        // tapped node's ancestry instead, which is strictly better evidence where it is available.
        claims?.markInstrumentedClickExecuted()
        onClick()
    }.semantics { this[AutographInstrumentedKey] = true }
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
