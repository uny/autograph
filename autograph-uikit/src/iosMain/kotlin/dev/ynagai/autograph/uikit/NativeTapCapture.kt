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
 * Starts reporting taps on native (UIKit/SwiftUI) content as [eventName], resolved by two resolvers in
 * order: [resolveNativeTapTargetByHitTest], which reads no accessibility state, and then
 * [resolveNativeTapTarget], which walks the accessibility tree.
 *
 * Opt-in, exactly as Compose autocapture is: observing every tap is a different privacy posture than
 * instrumenting individual elements, so nothing happens until an app calls this.
 *
 * **UIKit is covered in a cold process; SwiftUI is not** (#189, #135). UIKit and SwiftUI build the
 * accessibility element tree only once some accessibility client has run (VoiceOver, Voice Control, the
 * Accessibility Inspector, an XCUITest runner) — measured on a freshly created simulator and again on a
 * rebooted physical device. A `UIView.hitTest` never consults that tree, so a `UIControl` or a view with
 * an enabled single-tap recognizer resolves cold, **provided it carries an `accessibilityIdentifier`** —
 * that identifier is the only thing ever reported as a `target`, so an untagged control falls through
 * and is still dropped cold. SwiftUI has no per-element backing view for `hitTest` to find, so its
 * elements stay accessibility-only and every SwiftUI tap is dropped until a client has run. There is no
 * fix available from public API for that half, so anything on a SwiftUI surface that must not be lost
 * needs explicit instrumentation. Compose autocapture does not share the limitation.
 *
 * A tap that resolves to nothing is at least **not silently** lost: the first time it happens, an
 * `NSLog` line names the cold tree — see [warnOnceIfAccessibilityTreeIsCold] (#170) — so a developer
 * running the app during integration has something in the console instead of nothing.
 *
 * **A hybrid app's own Compose autocapture does not warm this pipeline either (#135, measured).**
 * `autograph-compose`'s iOS resolver activates CMP's accessibility bridge on tap (see
 * `AccessibilityTree.kt`), which is a *separate* on-demand activation from the one UIKit/SwiftUI gate
 * behind. In sequence, one process: a real Compose tap confirmed via `Element Clicked` in the same
 * process, immediately followed by a tap on an unrelated SwiftUI element, still resolved to nothing —
 * identical to a process no client had ever touched. So this pipeline's cold-inertness is not narrowed
 * to "pure-native apps with no Compose anywhere"; it applies to every hybrid app too, until a genuine
 * accessibility client has run in the process.
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
     * **Two resolvers, `hitTest` first.** [resolveNativeTapTargetByHitTest] reads no accessibility
     * state at all, so it answers in a cold process where [resolveNativeTapTarget] cannot — that is
     * #189's whole point, and this is the only place the two are sequenced. The tri-state it returns
     * is what makes the sequencing safe: only [NativeHitTestResolution.Unresolved] falls through, and
     * a [NativeHitTestResolution.Dropped] must not, or a veto applied by one resolver would be undone
     * by the other. See [NativeHitTestResolution].
     *
     * The cold warning stays wired to the *final* nothing, and only on the fall-through branch. A tap
     * this resolver deliberately dropped is not a symptom of a cold tree, and the check is spent once
     * per process — so letting a vetoed tap consume it would print a misleading line and then never
     * print the right one.
     *
     * Internal rather than private only so tests can drive it without a `UITouch` — the same reasoning
     * as [attach]. Without that, the one line wiring #170's warning to a dropped tap has no coverage at
     * all: a revert to `?: return` reads as a simplification and would pass every other test.
     */
    internal fun report(positionInWindowPoints: AxPoint, window: UIView) {
        try {
            // Re-checked here, not only at attach time: windowLevel is mutable, and an app that raises
            // an already-instrumented window to alert level would otherwise keep having its taps
            // reported as ordinary app interactions — exactly what isCapturableWindow exists to stop.
            if (window is UIWindow && !window.isCapturableWindow()) return
            // `scale` is mainScreen's, and must be — the accessibility frames this is compared
            // against are converted out of mainScreen's coordinate space. See
            // accessibilityBoundsInWindowPx, whose precondition this inherits.
            val scale = UIScreen.mainScreen.scale.toFloat()
            val positionInWindowPx = AxPoint(positionInWindowPoints.x * scale, positionInWindowPoints.y * scale)
            when (val hit = resolveNativeTapTargetByHitTest(window, positionInWindowPoints, positionInWindowPx)) {
                is NativeHitTestResolution.Target -> track(hit.identifier)
                NativeHitTestResolution.Dropped -> return
                NativeHitTestResolution.Unresolved -> {
                    val target = resolveNativeTapTarget(window, positionInWindowPx, scale)
                    if (target == null) warnOnceIfAccessibilityTreeIsCold(window) else track(target)
                }
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
 * Set the first time [warnOnceIfAccessibilityTreeIsCold] runs. See #170: the underlying cause is a
 * one-time process state (whether an accessibility client has ever run), not something that flips back
 * and forth, so nothing is gained by asking twice.
 *
 * Global rather than per [AutographNativeTapCapture] instance: `NSLog` itself is process-wide, and an
 * app that installs, uninstalls and reinstalls this capture (e.g. around a tracker replaced on logout)
 * must not see the warning repeat just because a new instance runs the check for the first time again.
 *
 * Main-thread-only, like the rest of this file, so no synchronization guards it.
 *
 * Internal rather than private only so tests can observe that a check happened — the same reasoning as
 * [AutographNativeTapCapture.attach]: nothing else in this file needs to read it.
 */
internal var checkedAccessibilityTreeColdness = false

/**
 * The first time any native tap resolves to nothing, checks whether the accessibility tree is cold
 * ([isAccessibilityTreeCold]) and, if so, logs once via `NSLog` — loud enough that a developer running
 * the app during integration sees it in the console instead of silent nothing. Never checks or logs
 * again after the first call, whatever the outcome.
 *
 * Deliberately not gated on *which* of [resolveNativeTapTarget]'s five numbered drop reasons produced
 * the null (it has two further unnumbered vetoes besides). [isAccessibilityTreeCold] answers the
 * tree-wide question directly, and when the tree genuinely is cold every native tap drops for reason 1
 * — the walk finds nothing anywhere — so checking on any drop and gating the log on tree-wide coldness
 * reaches the same answer without [resolveNativeTapTarget] having to expose which case fired. A drop on
 * a *warm* tree, whichever reason produced it, correctly finds real elements elsewhere in the tree and
 * stays silent — this is what keeps an ordinary tap-missed-everything from logging on every miss.
 *
 * [root] is the same [UIView] [resolveNativeTapTarget] was just asked to search — passing anything else
 * would check a different tree than the one that just dropped the tap.
 *
 * The line it logs makes a claim about the *tree*, not about the tap that triggered it, and that is
 * deliberate. In a hybrid app the first drop is quite likely a tap on Compose content, which
 * [resolveNativeTapTarget] vetoes at the Compose-host boundary and `autograph-compose` then reports
 * perfectly well — blaming coldness for that particular tap would be wrong. What coldness does mean is
 * that *native* taps are being lost, which is true whichever tap happened to ask.
 *
 * Returns whether the warning was emitted — false both when the check was already spent and when the
 * tree came back warm. Internal, and returning at all, only so tests can drive it: whether the `NSLog`
 * line reaches a console is not observable headlessly, so the return value is what lets a test pin the
 * two things that are — that the coldness question is answered correctly, and that it is asked at most
 * once. On-device verification covers the rest (see the PR for #170).
 */
internal fun warnOnceIfAccessibilityTreeIsCold(root: UIView): Boolean {
    if (checkedAccessibilityTreeColdness) return false
    checkedAccessibilityTreeColdness = true
    if (!isAccessibilityTreeCold(root)) return false
    // One argument, no varargs: the `%@` interop path crashes with EXC_BAD_ACCESS inside NSLog's own
    // formatting machinery (see sample-shared's SampleLog.ios.kt). That makes this literal NSLog's
    // *format string*, so it must stay free of `%` — a stray conversion specifier reads whatever
    // follows on the stack and reintroduces the same crash class from the other side.
    NSLog(
        "Autograph: a tap resolved to nothing, and the UIKit/SwiftUI accessibility tree looks cold — " +
            "it has not been built yet in this process, so installAutographNativeTapCapture cannot " +
            "resolve a SwiftUI tap until an accessibility client (VoiceOver, Voice Control, the " +
            "Accessibility Inspector, or an XCUITest runner) has run once. UIKit is not affected: a " +
            "UIControl, or a view with an enabled single-tap recognizer, resolves through hitTest even " +
            "cold, as long as it carries an accessibilityIdentifier — an untagged one lands here too. " +
            "This is expected, not a bug in your integration — see installAutographNativeTapCapture's " +
            "kdoc for the full explanation. Taps you cannot afford to lose should be instrumented " +
            "explicitly (Modifier.trackClick on Compose content, or an explicit tracker.track() call on " +
            "native content) rather than relying on this capture alone. Compose content is unaffected " +
            "and is still captured normally.",
    )
    return true
}
