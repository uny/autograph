package dev.ynagai.autograph.android

import android.view.View

/**
 * Marks this view and everything under it as excluded from native tap autocapture — the View
 * counterpart of Compose's `Modifier.autographIgnore()`.
 *
 * A tap that resolves to this view, or to any view inside it, reports nothing. Marking a container
 * is therefore enough to exclude a whole screen region; there is no need to find and mark each
 * clickable child. Nothing else changes: the touch is dispatched exactly as before, and any explicit
 * `Tracker.track` the app makes from its own click listener is unaffected — this only stops
 * [installAutographNativeTapCapture] from reporting taps here on its own.
 *
 * ## Two boundaries the word "under" does not reach, and both matter
 *
 * **A mark below the view a tap resolves to is never consulted** — see [isExcludedFromAutocapture]
 * for why it cannot be. Usually harmless (a mark on a non-clickable label inside a clickable row does
 * nothing, because the tap is attributed to the row), but for `AbsListView` it is not: a `ListView`
 * presses itself as well as the row, so a row tap resolves to the *list*, and a row marked in
 * `getView()` is still reported under the list's id. Mark the `ListView` itself. `RecyclerView` rows
 * are ordinary pressed views and are unaffected.
 *
 * **This does not cross into Compose.** A `ComposeView` inside a marked subtree is served by
 * `autograph-compose`, which resolves taps through the Compose semantics tree and never reads a View
 * tag, so its taps are still autocaptured; and an `AndroidView` inside a `Modifier.autographIgnore()`
 * subtree is an ordinary pressed View here. A region spanning both pipelines must be marked on both.
 *
 * ## A settable property, not a one-way mark, because views are recycled
 *
 * `RecyclerView` hands the same `View` instance to a different item, so a marker that could only be
 * applied would leak the exclusion onto whatever row inherited the view. A `ViewHolder` binding a
 * mixed list must be able to write `false` as readily as `true`:
 *
 * ```kotlin
 * override fun onBindViewHolder(holder: RowHolder, position: Int) {
 *     val row = items[position]
 *     holder.itemView.isAutographIgnored = row.isSensitive
 * }
 * ```
 *
 * ## Threading
 *
 * Main thread only, like every other `View` property, and for the same reason: the tap capture reads
 * it during touch dispatch. Specifically it is read *after* the app's own handling of the touch-up,
 * because that dispatch is what sets the pressed state the target is derived from — so a view that
 * clears its own mark while handling `ACTION_UP` is read as unmarked for that very tap. Set the mark
 * when the content is built or bound, not from inside a touch handler.
 *
 * Stored as a view tag keyed by a resource id this library declares, so it does not disturb
 * [View.getTag] or any other library's tags. Resource ids merge by name across the application,
 * though: an app or dependency declaring its own `autograph_ignore` id shares this key.
 */
public var View.isAutographIgnored: Boolean
    get() = getTag(R.id.autograph_ignore) == true
    set(value) {
        // Tagged `null` rather than `false` so the getter's `== true` is the whole rule, and an
        // untagged view and an un-marked one are literally the same state rather than two states that
        // have to agree. (`setTag` stores the null; it does not drop the key.)
        setTag(R.id.autograph_ignore, if (value) true else null)
    }

/**
 * True when [view] or any of its ancestors carries the marker.
 *
 * Walking up rather than down is what makes the mark subtree-wide at no per-tap cost proportional to
 * the subtree: the reported target is always a single view (the root of the pressed subtree), so the
 * only marks that can suppress it are on that view or above it. A mark on a non-clickable descendant
 * is not consulted, and cannot be — a tap on such a descendant is attributed to the clickable
 * ancestor that handled it, and where inside that ancestor the finger landed is deliberately not part
 * of the answer.
 */
internal fun isExcludedFromAutocapture(view: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current.isAutographIgnored) return true
        current = current.parent as? View
    }
    return false
}
