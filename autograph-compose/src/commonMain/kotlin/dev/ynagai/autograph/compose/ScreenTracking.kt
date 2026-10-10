@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.context.ScreenHistory
import dev.ynagai.autograph.context.startScreenVisit
import dev.ynagai.autograph.withEventMetadata
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
 *
 * It also declares [name] as the screen for as long as it is in the composition, through a frame in
 * the ambient [ScopeStack] — the same channel [TrackedScreen] uses, and what an autocaptured tap
 * reads.
 *
 * **That declaration is ambient, not scoped to a subtree.** This composable takes no `content`, so
 * the screen it names applies to every autocaptured tap in the composition while it is present, not
 * only to taps near it — and, because a frame naming a screen owns its section, it also clears the
 * section of a [TrackedScreen] it sits inside. Two consequences worth stating plainly:
 * - Nested inside a [TrackedScreen], an autocaptured tap says [name] while an explicit
 *   `trackClick` / `trackImpression` says the enclosing screen: this deliberately does not provide
 *   [LocalScreenContext], which is the whole difference between the two composables.
 * - Composed *after* a [TrackedScreen] as a sibling — a sheet, a dialog, a second pane — it wins for
 *   every autocaptured tap in the composition, including taps on the content behind it.
 *
 * Use [TrackedScreen] when the screen should be scoped to what it wraps, and when the autocaptured
 * and instrumented paths should agree — which is nearly always. Reach for this one for a surface
 * that has no content of its own to wrap.
 */
@Composable
public fun TrackScreenView(
    name: String,
    properties: JsonObject = EmptyJsonObject,
) {
    MirrorAmbientFrame(LocalScopeStack.current, screen = name) {
        EmitScreenView(name, properties)
    }
}

/**
 * The visit a [TrackedScreen] is in (#242), for the explicit `trackClick` / `trackImpression` inside
 * it. They attribute lexically, like [LocalScreenContext], not through the ambient stack, so the
 * screen hands them its visit directly. A holder rather than a value because it is read when the
 * event fires, after the effect that starts the visit, not when the element composes.
 */
internal class ScreenVisit {
    private var screen: String? = null
    private var id: String? = null

    /**
     * What the keyed `trackImpression` reported in this visit (#243). Bound to the visit, not to
     * [id]: the id can be absent — before the first [begin], or when the generator fails — and
     * de-duplication must hold all the same.
     */
    val impressions: ImpressionRegistry = ImpressionRegistry()

    fun begin(screen: String, id: String?) {
        // Only a rename starts the registry over. The first begin follows composition, so an item
        // reported in between belongs to this same visit and must stay reported.
        if (this.screen != null && this.screen != screen) impressions.startOver()
        this.screen = screen
        this.id = id
    }

    /**
     * The visit's id if it is a visit of [screen], else null: between a rename and the effect that
     * starts the renamed screen's visit, the old id must not reach an event under the new name.
     */
    fun idFor(screen: String?): String? = id?.takeIf { screen == this.screen }
}

/** The [ScreenVisit] of the enclosing [TrackedScreen], or null outside one. */
internal val LocalScreenVisit: ProvidableCompositionLocal<ScreenVisit?> = staticCompositionLocalOf { null }

/**
 * The reporting half of [TrackScreenView]: records history and emits, declaring nothing. Split out
 * so [TrackedScreen] — which pushes its own frame, with a section — does not push a second one.
 *
 * Each emit starts a new visit of the frame it runs in — [LocalScopeParent], the frame the caller's
 * [MirrorAmbientFrame] pushed — and the `Screen Viewed` carries its id (#242), handed to [visit] too.
 * The visit is started for [name] explicitly: whether this effect runs before or after the frame's
 * `SideEffect` writes [name] into it is the dispatcher's choice, and naming it here keeps the
 * revision that follows from reading as a rename.
 *
 * Keyed on [name] only, as before: a tracker or stack swapped under an unchanged screen does not
 * re-emit, and the frame the new stack gets therefore carries no visit until the next screen view.
 * No id rather than the old stack's.
 */
@Composable
private fun EmitScreenView(
    name: String,
    properties: JsonObject,
    visit: ScreenVisit? = null,
) {
    val tracker = LocalTracker.current
    val stack = LocalScopeStack.current
    val history = currentScreenHistory
    val frame = LocalScopeParent.current
    LaunchedEffect(name) {
        val previous = history.record(name)
        val id = frame?.get(0)?.let { stack.startScreenVisit(it, screen = name) }
        visit?.begin(name, id)
        val withPrevious = withPreviousScreen(properties, previous)
        tracker.screen(name, if (id != null) withPrevious.withEventMetadata(kind = null, screenViewId = id) else withPrevious)
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
 *
 * [section] is a screen-wide sub-label attached to every event fired inside [content] under the
 * reserved `section` key, on both the explicit (`trackClick` / `trackImpression`) and the autocapture
 * path — a variant or sub-mode of the whole screen, such as the selected tab of a home screen
 * (`"For You"` vs `"Following"`) or an A/B layout. It applies to the entire screen, not to a region
 * within it: because it is a parameter of the screen, a tap anywhere in [content] carries it, so it
 * cannot distinguish a header tap from a body tap. (Region-level attribution would need a separate
 * marker around the sub-tree; this composable does not provide one.)
 *
 * A section is context for the events fired under it, not a navigation destination: it is
 * deliberately **not** part of the `Screen Viewed` event this composable fires, and changing only the
 * section does not re-fire it. A nested [TrackedScreen] that names a different screen replaces both
 * the screen and the section — an inner screen does not inherit an outer one's section.
 *
 * Note the parameter order: [section] follows [properties] so that existing positional calls of the
 * form `TrackedScreen(name, properties) { ... }` keep compiling.
 */
@Composable
public fun TrackedScreen(
    name: String,
    properties: JsonObject = EmptyJsonObject,
    section: String? = null,
    content: @Composable () -> Unit,
) {
    // Mirror screen + section into the ambient stack so autocaptured taps on this screen carry them,
    // the same way [LocalScreenContext] carries them to explicit trackClick/trackImpression. The
    // observer sits above this composable and can't read the CompositionLocal. Wrapping the content
    // also makes this screen frame the lineage parent of any scope nested inside it, so scopes under
    // one screen stay on a single chain (and merge) rather than reading as siblings.
    // Remembered per stack: a stack swapped under this screen gets a fresh, empty visit rather than
    // the old stack's id (see EmitScreenView).
    val stack = LocalScopeStack.current
    val visit = remember(stack) { ScreenVisit() }
    MirrorAmbientFrame(stack, screen = name, section = section) {
        EmitScreenView(name, properties, visit)
        CompositionLocalProvider(
            LocalScreenContext provides ScreenContext(name, section),
            LocalScreenVisit provides visit,
            LocalImpressionRegistry provides visit.impressions,
            content = content,
        )
    }
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
