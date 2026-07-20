@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, AutographInternalApi::class)

package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSBundle
import platform.Foundation.NSHashTable
import platform.Foundation.NSHashTableObjectPointerPersonality
import platform.Foundation.NSHashTableWeakMemory
import platform.Foundation.NSStringFromClass
import platform.UIKit.UINavigationController
import platform.UIKit.UIPageViewController
import platform.UIKit.UISplitViewController
import platform.UIKit.UITabBarController
import platform.UIKit.UIViewController
import platform.darwin.IMP
import platform.objc.class_getInstanceMethod
import platform.objc.method_setImplementation
import platform.objc.object_getClass
import platform.objc.sel_registerName

/**
 * Starts reporting native **UIKit** screen transitions: on every `UIViewController.viewDidAppear:`
 * that names a screen, a `Screen Viewed` event is emitted via [tracker] and a screen frame is pushed
 * onto [scopeStack] so autocaptured taps on that screen carry it; the frame is removed on
 * `viewDidDisappear:`. This is what makes #62's native taps stop carrying no `screen`.
 *
 * Opt-in, exactly as tap capture is: nothing happens until an app calls this. Pass the **same**
 * [scopeStack] handed to `AutographProvider` and to [installAutographNativeTapCapture] if the app is
 * hybrid — one stack is what lets a native screen become the `previous_screen` of the next Compose
 * screen and enriches taps from either pipeline.
 *
 * ## What this captures, precisely — and what it does not
 *
 * This is **UIKit `UIViewController` appearance capture**, nothing more. A screen is reported when a
 * view controller that belongs to the app (not a UIKit/SwiftUI system container) appears. That covers
 * the ordinary cases — a window's root controller, a controller pushed onto a `UINavigationController`,
 * a presented controller — because each fires `viewDidAppear:` on a distinct app controller.
 *
 * It deliberately does **not** cover:
 * - **SwiftUI**, including `NavigationStack`. Every SwiftUI screen is one `UIHostingController` from a
 *   *system* bundle (so the filter excludes it), and `NavigationStack` swaps its destinations *inside*
 *   that single host with no per-destination `viewDidAppear:`. A SwiftUI screen names itself with the
 *   one-line `.autographScreen("Name")` modifier instead; a pure-SwiftUI app gets no screen from this
 *   swizzle. (That modifier is a separate, Swift-side API.)
 * - **Container controllers** — `UINavigationController`, `UITabBarController`, `UISplitViewController`,
 *   `UIPageViewController` and their subclasses. A container is not itself a screen; its *content*
 *   controller is the screen. Their own `viewDidAppear:` is skipped so one push does not emit both
 *   "the nav controller" and "the screen it now shows". An app that subclasses a container (a common
 *   pattern) is caught by the same rule, because the check is by class kind, not by bundle.
 * - **Embedded child controllers** (`addChild`) used as panels or decorations, and the multiple
 *   simultaneously-visible panes of a `UISplitViewController`: there is no single "current screen"
 *   there, so this reports whichever app controller's `viewDidAppear:` fired and does not try to
 *   reconcile several visible at once. Treat those as needing the explicit screen API, like SwiftUI.
 * - **Taps on Compose content.** A controller whose view subtree contains a registered
 *   [AutographComposeHosts] view is Compose-owned and skipped, so a hybrid app's Compose host does not
 *   also report a native screen for the Compose screen it renders.
 *
 * ## The hook is permanent
 *
 * The swizzle installs once, process-wide, and is **never removed** — not even by [uninstall]. Other
 * SDKs (Firebase, Sentry, Datadog) swizzle `viewDidAppear:` too; restoring our saved implementation
 * when one of them hooked on top of us would break *their* chain. [uninstall] instead clears the sink
 * this capture reports into, so reporting stops while the harmless hook stays. A second
 * [installAutographNativeScreenCapture] replaces the sink.
 *
 * ## Screen naming
 *
 * By default a screen is named by its controller's class, verbatim — module prefix and all, no
 * `ViewController`-stripping — so this imposes no naming convention of its own. Supply [screenName] to
 * clean that up, or to return `null` for a controller that should not be reported at all (an opt-out).
 *
 * ## Threading
 *
 * Main thread only, to install, to [uninstall], and throughout the hook — it is called by UIKit on the
 * main thread and it touches [ScopeStack] and UIKit throughout.
 *
 * ## Already-visible screens
 *
 * Only *transitions after install* are seen: a controller already on screen when this is called does
 * not retroactively fire `viewDidAppear:`. Installing at launch, before the first screen appears,
 * captures that first screen; a late install misses the current screen until the next transition.
 *
 * Keep the returned handle: it is the only way to [AutographNativeScreenCapture.uninstall].
 */
@AutographInternalApi
public fun installAutographNativeScreenCapture(
    tracker: Tracker,
    scopeStack: ScopeStack,
    screenName: (UIViewController) -> String? = ::defaultScreenName,
): AutographNativeScreenCapture {
    installScreenSwizzleOnce()
    val owner = Any()
    activeSink = ScreenSink(owner, tracker, scopeStack, screenName)
    return AutographNativeScreenCapture(owner)
}

/**
 * A running native screen capture. Created by [installAutographNativeScreenCapture].
 *
 * Holds nothing itself; the sink it created (with [tracker] and [scopeStack]) lives in a
 * process-global slot until [uninstall] clears it or another install replaces it.
 */
@AutographInternalApi
public class AutographNativeScreenCapture internal constructor(private val owner: Any) {

    /**
     * Stops this capture reporting, and removes the screen frames it pushed from the stacks they were
     * pushed onto. Safe to call more than once. The swizzle itself is left in place (see the class
     * kdoc): it does nothing while no sink is active.
     *
     * Compare-and-clear by owner: `install(A) → install(B) → A.uninstall()` must not silence B. Frames
     * are removed only for *this* owner and from the stack each was actually pushed onto, so an
     * uninstall after a sink swap leaves B's visible screen frames alone.
     */
    public fun uninstall() {
        if (activeSink?.owner === owner) {
            activeSink = null
        }
        // Remove only our own frames, each from the stack it was pushed onto. A frame pushed onto a
        // now-retired stack is still ours to remove; a frame the *new* sink pushed is not.
        removeScreenEntries { it.owner === owner }
    }
}

/** The screen name a controller is reported under by default: its class name, verbatim. */
@AutographInternalApi
public fun defaultScreenName(controller: UIViewController): String? =
    NSStringFromClass(object_getClass(controller) ?: return null)

// --- Process-global state (main-thread-confined; see class kdoc) --------------------------------

/**
 * What an active capture reports into: its [tracker], the [scopeStack] it pushes screen frames onto,
 * and how it [screenName]s a controller. [owner] is an identity token minted per install so
 * [AutographNativeScreenCapture.uninstall] can compare-and-clear without silencing a later install.
 */
private class ScreenSink(
    val owner: Any,
    val tracker: Tracker,
    val scopeStack: ScopeStack,
    val screenName: (UIViewController) -> String?,
)

/** The current sink, or null when no capture is installed (the hook then does nothing). */
private var activeSink: ScreenSink? = null

/**
 * One pushed screen frame, awaiting the controller's disappearance or deallocation to be removed.
 *
 * [weakController] holds the controller **weakly, through Objective-C** — an `NSHashTable` of one, the
 * same construction [AutographNativeTapCapture] uses for windows. Liveness is asked of it, not of a
 * `kotlin.native.ref.WeakReference`: a Kotlin wrapper's lifetime is not the underlying controller's
 * (Kotlin/Native does not canonicalize interop wrappers), so a `WeakReference` could read as dead
 * while the controller lives — dropping a screen frame out from under a screen still on display. The
 * ObjC weak reference zeroes exactly when the controller deallocates.
 *
 * [owningScopeStack] is remembered per entry, not read from the current sink at removal time: after a
 * sink swap a stale controller's `viewDidDisappear:` must remove the frame from the stack it was
 * pushed onto. Removing from the new sink's stack is a silent no-op ([ScopeStack.remove] ignores a
 * handle from another stack), leaving the frame stranded on the old one.
 */
private class ScreenEntry(
    val owner: Any,
    val owningScopeStack: ScopeStack,
    val handle: ScopeHandle,
    val weakController: NSHashTable,
) {
    fun claims(controller: UIViewController): Boolean = weakController.containsObject(controller)
    fun isDead(): Boolean = weakController.allObjects.isEmpty()
}

/**
 * The live screen frames, newest last. A plain strong list — deliberately not an `NSMapTable` keyed by
 * the controller — because a frame has to be removed from [ScopeStack] *actively* when its controller
 * goes away, and a weak-keyed map silently drops a dead entry with no chance to run that removal. The
 * list is swept on every hook (see [sweepDeadScreenEntries]); controllers are held weakly per entry, so
 * the list never keeps a controller alive.
 */
private val screenEntries = ArrayList<ScreenEntry>()

private var appearOriginalImp: IMP? = null
private var disappearOriginalImp: IMP? = null
private var screenSwizzleInstalled = false

private fun installScreenSwizzleOnce() {
    if (screenSwizzleInstalled) return
    // The class to swizzle, fetched the measured way: object_getClass of an instance returns its
    // class (`objc_getClass(name)` is typed to return a plain `Any?` here and would need casting).
    val cls = object_getClass(UIViewController())
    val appear = class_getInstanceMethod(cls, sel_registerName("viewDidAppear:"))
    val disappear = class_getInstanceMethod(cls, sel_registerName("viewDidDisappear:"))
    if (appear == null || disappear == null) return
    // Use the IMP method_setImplementation RETURNS as the chained original — the just-replaced one,
    // read atomically with the replacement. Reading it separately with method_getImplementation first
    // would race another SDK swizzling in the gap and chain to the wrong implementation.
    appearOriginalImp = method_setImplementation(appear, staticCFunction(::appearHook).reinterpret())
    disappearOriginalImp = method_setImplementation(disappear, staticCFunction(::disappearHook).reinterpret())
    screenSwizzleInstalled = true
}

// The replacement IMPs. Top-level and capture-free: staticCFunction rejects anything else. Each does
// our work inside a Throwable-catch — a Kotlin exception unwinding into the ObjC caller would crash the
// host app — then chains to the original exactly once, OUTSIDE the catch, so the original always runs
// in its normal order even if our logic threw. (NSException from the original is not a Kotlin throwable
// and is not claimed to be caught here.)
private fun appearHook(self: COpaquePointer?, cmd: COpaquePointer?, animated: Boolean) {
    try {
        onViewDidAppear(self)
    } catch (_: Throwable) {
    }
    appearOriginalImp?.asVoidBoolImp()?.invoke(self, cmd, animated)
}

private fun disappearHook(self: COpaquePointer?, cmd: COpaquePointer?, animated: Boolean) {
    try {
        onViewDidDisappear(self)
    } catch (_: Throwable) {
    }
    disappearOriginalImp?.asVoidBoolImp()?.invoke(self, cmd, animated)
}

private fun IMP.asVoidBoolImp() =
    reinterpret<CFunction<(COpaquePointer?, COpaquePointer?, Boolean) -> Unit>>()

private fun onViewDidAppear(self: COpaquePointer?) {
    val controller = self.asViewControllerOrNull() ?: return
    val sink = activeSink ?: return
    sweepDeadScreenEntries()
    // Dedup: a controller already on the stack re-firing viewDidAppear: — a cancelled interactive pop
    // re-shows the top screen with no viewDidDisappear: in between — must not emit again. Do this
    // before naming or recording: a skipped re-appearance must not touch previous_screen either.
    if (screenEntries.any { it.claims(controller) }) return
    if (!controller.isCapturableScreen()) return
    val name = sink.screenName(controller) ?: return
    // Push the frame and register the entry BEFORE emitting, so that if the tracker throws the frame
    // is already tracked and viewDidDisappear: can still remove it. record() then feeds the emit its
    // previous_screen, per ScreenHistory's contract.
    val handle = sink.scopeStack.push(screen = name)
    screenEntries.add(ScreenEntry(sink.owner, sink.scopeStack, handle, weakSetOf(controller)))
    // `previous == name` when this same screen is re-entered with nothing capturable recorded in
    // between — e.g. leaving a UIKit screen for an excluded one (a SwiftUI tab, a system/Compose modal)
    // and coming back. The intermediate screen is genuinely unknown, so the honest previous_screen is
    // none, not the screen naming itself. The Compose path never hits this (its LaunchedEffect is keyed
    // on the name, so it cannot re-record an unchanged one); this is the first caller that can, so the
    // guard lives here rather than in ScreenHistory.record.
    val previous = sink.scopeStack.screenHistory.record(name)?.takeIf { it != name }
    sink.tracker.screen(name, withPreviousScreen(previous))
}

private fun onViewDidDisappear(self: COpaquePointer?) {
    val controller = self.asViewControllerOrNull() ?: return
    sweepDeadScreenEntries()
    val index = screenEntries.indexOfFirst { it.claims(controller) }
    if (index < 0) return
    val entry = screenEntries.removeAt(index)
    entry.owningScopeStack.remove(entry.handle)
}

/**
 * Removes frames whose controller has deallocated without a `viewDidDisappear:` — a controller torn
 * out of the hierarchy, say. Without this the frame would leak on its stack permanently ([ScopeStack]
 * holds it strongly and would never be told to drop it). Run at the top of every hook so a leaked
 * frame is reclaimed at the very next transition, deterministically and on the main thread — no
 * dependency on Objective-C `dealloc` (which Kotlin/Native cannot override) or on GC timing.
 */
private fun sweepDeadScreenEntries() {
    removeScreenEntries { it.isDead() }
}

/**
 * Removes every entry [shouldRemove] selects, dropping each one's frame from the stack it was pushed
 * onto — the single place that couples "forget the entry" with "and drop its frame from the *right*
 * stack", so the sweep and [AutographNativeScreenCapture.uninstall] cannot diverge on that invariant.
 */
private fun removeScreenEntries(shouldRemove: (ScreenEntry) -> Boolean) {
    screenEntries.removeAll { entry ->
        shouldRemove(entry).also { remove ->
            if (remove) entry.owningScopeStack.remove(entry.handle)
        }
    }
}

private fun COpaquePointer?.asViewControllerOrNull(): UIViewController? {
    val raw = this ?: return null
    return interpretObjCPointerOrNull<UIViewController>(raw.rawValue)
}

/**
 * Whether this controller is an app screen worth reporting: from the app bundle, not a system
 * container type, and not hosting Compose. All three are needed and none alone suffices — a
 * `ComposeUIViewController` is app-bundled and excluded only by the Compose check; a
 * `UINavigationController` has no Compose host and is excluded only by the container check; a
 * `UIHostingController` is system-bundled and excluded only by the bundle check.
 */
private fun UIViewController.isCapturableScreen(): Boolean {
    if (isSystemContainer()) return false
    if (!isFromAppBundle()) return false
    // `view` forces the controller to load its view if it has not — at viewDidAppear: it is always
    // loaded, so this is a plain read — and is non-null by the time appearance fires.
    return !AutographComposeHosts.hostInSubtree(view)
}

private fun UIViewController.isSystemContainer(): Boolean =
    this is UINavigationController ||
        this is UITabBarController ||
        this is UISplitViewController ||
        this is UIPageViewController

/**
 * Whether this controller's class was loaded from inside the app bundle — true for the app's own
 * controllers and for those in frameworks embedded within the `.app`, false for UIKit/SwiftUI system
 * classes. Compared by path *component*, not raw string prefix: a raw `startsWith` would let a sibling
 * directory sharing a name prefix pass, and would wrongly exclude an app whose bundle path is itself a
 * prefix of nothing.
 */
private fun UIViewController.isFromAppBundle(): Boolean {
    val cls = object_getClass(this) ?: return false
    val classBundlePath = NSBundle.bundleForClass(cls).bundlePath
    val appBundlePath = NSBundle.mainBundle.bundlePath
    return classBundlePath.isUnderPathComponents(appBundlePath)
}

/**
 * Path-component containment: whether [ancestor]'s components are a prefix of this path's. Internal
 * rather than private only so its sibling-prefix edge — the reason it is component-wise and not a raw
 * `startsWith` — can be unit-tested without a real bundle hierarchy; not part of the public ABI.
 */
internal fun String.isUnderPathComponents(ancestor: String): Boolean {
    val here = trimEnd('/').split('/')
    val above = ancestor.trimEnd('/').split('/')
    if (here.size < above.size) return false
    return above.indices.all { here[it] == above[it] }
}

private fun weakSetOf(controller: UIViewController): NSHashTable =
    NSHashTable.hashTableWithOptions(NSHashTableWeakMemory or NSHashTableObjectPointerPersonality)
        .also { it.addObject(controller) }

/**
 * [properties]-free equivalent of `autograph-compose`'s `withPreviousScreen`: a `Screen Viewed`'s
 * `previous_screen`, or nothing when this is the first screen. Native screen views carry no base
 * properties, so there is no caller-supplied `previous_screen` to preserve.
 */
private fun withPreviousScreen(previous: String?): JsonObject =
    if (previous != null) {
        JsonObject(mapOf("previous_screen" to JsonPrimitive(previous)))
    } else {
        EmptyJsonObject
    }
