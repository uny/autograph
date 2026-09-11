package dev.ynagai.autograph.context

import android.view.View
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
