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
 * it during touch dispatch.
 *
 * Stored as a view tag under a resource id private to this library, so it does not disturb
 * [View.getTag] or any other library's tags.
 */
public var View.isAutographIgnored: Boolean
    get() = getTag(R.id.autograph_ignore) == true
    set(value) {
        // Cleared rather than tagged `false`: an untagged view and a view tagged `false` mean the same
        // thing, and holding no reference is the cheaper of the two ways to say it.
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
