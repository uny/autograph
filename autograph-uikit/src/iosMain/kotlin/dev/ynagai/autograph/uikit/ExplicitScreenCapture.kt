package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack

/**
 * Reports **SwiftUI** screen views that the UIKit `viewDidAppear:` swizzle
 * ([installAutographNativeScreenCapture]) cannot see.
 *
 * A SwiftUI screen is one `UIHostingController` from a *system* bundle — which the swizzle's filter
 * excludes — and `NavigationStack` swaps its destinations *inside* that single host with no
 * per-destination `viewDidAppear:`. So SwiftUI screens name themselves explicitly: a `.autographScreen`
 * modifier (shipped by the `AutographUI` Swift product) calls [appeared] when the screen appears and
 * [AutographScreenView.disappeared] when it leaves.
 *
 * ## Sharing with the rest of the pipeline
 *
 * Hand this the **same** [ScopeStack] given to `AutographProvider` and to
 * [installAutographNativeScreenCapture]/[installAutographNativeTapCapture] in a hybrid app: one stack
 * is what lets a SwiftUI screen become the `previous_screen` of the next UIKit/Compose screen, and lets
 * autocaptured taps on the SwiftUI screen carry it. There is no global sink here — sharing is exactly
 * "pass the same stack", nothing more, so a pure-SwiftUI app pays for no UIKit swizzle.
 *
 * ## Threading
 *
 * Main thread only — it is driven from SwiftUI's `onAppear`/`onDisappear` and touches [ScopeStack].
 */
public class AutographScreenCapture(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
) {

    /**
     * Reports that a screen named [name] appeared: pushes a screen frame onto the stack and emits a
     * `Screen Viewed` carrying the screen it replaced as `previous_screen`. Keep the returned
     * [AutographScreenView] and call [AutographScreenView.disappeared] when the screen leaves.
     *
     * A tracker failure is **never** allowed to escape into the SwiftUI `onAppear` caller — a Kotlin
     * exception unwinding into Swift with no `@Throws` crashes the app. The pushed frame stays
     * regardless of whether the emit succeeded (a screen's taps carry it even if its `Screen Viewed`
     * emit threw), exactly as the UIKit swizzle keeps its frame independent of emit success; it is
     * removed by [AutographScreenView.disappeared]. Screen history is not rolled back on failure —
     * `record` already ran, and whether the tracker actually delivered the event is unobservable, so
     * "un-recording" would only trade one inconsistency for another. This mirrors the swizzle.
     */
    public fun appeared(name: String): AutographScreenView {
        val handle = scopeStack.push(screen = name)
        try {
            scopeStack.emitScreenView(tracker, name)
        } catch (_: Throwable) {
        }
        return AutographScreenView(scopeStack, handle)
    }
}

/**
 * A live SwiftUI screen frame, returned by [AutographScreenCapture.appeared]. Its sole job is to remove
 * that frame from the stack when the screen leaves.
 */
public class AutographScreenView internal constructor(
    scopeStack: ScopeStack,
    handle: ScopeHandle,
) {

    // The nullable pair IS the active/inactive state: cleared on the first [disappeared] so a second
    // call — or one after a reconcile already retired this view — is a no-op rather than a
    // remove-of-an-already-removed handle.
    private var scopeStack: ScopeStack? = scopeStack
    private var handle: ScopeHandle? = handle

    /**
     * Removes this screen's frame from the stack. Idempotent — safe to call more than once, and a
     * no-op once already removed. Never throws into the SwiftUI `onDisappear` caller.
     */
    public fun disappeared() {
        val stack = scopeStack ?: return
        val frame = handle ?: return
        scopeStack = null
        handle = null
        try {
            stack.remove(frame)
        } catch (_: Throwable) {
            // ScopeStack.remove makes no exception-safety promise (its recompute could throw); swallow
            // so cleanup never crashes the onDisappear caller. A rare, documented limitation.
        }
    }
}
