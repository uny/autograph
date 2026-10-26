package dev.ynagai.autograph.android

import android.view.View

/**
 * Declares that the fragments in this view's subtree sit **beside** their Activity's content rather
 * than replacing it — a mini-player, a banner, a widget slot — so the Activity stays a screen of its
 * own while they are attached ([#281](https://github.com/uny/autograph/issues/281)).
 *
 * [installAutographNativeScreenCapture] decides whether an Activity is a screen or a shell for its
 * fragments from its structure, and the structure cannot tell the two shapes apart: a Compose
 * mini-player added into a container of a native Activity looks exactly like the Compose content
 * fragment of a fragment-based app. It therefore treats every view-bearing fragment as content, and
 * an Activity with a mini-player up at its first resume, or at the first resume after a stop, becomes
 * a shell: its own taps carry no screen and it reports no `Screen Viewed` for itself. Only the app
 * knows which shape it built, so it says so here.
 *
 * Set it on the container the fragment is added into — which covers every fragment added there
 * later, the way a mini-player slot is reused — or on the fragment's own root view (in
 * `onCreateView` / `onViewCreated`). Either is in place before the host's next resume for a fragment
 * added in `onCreate`. While a fragment's view, or one of its ancestors, is marked:
 *
 * - it does not make its Activity a shell, and a Compose view inside a marked subtree does not make
 *   the Activity (or a fragment containing the mark) a Compose host;
 * - it does not take its Activity's return: coming back to the Activity reports the Activity, not
 *   the marked fragment, even when that fragment is a screen of its own.
 *
 * The marked fragment itself is unaffected. A capturable one still reports its own `Screen Viewed`
 * and names its own taps; an excluded one (it hosts Compose) still masks the taps inside it, so they
 * carry no screen rather than the Activity's. A fragment that really is the Activity's content must
 * not be marked: the Activity would then report its own name over it — wrong, not absent.
 *
 * Without the mark nothing changes: an Activity with a mini-player still becomes a shell, as the
 * install kdoc documents.
 *
 * ## A settable property, not a one-way mark
 *
 * Like [isAutographIgnored], and for the same reason: containers are reused, and a container that
 * once held a widget can later hold content. Write `false` to take the mark back. The capture reads it
 * when it settles what the Activity is — at the first resume, and at the first resume after each
 * stop — so a change takes effect at the next of those, not at once.
 *
 * Main thread only, like every other `View` property. Stored as a view tag keyed by a resource id
 * this library declares, so it does not disturb [View.getTag] or any other library's tags. Resource
 * ids merge by name across the application, though: an app or dependency declaring its own
 * `autograph_embedded` id shares this key.
 */
public var View.isAutographEmbedded: Boolean
    get() = getTag(R.id.autograph_embedded) == true
    set(value) {
        // `null` rather than `false`, as in isAutographIgnored: untagged and un-marked are one state.
        setTag(R.id.autograph_embedded, if (value) true else null)
    }

/** True when [view] or any of its ancestors carries [isAutographEmbedded]. */
internal fun isInEmbeddedSubtree(view: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current.isAutographEmbedded) return true
        current = current.parent as? View
    }
    return false
}
