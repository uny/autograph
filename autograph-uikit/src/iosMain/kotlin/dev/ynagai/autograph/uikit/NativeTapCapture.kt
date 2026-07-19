package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.DEFAULT_AUTOCAPTURE_EVENT_NAME
import dev.ynagai.autograph.context.ScopeStack
import platform.Foundation.NSHashTable
import platform.Foundation.NSHashTableObjectPointerPersonality
import platform.Foundation.NSHashTableWeakMemory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowDidBecomeVisibleNotification
import platform.darwin.NSObject

/**
 * Starts reporting taps on native (UIKit/SwiftUI) content as [eventName], resolved through the
 * accessibility tree by [resolveNativeTapTarget].
 *
 * Opt-in, exactly as Compose autocapture is: observing every tap is a different privacy posture than
 * instrumenting individual elements, so nothing happens until an app calls this.
 *
 * Pass the **same** [scopeStack] the app gives `AutographProvider` if it also renders Compose.
 * Sharing one stack is what lets a native tap carry the screen and scope a Compose screen pushed, and
 * vice versa; two stacks leave both sides attributing against half-empty context.
 *
 * **Taps on Compose content are not reported here.** `autograph-compose` registers its host views and
 * this pipeline drops any tap whose hit path crosses one, so a hybrid app reports each tap exactly
 * once, from the pipeline that owns the content. See [AutographComposeHosts].
 *
 * **A native tap carries no `screen` of its own yet.** The Compose path falls back to its
 * `ScreenHistory` when no ambient frame supplies one; there is no native equivalent until #65 adds
 * screen-transition capture, so a native tap's `screen` comes from the shared [scopeStack] or not at
 * all.
 *
 * **Threading.** Main thread only, to install and to uninstall — it touches UIKit throughout.
 *
 * Keep the returned handle: it is the only way to [AutographNativeTapCapture.uninstall], and the
 * capture holds [tracker] and [scopeStack] strongly until then.
 */
@AutographInternalApi
public fun installAutographNativeTapCapture(
    tracker: Tracker,
    scopeStack: ScopeStack,
    eventName: String = DEFAULT_AUTOCAPTURE_EVENT_NAME,
): AutographNativeTapCapture = AutographNativeTapCapture(tracker, scopeStack, eventName).also { it.install() }

/**
 * A running native tap capture. Created by [installAutographNativeTapCapture].
 *
 * Holds [tracker] and [scopeStack] strongly, which is why [uninstall] exists rather than relying on
 * the handle being dropped: an app that replaces its tracker on logout must uninstall, or the
 * recognizers keep reporting into the retired one.
 */
@AutographInternalApi
public class AutographNativeTapCapture internal constructor(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
    private val eventName: String,
) {

    private val observer = NativeTapObserver(::report)

    /**
     * The windows already carrying a recognizer, so a window is never instrumented twice.
     *
     * [UIWindowDidBecomeVisibleNotification] fires every time a window becomes visible, not only the
     * first time, so without this a window that is shown, hidden and shown again would accumulate
     * recognizers and report its taps once per accumulation.
     *
     * Weak with pointer personality for the same reasons as [AutographComposeHosts]: a closed window
     * must not be kept alive by this set, and comparison has to be by underlying object rather than
     * by Kotlin wrapper identity or a host-overridable `isEqual:`.
     */
    private val instrumented = NSHashTable.hashTableWithOptions(
        NSHashTableWeakMemory or NSHashTableObjectPointerPersonality,
    )

    private var windowObserver: NSObject? = null

    internal fun install() {
        // Both halves are required and neither is a fallback for the other: the scan catches windows
        // that already exist (the notification fired for those long ago), the observer catches the
        // ones created later. An on-device probe that only scanned at launch saw nothing at all from
        // a window that appeared afterwards.
        UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().forEach(::attach)
        windowObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIWindowDidBecomeVisibleNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            (notification?.`object` as? UIWindow)?.let(::attach)
        } as? NSObject
    }

    /** Detaches every recognizer and stops listening for new windows. Safe to call more than once. */
    public fun uninstall() {
        windowObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        windowObserver = null
        instrumented.allObjects.filterIsInstance<UIWindow>().forEach { window ->
            window.gestureRecognizers
                ?.filterIsInstance<UITapGestureRecognizer>()
                // Only this capture's own recognizers: an app is free to have its own tap
                // recognizers on the same window, and uninstalling must not disarm those.
                //
                // `==` rather than `===`, though `===` is measured to work here: the delegate is a
                // Kotlin-created object, and those do come back as the same wrapper across an interop
                // crossing. That is the narrow exception, not the rule — Kotlin/Native does not
                // canonicalize Objective-C wrappers in general, and elsewhere in this module
                // (AccessibilityTree.kt's cycle guard) `===` was silently inert for exactly that
                // reason. Depending on which side of the boundary an object originated is too fine a
                // distinction to leave load-bearing, and the failure mode is silent: recognizers left
                // attached, still reporting into the tracker `uninstall` exists to release. `==`
                // routes to `isEqual:`, pointer equality on the underlying object, which is what is
                // wanted either way.
                ?.filter { it.delegate == observer }
                ?.forEach(window::removeGestureRecognizer)
        }
        instrumented.removeAllObjects()
    }

    // Internal rather than private only so tests can drive the attach/uninstall lifecycle against a
    // hand-made window: [install]'s other half reads UIApplication.sharedApplication.windows, which a
    // unit test cannot populate.
    internal fun attach(window: UIWindow) {
        if (!window.isCapturableWindow()) return
        if (instrumented.containsObject(window)) return
        window.addGestureRecognizer(observer.makeRecognizer())
        instrumented.addObject(window)
    }

    /**
     * Resolves the tap and, if it names something, reports it.
     *
     * Mirrors `autograph-compose`'s `reportTapIfResolvable`, including swallowing anything thrown: a
     * single bad resolve or a throwing tracker must not leave the capture poisoned for the rest of
     * the app's life, and this runs on every tap the user makes.
     */
    private fun report(positionInWindowPoints: AxPoint, window: UIView) {
        try {
            // `scale` is mainScreen's, and must be — the accessibility frames this is compared
            // against are converted out of mainScreen's coordinate space. See
            // accessibilityBoundsInWindowPx, whose precondition this inherits.
            val scale = UIScreen.mainScreen.scale.toFloat()
            val positionInWindowPx = AxPoint(positionInWindowPoints.x * scale, positionInWindowPoints.y * scale)
            val target = resolveNativeTapTarget(window, positionInWindowPx, scale) ?: return
            tracker.track(eventName, scopeStack.current().enrich(EmptyJsonObject), target)
        } catch (e: Exception) {
            // Swallowed: see kdoc above.
        }
    }
}
