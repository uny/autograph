package dev.ynagai.autograph.uikit

import platform.UIKit.UIView

/**
 * The set of views a developer has excluded from native tap autocapture. Consulted by
 * [resolveNativeTapTarget]; populated through the public [registerAutographIgnoredView]. A separate
 * [WeakViewRegistry] instance from [AutographComposeHosts] on purpose — "the developer opted this out"
 * and "this is Compose-owned" are different facts, and one must never disarm the other.
 *
 * **Main thread only**, like the tap pipeline it feeds.
 */
internal object AutographIgnoredViews {

    private val views = WeakViewRegistry()

    fun register(view: UIView): Unit = views.register(view)

    fun unregister(view: UIView): Unit = views.unregister(view)

    /** Whether a tap's hit [path] crosses an excluded view — i.e. landed inside an opted-out subtree. */
    fun containsAny(path: List<Any>): Boolean = views.containsAny(path)
}

/**
 * Excludes [view] and everything under it from native (UIKit/SwiftUI) tap autocapture — the
 * counterpart of Compose's `Modifier.autographIgnore()`. This is a **privacy** control: a tap whose
 * hit path crosses [view] is not reported at all, so a screen can keep something off the analytics
 * stream without stripping its `accessibilityIdentifier` (which UI testing and assistive tooling rely
 * on).
 *
 * Only *ambient* autocapture is suppressed. Explicit instrumentation is unaffected — a `trackClick`
 * you call yourself inside the subtree still fires.
 *
 * Registrations **nest and are ref-counted**: registering the same view twice keeps it excluded until
 * *both* registrations are released, so overlapping owners can't disarm each other. Keep the returned
 * [AutographIgnoredViewRegistration] and call [AutographIgnoredViewRegistration.unregister] to stop
 * excluding the view (e.g. when it leaves the screen). Dropping the registration without unregistering
 * does **not** end the exclusion: collecting it only releases its strong reference to the view, so the
 * view stays excluded until either [AutographIgnoredViewRegistration.unregister] runs or the view
 * itself deallocates. Hold the registration and unregister it explicitly for exactly as long as the
 * exclusion should last.
 *
 * A genuinely supported API (unlike the `@AutographInternalApi` internals): reachable from UIKit
 * through the umbrella `Autograph` framework without importing the SwiftUI `AutographUI` product.
 *
 * **Main thread only.**
 */
public fun registerAutographIgnoredView(view: UIView): AutographIgnoredViewRegistration {
    AutographIgnoredViews.register(view)
    return AutographIgnoredViewRegistration(view)
}

/**
 * A live exclusion from [registerAutographIgnoredView]. Call [unregister] to release it. Holds the
 * view until then, so the exclusion outlives the caller's other references to it if need be.
 */
public class AutographIgnoredViewRegistration internal constructor(view: UIView) {

    // Nulled on the first [unregister] so a second call — or one after the caller already released it —
    // is a no-op rather than an unbalanced decrement that could re-arm capture on another owner's view.
    private var view: UIView? = view

    /** Stops excluding the view. Idempotent — safe to call more than once. Main thread only. */
    public fun unregister() {
        val excluded = view ?: return
        view = null
        AutographIgnoredViews.unregister(excluded)
    }
}
