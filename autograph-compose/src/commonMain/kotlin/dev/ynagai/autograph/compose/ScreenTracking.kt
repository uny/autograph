package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ynagai.autograph.EmptyJsonObject
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The screen (and optional section) an element belongs to. Provided by [TrackedScreen]
 * so that nested instrumentation — e.g. impression and click tracking — can attach
 * screen context automatically.
 */
public data class ScreenContext(
    val screen: String,
    val section: String? = null,
)

/** The ambient [ScreenContext], or null outside of a [TrackedScreen]. */
public val LocalScreenContext: androidx.compose.runtime.ProvidableCompositionLocal<ScreenContext?> =
    compositionLocalOf { null }

/**
 * Records a `Screen Viewed` event once when [name] enters the composition
 * (and again whenever [name] changes). A `previous_screen` property is attached
 * automatically when a previous screen is known.
 */
@Composable
public fun TrackScreenView(
    name: String,
    properties: JsonObject = EmptyJsonObject,
) {
    val tracker = LocalTracker.current
    val history = LocalScreenHistory.current
    LaunchedEffect(name) {
        tracker.screen(name, withPreviousScreen(properties, history.lastScreen))
        history.lastScreen = name
    }
}

/**
 * Returns [properties] with a `previous_screen` entry for [previousScreen], unless [previousScreen]
 * is null or the caller already provided a `previous_screen`. Shared by [TrackScreenView] and
 * [NavController.TrackScreenViews].
 */
internal fun withPreviousScreen(properties: JsonObject, previousScreen: String?): JsonObject =
    if (previousScreen != null && !properties.containsKey("previous_screen")) {
        JsonObject(properties + ("previous_screen" to JsonPrimitive(previousScreen)))
    } else {
        properties
    }

/**
 * [TrackScreenView] plus ambient [ScreenContext] for the content — nested
 * instrumentation inside [content] is attributed to this screen automatically.
 */
@Composable
public fun TrackedScreen(
    name: String,
    properties: JsonObject = EmptyJsonObject,
    content: @Composable () -> Unit,
) {
    TrackScreenView(name, properties)
    CompositionLocalProvider(LocalScreenContext provides ScreenContext(name), content = content)
}

/** The most recent screen name, used to enrich the next event with `previous_screen`. */
internal class ScreenHistory {
    // Written from a LaunchedEffect coroutine and from the NavController listener callback; @Volatile
    // makes writes visible across those contexts even if they are ever not on the same thread.
    @Volatile
    var lastScreen: String? = null
}

private val fallbackScreenHistory = ScreenHistory()

/**
 * Per-[AutographProvider] screen history. Scoping it to the provider (keyed on the tracker)
 * keeps `previous_screen` from leaking between independent trackers and resets it when the
 * tracker is replaced (e.g. after logout). Outside a provider — where events are dropped
 * anyway — reads fall back to a shared instance.
 */
internal val LocalScreenHistory: ProvidableCompositionLocal<ScreenHistory> =
    staticCompositionLocalOf { fallbackScreenHistory }
