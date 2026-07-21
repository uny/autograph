package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.context.AutographInternalApi
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
 * The ref-counted, weak, pointer-personality bookkeeping lives in [WeakViewRegistry]; this holds its
 * own instance, separate from [AutographIgnoredViews]'s, deliberately (see there). **Main thread only.**
 */
@AutographInternalApi
public object AutographComposeHosts {

    private val hosts = WeakViewRegistry()

    /**
     * Marks [view] and everything under it as owned by the Compose pipeline, until a matching
     * [unregister]. Registrations nest: callers pair their own, and the view stays owned until the
     * last one is released.
     */
    public fun register(view: UIView): Unit = hosts.register(view)

    /**
     * Releases one [register]. Safe to call for a view that was never registered, and — deliberately —
     * for one already fully released: an unbalanced extra call must not push the count negative and
     * re-arm nothing.
     */
    public fun unregister(view: UIView): Unit = hosts.unregister(view)

    /**
     * Whether [path] — a hit path from [deepestAccessibilityHitPath] — crosses a registered host,
     * i.e. whether the tap that produced it landed on Compose-owned content.
     */
    public fun containsAny(path: List<Any>): Boolean = hosts.containsAny(path)

    /**
     * Whether [root] or anything under it is a registered host — i.e. whether a controller whose
     * `view` is [root] renders Compose content and should be left to the Compose pipeline.
     */
    internal fun hostInSubtree(root: UIView): Boolean = hosts.anyInSubtree(root)
}
