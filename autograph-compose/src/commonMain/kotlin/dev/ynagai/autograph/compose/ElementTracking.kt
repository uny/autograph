@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.compose

import androidx.compose.foundation.clickable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.semantics.semantics
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.EventKinds
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.withEventMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 *
 * The event carries `kind` `impression` and both thresholds in its envelope metadata (see
 * [dev.ynagai.autograph.EventMetadata]), so a dashboard can tell which definition of "seen" it counts.
 * Inside a [TrackedScreen] it also carries the screen's visit id (#242).
 *
 * In a lazy list, "this composable instance" is the wrong unit: an item scrolled out of view is
 * disposed and reports again when it scrolls back. Use the overload that takes a `key` there.
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
    val visit = LocalScreenVisit.current
    var fired by remember { mutableStateOf(false) }
    onVisibilityChanged(minDurationMs = minDurationMs, minFractionVisible = minFractionVisible) { visible ->
        if (visible && !fired) {
            fired = true
            tracker.trackImpressionEvent(name, properties, target, minDurationMs, minFractionVisible, screenContext, visit)
        }
    }
}

/**
 * [trackImpression], de-duplicated by [key] rather than by composable instance (#243): within one
 * unit, an item reports at most once however often it is disposed and re-composed — which is what a
 * lazy list does to an item scrolled out of view and back. Pass the same value as the list's own
 * `key`. It must keep a stable `equals` / `hashCode` for as long as the unit lasts; it is held, not
 * reported, so put whatever identifies the item for analysis in [properties] as well.
 *
 * The unit is the enclosing [TrackedScreen]'s visit — the same unit its `screen_view_id` names — or
 * an [ImpressionScope], whichever is innermost:
 * - A new visit reports again: a renamed screen, or a return that re-creates the screen's
 *   composition. A change of section keeps the visit and does not; to count per section, include it
 *   in [key] or [target].
 * - An impression is one definition applied to one item: [name], both thresholds, the effective
 *   target ([target], else `properties["target"]`) and [key]. The same item in two lists with
 *   different names or targets reports in each; instrumented identically twice, it reports once.
 * - When the unit starts over, or this element's [key] changes, its visibility measurement starts
 *   over too: time spent visible before does not count toward the new one.
 *
 * Outside both, there is nothing to de-duplicate in. The element then behaves as the overload
 * without a key — once per composable instance — and a one-time console warning says so.
 *
 * "Once" means once per emission attempt: the key is recorded after `track` returns, so it marks an
 * event handed to the tracker, not one delivered — delivery is not observable here. A `track` that
 * throws is not caught: the exception leaves through Compose's layout pass, which runs visibility
 * callbacks, as it would from any of them.
 *
 * Not `rememberSaveable`: that would outlive the visit into the navigation back stack's saved state
 * and not report again on a revisit, and in a list without keys it is positional, so the record can
 * move to a different item.
 */
public fun Modifier.trackImpression(
    name: String,
    key: Any,
    properties: JsonObject = EmptyJsonObject,
    target: String? = null,
    minDurationMs: Long = 500L,
    minFractionVisible: Float = 0.5f,
): Modifier = composed {
    val tracker = LocalTracker.current
    val screenContext = LocalScreenContext.current
    val visit = LocalScreenVisit.current
    val registry = LocalImpressionRegistry.current
    val impression = ImpressionKey(
        name = name,
        minDurationMs = minDurationMs,
        // -0f and 0f are one threshold; Float.equals tells them apart.
        minFractionVisible = if (minFractionVisible == 0f) 0f else minFractionVisible,
        target = target ?: (properties["target"] as? JsonPrimitive)?.contentOrNull,
        key = key,
    )
    if (registry == null) NoImpressionUnit.warn()
    // Without a unit: today's per-instance behaviour, per key.
    var firedHere by remember(impression) { mutableStateOf(false) }

    // `onVisibilityChanged` keeps its visible state and dwell timer across an update, so restarting
    // the measurement takes a new node. Dropping the modifier for one frame detaches the old one; the
    // effect then re-adds it, fresh. Initially the two are equal and the node is there at once.
    val measurement = Measurement(registry, registry?.generation, impression)
    var measuring by remember { mutableStateOf(measurement) }
    if (measuring != measurement) {
        SideEffect { measuring = measurement }
        Modifier
    } else {
        onVisibilityChanged(minDurationMs = minDurationMs, minFractionVisible = minFractionVisible) { visible ->
            if (!visible) return@onVisibilityChanged
            if (registry != null) {
                if (impression in registry) return@onVisibilityChanged
                tracker.trackImpressionEvent(name, properties, target, minDurationMs, minFractionVisible, screenContext, visit)
                registry.record(impression)
            } else if (!firedHere) {
                tracker.trackImpressionEvent(name, properties, target, minDurationMs, minFractionVisible, screenContext, visit)
                firedHere = true
            }
        }
    }
}

/** Everything whose change must restart a keyed impression's visibility measurement. */
private data class Measurement(val registry: ImpressionRegistry?, val generation: Int?, val impression: ImpressionKey)

/** The one-time warning for a keyed impression with no unit to de-duplicate in. */
private object NoImpressionUnit {
    private var warned = false

    fun warn() {
        if (!warned) {
            warned = true
            // The console, as MissingTracker does: Compose has no route to AutographConfig.logger.
            println(
                "Autograph: Modifier.trackImpression(key = ...) is outside any TrackedScreen or " +
                    "ImpressionScope, so it reports once per composable instance and a lazy list re-reports " +
                    "items scrolled back into view. Wrap the list in ImpressionScope { ... }.",
            )
        }
    }
}

private fun Tracker.trackImpressionEvent(
    name: String,
    properties: JsonObject,
    target: String?,
    minDurationMs: Long,
    minFractionVisible: Float,
    screenContext: ScreenContext?,
    visit: ScreenVisit?,
) {
    val tagged = withScreenContext(properties, screenContext).withEventMetadata(
        EventKinds.IMPRESSION,
        impressionMinDurationMs = minDurationMs,
        impressionMinFractionVisible = minFractionVisible.toDecimalDouble(),
        screenViewId = visit?.idFor(screenContext?.screen),
    )
    track(name, tagged, target)
}

/**
 * Fires [name] on click, then invokes [onClick]. Screen/section from the ambient [ScreenContext]
 * (see [TrackedScreen]) are merged into [properties] automatically when this element is nested
 * inside one. The event carries `kind` `click` in its envelope metadata, and inside a [TrackedScreen]
 * the screen's visit id (#242).
 */
public fun Modifier.trackClick(
    name: String,
    properties: JsonObject = EmptyJsonObject,
    target: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val tracker = LocalTracker.current
    val screenContext = LocalScreenContext.current
    val visit = LocalScreenVisit.current
    val claims = LocalAutocaptureClaims.current
    clickable {
        val tagged = withScreenContext(properties, screenContext)
            .withEventMetadata(EventKinds.CLICK, screenViewId = visit?.idFor(screenContext?.screen))
        tracker.track(name, tagged, target)
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

/**
 * This threshold through its string form: `0.3f` becomes `0.3`, where [Float.toDouble] gives
 * `0.30000001192092896`. `Float.toString` prints the shortest decimal that reads back as the same
 * float, so a threshold written with few digits comes out as written. Measured on the JVM and on
 * Kotlin/Native by this module's tests.
 */
internal fun Float.toDecimalDouble(): Double = toString().toDouble()
