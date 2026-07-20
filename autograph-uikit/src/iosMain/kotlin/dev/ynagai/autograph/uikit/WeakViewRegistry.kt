package dev.ynagai.autograph.uikit

import platform.Foundation.NSMapTable
import platform.Foundation.NSMapTableObjectPointerPersonality
import platform.Foundation.NSMapTableStrongMemory
import platform.Foundation.NSMapTableWeakMemory
import platform.Foundation.NSNumber
import platform.Foundation.numberWithInt
import platform.UIKit.UIView

/**
 * A ref-counted, weak set of `UIView`s that two native-capture boundaries build on: the Compose-host
 * carve-out ([AutographComposeHosts]) and the developer opt-out ([AutographIgnoredViews]). Each owns
 * its **own** instance — they are separate questions ("is this Compose-owned?" vs "did the developer
 * exclude this?") and must never share one table, or an opt-out could disarm a Compose boundary or
 * vice versa. What they share is only the tricky Objective-C bookkeeping, kept here in one place.
 *
 * **Counted, not a plain set.** One view can be claimed more than once (nested providers, two
 * navigation destinations wrapping the same host while a transition overlaps them). Under set
 * semantics the first release would drop the entry and leave a still-live claimant unprotected — a
 * silent *fail-open*, exactly the leak these boundaries exist to prevent.
 *
 * **Weak keys**, so a dismissed view is not kept alive and, once deallocated, silently leaves the
 * table even if [unregister] never ran (a view torn down without its cleanup firing). The stranded
 * count costs nothing: it dies with its key.
 *
 * **Pointer personality**, which is what makes this correct across the Kotlin/Native interop boundary.
 * Kotlin does not canonicalize Objective-C wrappers: the same underlying view fetched twice — once
 * handed over at registration, once reached through `subviews` during the walk — arrives as two
 * distinct Kotlin objects. A Kotlin-side `Map` or a `===` scan would call those different and the
 * boundary would never match anything. [NSMapTableObjectPointerPersonality] is named explicitly rather
 * than taking `weakToStrongObjectsMapTable()` (weak keys at the *default* `isEqual:`/`hash`
 * personality): a host app may subclass `UIView` with content-based equality, and then two such views
 * would be indistinguishable to the table — unregistering one would silently disarm another.
 *
 * **Threading.** Main thread only, like every UIKit API involved.
 */
internal class WeakViewRegistry {

    private val views = NSMapTable.mapTableWithKeyOptions(
        keyOptions = NSMapTableWeakMemory or NSMapTableObjectPointerPersonality,
        valueOptions = NSMapTableStrongMemory,
    )

    /** Adds one claim on [view]. Registrations nest: the view stays in the set until the last release. */
    fun register(view: UIView) {
        views.setObject(NSNumber.numberWithInt(countFor(view) + 1), forKey = view)
    }

    /**
     * Releases one [register]. Safe for a view never registered, and — deliberately — for one already
     * fully released: an unbalanced extra call must not push the count negative and re-arm nothing.
     */
    fun unregister(view: UIView) {
        val remaining = countFor(view) - 1
        if (remaining <= 0) {
            views.removeObjectForKey(view)
        } else {
            views.setObject(NSNumber.numberWithInt(remaining), forKey = view)
        }
    }

    /** Whether [element] (a `UIView`, or any object) is itself a registered member. */
    fun contains(element: Any): Boolean = views.objectForKey(element) != null

    /**
     * Whether [path] — a hit path from [deepestAccessibilityHitPath] — crosses a registered member.
     * Asks about the whole path rather than its leaf: the leaf is typically a bridged accessibility
     * element, while the registered view appears as one of its ancestors.
     */
    fun containsAny(path: List<Any>): Boolean = path.any { contains(it) }

    /**
     * Whether [root] or anything under it is registered. Walks the subtree itself (depth-first over
     * `subviews`) and stops at the first hit, so no intermediate path list is allocated for what is
     * usually a negative answer — for callers that hold a view subtree rather than a built hit path.
     */
    fun anyInSubtree(root: UIView): Boolean {
        val stack = ArrayDeque<UIView>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (contains(view)) return true
            view.subviews.forEach { subview -> (subview as? UIView)?.let(stack::addLast) }
        }
        return false
    }

    private fun countFor(element: Any): Int = (views.objectForKey(element) as? NSNumber)?.intValue ?: 0
}
