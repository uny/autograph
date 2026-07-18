package dev.ynagai.autograph.uikit

import platform.Foundation.NSHashTable
import platform.UIKit.UIView

/**
 * The set of views whose content belongs to a Compose capture pipeline, so the native pipeline can
 * stay off it.
 *
 * Both pipelines hit-test the *same* accessibility tree — that is the whole point of walking it —
 * so a tap inside a `ComposeUIViewController` is visible to both. Without a boundary the two would
 * each report it and every Compose tap would be counted twice. `autograph-compose` registers its
 * host view; [containsAny] lets the native side drop any tap whose hit path crosses one.
 *
 * **This is a static condition, not a race.** The alternative — "did the Compose pipeline already
 * report this tap?" — would make the outcome depend on which recognizer fired first. Asking instead
 * "does this tap land under Compose-owned content?" has one answer regardless of ordering, and it
 * holds even when the Compose side declines to report the tap at all.
 *
 * **That last part is the privacy-relevant one.** The invariant is *content under a Compose host
 * belongs to the Compose pipeline exclusively* — not *content the Compose pipeline reported*. A
 * hybrid app can run Compose with autocapture off and the native pipeline on; if registration were
 * conditional on the Compose autocapture flag, the native walk would descend into Compose's bridged
 * tree and capture elements the developer excluded with `Modifier.autographIgnore()`. Registration
 * must therefore happen whenever a Compose host exists, independent of that flag.
 *
 * **Process-global by design.** "This view hosts Compose" is a fact about the view, not about a
 * tracker, and the two sides have no other place to meet: the Compose host registers from inside a
 * composition and the native pipeline consults it from a gesture recognizer, with no shared owner
 * to thread an instance through.
 *
 * **Threading.** Main thread only, like every UIKit API involved.
 */
@AutographInternalApi
public object AutographComposeHosts {

    /**
     * Weak, so a dismissed Compose screen's host view is not kept alive — and, once deallocated,
     * silently leaves the set even if [unregister] never ran (a controller torn down without its
     * `DisposableEffect` firing, say).
     *
     * `NSHashTable.weakObjectsHashTable` compares by *pointer*, which is what makes this correct
     * across the Kotlin/Native interop boundary. Kotlin does not canonicalize Objective-C wrappers:
     * the same underlying view fetched twice — once handed over by Compose, once reached through
     * `subviews` during the walk — arrives as two distinct Kotlin objects. A Kotlin-side `Set` or a
     * `===` scan would call those different and the boundary would never match anything, silently
     * double-counting every Compose tap. Routing the comparison through the hash table's own
     * pointer personality sidesteps wrapper identity entirely.
     */
    private val hosts = NSHashTable.weakObjectsHashTable()

    /** Marks [view] and everything under it as owned by the Compose pipeline. */
    public fun register(view: UIView) {
        hosts.addObject(view)
    }

    /** Reverses [register]. Safe to call for a view that was never registered. */
    public fun unregister(view: UIView) {
        hosts.removeObject(view)
    }

    /**
     * Whether [path] — a hit path from [deepestAccessibilityHitPath] — crosses a registered host,
     * i.e. whether the tap that produced it landed on Compose-owned content.
     *
     * Asks about the whole path rather than its leaf because the leaf is typically a bridged
     * accessibility element, not the host view itself; the host appears as one of its ancestors.
     */
    public fun containsAny(path: List<Any>): Boolean = path.any { hosts.containsObject(it) }
}
