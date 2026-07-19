package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.context.ScreenHistory
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
    val history = currentScreenHistory
    LaunchedEffect(name) {
        val previous = history.record(name)
        tracker.screen(name, withPreviousScreen(properties, previous))
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
    // Mirror the screen into the ambient stack so autocaptured taps on this screen carry it, the
    // same way [LocalScreenContext] carries it to explicit trackClick/trackImpression. The observer
    // sits above this composable and can't read the CompositionLocal.
    MirrorAmbientFrame(LocalScopeStack.current, screen = name)
    CompositionLocalProvider(LocalScreenContext provides ScreenContext(name), content = content)
}

/**
 * The screen history this composition writes to: the one carried by the ambient [ScopeStack].
 *
 * Reading it off the stack rather than providing it separately is what keeps `previous_screen`
 * continuous across a Compose↔native transition. A hybrid app passes one [ScopeStack] to
 * `AutographProvider` and to the native pipeline; both then share this history, and a native screen
 * view becomes the `previous_screen` of the next Compose one and vice versa.
 *
 * Its per-provider scoping is unchanged: `AutographProvider` keys a caller-less stack on the tracker,
 * so history still resets when the tracker is replaced (e.g. after logout). A caller supplying its own
 * stack takes on that lifetime obligation, exactly as it already does for the stack's frames.
 */
internal val currentScreenHistory: ScreenHistory
    @Composable get() = LocalScopeStack.current.screenHistory
