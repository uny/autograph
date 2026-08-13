package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.DEFAULT_AUTOCAPTURE_EVENT_NAME
import dev.ynagai.autograph.context.ScopeStack
import platform.Foundation.NSHashTable
import platform.Foundation.NSHashTableObjectPointerPersonality
import platform.Foundation.NSHashTableWeakMemory
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowDidBecomeVisibleNotification
import platform.darwin.NSObjectProtocol

/**
 * Starts reporting taps on native **UIKit** content as [eventName], resolved by
 * [resolveNativeTapTargetByHitTest] — one resolver, which reads no accessibility state at any point.
 *
 * Opt-in, exactly as Compose autocapture is: observing every tap is a different privacy posture than
 * instrumenting individual elements, so nothing happens until an app calls this.
 *
 * **UIKit is covered, in a cold process and a warm one alike. SwiftUI is not covered at all** (#189,
 * #191). A `UIControl`, or a view carrying an enabled single-tap recognizer, resolves through
 * `UIView.hitTest` — the path UIKit actually delivers the touch along — **provided it carries an
 * `accessibilityIdentifier`**, which is the only thing ever reported as a `target`. An untagged control
 * resolves to nothing. SwiftUI has no per-element backing view for `hitTest` to find (measured: a whole
 * SwiftUI screen is a handful of `UIView`s, and `.accessibilityIdentifier` appears on none of them), and
 * no public API offers another route, so **taps on a SwiftUI surface must be instrumented explicitly**
 * with a `track()` call. Compose content has its own pipeline and is unaffected by any of this.
 *
 * **Why SwiftUI is not captured *warm* either, though it once was.** UIKit and SwiftUI build the
 * accessibility element tree only when an accessibility client has run in the process — VoiceOver, Voice
 * Control, the Accessibility Inspector, an XCUITest runner (#135, measured on a freshly created
 * simulator and again on a rebooted physical device). Until #191 a second resolver walked that tree
 * behind this one, which did name SwiftUI elements — but only in a process where such a client was
 * running. That is not a partial capture, it is a **biased** one: the taps it recorded were exactly
 * those made by assistive-technology users and by test automation, and its silence elsewhere is
 * indistinguishable downstream from nobody having tapped. Data that is quietly conditioned on a user's
 * assistive technology is worse to ship than no data, so the fallback was removed rather than kept for
 * the population it happened to serve. (This also removed the misattribution that same walk produced:
 * a UIView-backed SwiftUI container carrying an identifier could claim a tap on its own contents.)
 *
 * A tap that resolves to nothing is at least **not silently** lost: the first time it happens, an
 * `NSLog` line explains why — see [warnOnceIfANativeTapResolvedToNothing] — so a developer running the
 * app during integration has something in the console instead of nothing.
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

    // Held as the protocol type NSNotificationCenter hands back and takes again. Narrowing it to
    // NSObject with `as?` would turn an unexpected wrapper type into a silent null — an observer that
    // uninstall can never remove, still attaching recognizers to new windows and still holding the
    // tracker. Same silent-failure shape as `===` on the delegate match, avoided the same way.
    private var windowObserver: NSObjectProtocol? = null

    /**
     * Set by [uninstall], and checked by [attach].
     *
     * `addObserverForName` delivers its block as an *operation* on the main queue, not synchronously,
     * so a window-visible notification posted before [uninstall] can drain after it. Re-attaching then
     * would leave a recognizer in no [instrumented] set anyone iterates again — permanently reporting
     * into the very tracker [uninstall] exists to release.
     */
    private var uninstalled = false

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
        }
    }

    /** Detaches every recognizer and stops listening for new windows. Safe to call more than once. */
    public fun uninstall() {
        uninstalled = true
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
        if (uninstalled) return
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
     *
     * **One resolver, and it never reads accessibility state.** [resolveNativeTapTargetByHitTest] is
     * the whole of native resolution: `hitTest` is the touch-delivery path itself rather than a
     * geometric approximation of it, and it answers in a cold process. There used to be a second,
     * accessibility-walking resolver behind it; #191 removed it, because the only population it still
     * served was *warm* SwiftUI — see this file's header for why that is worse than not capturing.
     *
     * **The tri-state survives the second resolver it was introduced for**, for a different reason
     * that is easy to lose: it is what keeps the diagnostic honest. Only
     * [NativeHitTestResolution.Unresolved] means "nothing here could be named", which is the one
     * outcome worth telling a developer about. A [NativeHitTestResolution.Dropped] is this library
     * working as asked — an ignored region, Compose-owned content, a reserved identifier — and in a
     * hybrid app the very first tap is quite likely to be one, on Compose content that
     * `autograph-compose` then reports perfectly well. Collapsing the two to a nullable `String` would
     * spend the one-per-process warning on that tap and print a line blaming a pipeline that did
     * nothing wrong, while the tap that genuinely needed the warning gets silence.
     *
     * Internal rather than private only so tests can drive it without a `UITouch` — the same reasoning
     * as [attach]. Without that, the one line wiring the warning to an unresolved tap has no coverage
     * at all: dropping it reads as a simplification and would pass every other test.
     */
    internal fun report(positionInWindowPoints: AxPoint, window: UIView) {
        try {
            // Re-checked here, not only at attach time: windowLevel is mutable, and an app that raises
            // an already-instrumented window to alert level would otherwise keep having its taps
            // reported as ordinary app interactions — exactly what isCapturableWindow exists to stop.
            if (window is UIWindow && !window.isCapturableWindow()) return
            // Pixels are the space AutographIgnoredBounds registers rectangles in; points are what
            // hitTest takes. `scale` must be mainScreen's — see AxRect for why that space exists.
            val scale = UIScreen.mainScreen.scale.toFloat()
            val positionInWindowPx = AxPoint(positionInWindowPoints.x * scale, positionInWindowPoints.y * scale)
            when (val hit = resolveNativeTapTargetByHitTest(window, positionInWindowPoints, positionInWindowPx)) {
                is NativeHitTestResolution.Target -> track(hit.identifier)
                NativeHitTestResolution.Dropped -> return
                NativeHitTestResolution.Unresolved -> warnOnceIfANativeTapResolvedToNothing()
            }
        } catch (e: Exception) {
            // Swallowed: see kdoc above.
        }
    }

    private fun track(target: String) {
        tracker.track(eventName, scopeStack.current().enrich(EmptyJsonObject), target)
    }
}

/**
 * Set the first time [warnOnceIfANativeTapResolvedToNothing] runs. What it reports is a property of the
 * app's own view hierarchy — which surfaces are SwiftUI, which controls carry an identifier — so it does
 * not change between taps and nothing is gained by logging it on every one.
 *
 * Global rather than per [AutographNativeTapCapture] instance: `NSLog` itself is process-wide, and an
 * app that installs, uninstalls and reinstalls this capture (e.g. around a tracker replaced on logout)
 * must not see the warning repeat just because a new instance runs for the first time again.
 *
 * Main-thread-only, like the rest of this file, so no synchronization guards it.
 *
 * Internal rather than private only so tests can observe that the warning was spent — the same
 * reasoning as [AutographNativeTapCapture.attach]: nothing else in this file needs to read it.
 */
internal var warnedANativeTapResolvedToNothing = false

/**
 * The first time a native tap resolves to nothing, logs once via `NSLog` — loud enough that a developer
 * running the app during integration sees it in the console instead of silent nothing. Never logs again.
 *
 * **No tree-wide check gates this any more, and that is the point of #191's change rather than a
 * regression in precision.** The predecessor, `warnOnceIfAccessibilityTreeIsCold`, asked whether the
 * accessibility tree had been built, because that was what decided whether a native tap could resolve.
 * It no longer decides anything: [resolveNativeTapTargetByHitTest] never reads that tree, so a tap now
 * resolves to nothing for reasons that have nothing to do with coldness — SwiftUI content, which has no
 * per-element backing view at all, or a UIKit control carrying no `accessibilityIdentifier`. Keeping the
 * coldness question would have gated the warning on a fact that is no longer causal: an app whose taps
 * are all being lost on a *warm* tree would have been told nothing.
 *
 * Only [NativeHitTestResolution.Unresolved] reaches here — never a
 * [Dropped][NativeHitTestResolution.Dropped]. See [AutographNativeTapCapture.report] for why that
 * distinction is load-bearing: a vetoed tap is this library working correctly, and letting one spend
 * the single warning would print a misleading line and then never print the right one.
 *
 * Returns whether the warning was emitted — false when it was already spent. Internal, and returning at
 * all, only so tests can drive it: whether the `NSLog` line reaches a console is not observable
 * headlessly, so the return value is what lets a test pin the thing that is — that it fires at most once.
 */
internal fun warnOnceIfANativeTapResolvedToNothing(): Boolean {
    if (warnedANativeTapResolvedToNothing) return false
    warnedANativeTapResolvedToNothing = true
    // One argument, no varargs: the `%@` interop path crashes with EXC_BAD_ACCESS inside NSLog's own
    // formatting machinery (see sample-shared's SampleLog.ios.kt). That makes this literal NSLog's
    // *format string*, so it must stay free of `%` — a stray conversion specifier reads whatever
    // follows on the stack and reintroduces the same crash class from the other side.
    NSLog(
        "Autograph: a native tap resolved to nothing, so no event was sent for it. " +
            "installAutographNativeTapCapture names an element from the view that actually receives " +
            "the touch, which means it reports a UIKit control (a UIControl, or a view carrying an " +
            "enabled single-tap recognizer) that also has an accessibilityIdentifier. Two common " +
            "reasons a tap lands here: the control carries no accessibilityIdentifier, or the content " +
            "is SwiftUI. SwiftUI is not covered at all — its elements have no per-element backing view " +
            "for hit-testing to find, and there is no fix for that from public API — so taps on a " +
            "SwiftUI surface must be instrumented explicitly with a track() call rather than left to " +
            "this capture. This is expected, not a bug in your integration; see " +
            "installAutographNativeTapCapture's kdoc for the full account. Compose content is " +
            "unaffected and is still captured normally, by its own pipeline.",
    )
    return true
}
