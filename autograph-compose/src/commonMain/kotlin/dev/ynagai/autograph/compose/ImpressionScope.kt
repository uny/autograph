package dev.ynagai.autograph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Marks the content as one unit of impression de-duplication for the keyed
 * [trackImpression][dev.ynagai.autograph.compose.trackImpression] overload: inside it, each key
 * reports at most once for as long as this scope stays in the composition (#243).
 *
 * A [TrackedScreen] already is such a unit — one per visit — so this is for content that has no
 * [TrackedScreen] around it, most often a destination tracked only by
 * [NavController.TrackScreenViews][TrackScreenViews]. Wrap the list, not its items: an item's own
 * composition is disposed when it scrolls out of view, and the record of what it reported with it.
 *
 * The innermost unit wins. Inside a [TrackedScreen], this scope starts over whenever the screen's
 * visit does, so it never remembers an item across two visits the warehouse would see as distinct.
 */
@Composable
public fun ImpressionScope(content: @Composable () -> Unit) {
    val outer = LocalImpressionRegistry.current
    val registry = remember(outer) { ImpressionRegistry(parent = outer) }
    CompositionLocalProvider(LocalImpressionRegistry provides registry, content = content)
}

/**
 * What the keyed `trackImpression` overload has reported within one unit of de-duplication — a
 * [TrackedScreen]'s visit or an [ImpressionScope] — keyed by [ImpressionKey].
 *
 * [generation] advances when the unit starts over: a [TrackedScreen] renamed under the same
 * composition, or, for an [ImpressionScope], whatever unit encloses it ([parent]). It moves at once,
 * inside the visit's `begin`, so a dwell that began under the old generation can tell before it
 * reports. It is snapshot state on purpose: an element reads it while composing, so a new generation
 * recomposes every keyed element under this registry and restarts its dwell — an element that never
 * leaves the viewport would otherwise never report under the new generation.
 *
 * Main-thread only, like the composition and the visibility callbacks that use it.
 */
internal class ImpressionRegistry(private val parent: ImpressionRegistry? = null) {
    private var ownGeneration by mutableIntStateOf(0)

    /** Only a [TrackedScreen]'s registry starts over by itself, so a sum moves whenever either does. */
    val generation: Int get() = ownGeneration + (parent?.generation ?: 0)

    private val reported = mutableSetOf<ImpressionKey>()
    private var reportedIn = generation

    operator fun contains(impression: ImpressionKey): Boolean = impression in current()

    fun record(impression: ImpressionKey) {
        current() += impression
    }

    fun startOver() {
        ownGeneration++
    }

    /** [reported], emptied first if the unit started over since it was last touched. */
    private fun current(): MutableSet<ImpressionKey> {
        val now = generation
        if (now != reportedIn) {
            reported.clear()
            reportedIn = now
        }
        return reported
    }
}

/**
 * One impression definition applied to one item: two lists, or two thresholds, on the same item do
 * not collide. [target] is the effective one — what reaches the event — so the same target given as
 * the argument, as `properties["target"]` or by an enclosing [AutographScope] is the same impression.
 */
internal data class ImpressionKey(
    val name: String,
    val minDurationMs: Long,
    val minFractionVisible: Float,
    val target: String?,
    val key: Any,
)

/**
 * The de-duplication unit keyed impressions report into: the innermost [TrackedScreen] visit or
 * [ImpressionScope], or null outside both.
 */
internal val LocalImpressionRegistry: ProvidableCompositionLocal<ImpressionRegistry?> =
    staticCompositionLocalOf { null }
