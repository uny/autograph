package dev.ynagai.autograph.context

import android.view.View
import android.view.ViewGroup
import dev.ynagai.autograph.AutographInternalApi

/**
 * The [ScopeStack] frame of the surface that owns this view's subtree, or null if none has claimed
 * it. Shared between Autograph's own Android pipelines and not a supported public API.
 *
 * The native screen capture sets it on each surface's root view — a fragment's view, an Activity's
 * decor — to the frame it reserved for that surface. Two readers then agree on where an event
 * came from: the native tap capture walks up from the tapped view to the nearest owner and resolves
 * the tap from that frame ([ScopeStack.current] with an origin), and the Compose provider walks up
 * from its host view to find the surface its composition lives in and nests its own root frame
 * under it. Neither reader depends on the other's module; this property is the whole contract.
 *
 * Stored as a view tag keyed by a resource id this module declares, so it does not disturb
 * [View.getTag] or any other library's tags. Resource ids merge by name across the application,
 * though: an app or dependency declaring its own `autograph_scope_owner` id shares this key.
 *
 * Main thread only, like every `View` property.
 */
@AutographInternalApi
public var View.autographScopeOwner: ScopeHandle?
    get() = getTag(R.id.autograph_scope_owner) as? ScopeHandle
    set(value) = setTag(R.id.autograph_scope_owner, value)

/**
 * The origin for an event on this view: its own [autographScopeOwner], or the nearest ancestor's,
 * or null when no surface up the tree has claimed one (no native capture installed, or a window —
 * a `Popup`, a `Dialog` — whose view tree is not under any surface's root).
 *
 * Nearest wins, and that is what makes a surface nested inside another resolve to itself: a
 * fragment's view sits inside its host Activity's decor, and both are tagged.
 */
@AutographInternalApi
public fun View.autographScopeOrigin(): ScopeHandle? {
    var view: View? = this
    while (view != null) {
        view.autographScopeOwner?.let { return it }
        view = view.parent as? View
    }
    return null
}

/**
 * A callback a composition hangs on its own host view, run whenever a surface *above* that view
 * claims or releases its root ([notifyAutographScopeOwnerChanged]). The Compose provider uses it
 * to re-link its root frame under the surface that now hosts it; without it, a composition that
 * existed before the native capture was installed stays a root — and a root under no boundary
 * applies to every event, so an off-screen pager page would lend its screen to a tap on the page
 * beside it. Main thread only.
 */
@AutographInternalApi
public var View.autographScopeOwnerListener: (() -> Unit)?
    @Suppress("UNCHECKED_CAST")
    get() = getTag(R.id.autograph_scope_owner_listener) as? () -> Unit
    set(value) = setTag(R.id.autograph_scope_owner_listener, value)

/**
 * Runs every [autographScopeOwnerListener] in this view's subtree, this view included. Called by
 * the native screen capture after it claims or releases a surface's root view. The walk is the
 * same one the capture already makes over a new surface's view tree; a listener that throws is
 * swallowed so a lifecycle callback never crashes the host app.
 */
@AutographInternalApi
public fun View.notifyAutographScopeOwnerChanged() {
    autographScopeOwnerListener?.let { runCatching { it() } }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).notifyAutographScopeOwnerChanged()
    }
}
