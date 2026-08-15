@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack

/**
 * The engine behind [installAutographNativeTapCapture]: it keeps one [TapWindowCallback] of its own
 * on each foreground Activity's [Window], resolves every touch-up through [resolveTapTarget], and
 * reports what that names. Main-thread-confined (see the install kdoc), so the plain fields need no
 * guard.
 */
internal class AndroidTapCapture(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
    private val eventName: String,
) : Application.ActivityLifecycleCallbacks {

    private var active = true

    /**
     * The wrapper this capture currently considers its own, per window. Weak keys: a window belongs
     * to its Activity and must not be kept alive by this map.
     */
    private val wrappers = java.util.WeakHashMap<Window, TapWindowCallback>()

    // The last touch-up already reported, identified by the event's own timestamps. A belt-and-braces
    // guard for the one shape that would double-count: if this capture ever ends up twice in a single
    // callback chain, both copies see the same MotionEvent and only the first gets to report it.
    private var lastDownTime = Long.MIN_VALUE
    private var lastEventTime = Long.MIN_VALUE

    override fun onActivityResumed(activity: Activity) {
        if (!active) return
        // On API 29+ onActivityPostResumed is strictly better (below), and running both would just
        // wrap twice in a row.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        wrap(activity)
        // This callback runs *inside* Activity.onResume, so an Activity that installs its own
        // Window.Callback there would replace ours immediately afterwards. Re-checking on the next
        // main-loop message catches that. API 29+ has onActivityPostResumed, which is already past it.
        activity.window.decorView.post { if (active) wrap(activity) }
    }

    override fun onActivityPostResumed(activity: Activity) {
        if (!active) return
        wrap(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!active) return
        // Retire, never unwrap — see ensureWrapped.
        wrappers.remove(activity.window)?.active = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun wrap(activity: Activity) {
        val window = runCatching { activity.window }.getOrNull() ?: return
        ensureWrapped(window)
    }

    /**
     * Puts this capture's wrapper on top of [window]'s callback chain, at most once per window at a
     * time.
     *
     * **Wrapping is never undone, only retired.** Another SDK that also wraps `Window.Callback`
     * (several do) will have wrapped *our* wrapper, so restoring the delegate we captured would
     * silently drop theirs. Instead the previous wrapper is left in the chain, still delegating, with
     * its reporting switched off — and a fresh wrapper goes on top. Without the switch-off, an app
     * running alongside such an SDK would end up with two live copies of this capture in one chain
     * and report every tap twice.
     */
    private fun ensureWrapped(window: Window) {
        val current = runCatching { window.callback }.getOrNull() ?: return
        val existing = wrappers[window]
        if (existing != null) {
            if (current === existing) return // still on top, and still ours
            existing.active = false
        }
        val wrapper = TapWindowCallback(window, current, ::onTouchUp)
        runCatching { window.callback = wrapper }.onFailure { return }
        wrappers[window] = wrapper
    }

    /**
     * Called synchronously, with the touch-up dispatch already delivered but still on the stack —
     * which is the only moment the pressed state [resolveTapTarget] reads is still set.
     */
    private fun onTouchUp(window: Window, event: MotionEvent) {
        if (!active) return
        if (event.downTime == lastDownTime && event.eventTime == lastEventTime) return
        lastDownTime = event.downTime
        lastEventTime = event.eventTime
        report(window)
    }

    private fun report(window: Window) {
        try {
            // The window the event was delivered to, not just any window: an app with several
            // Activities stacked keeps a wrapper for each of them.
            val root = window.peekDecorView() ?: return
            when (val resolution = resolveTapTarget(root)) {
                is TapResolution.Target ->
                    tracker.track(eventName, scopeStack.current().enrich(EmptyJsonObject), resolution.identifier)
                TapResolution.Ambiguous -> Unit
                TapResolution.Unresolved -> warnOnceIfATapResolvedToNothing()
            }
        } catch (_: Throwable) {
            // Swallowed: a single bad resolve, or a throwing tracker, must not poison touch dispatch
            // for the rest of the app's life. This runs on every tap the user makes.
        }
    }

    /** Stops reporting and retires every wrapper this capture installed. Idempotent. */
    fun tearDown() {
        active = false
        wrappers.values.forEach { it.active = false }
        wrappers.clear()
    }
}

/**
 * The one thing this capture puts into an app's touch path: it delegates every call unchanged — so
 * nothing is consumed, delayed, or reordered — and, after the delegate has handled a touch-up,
 * reports it.
 *
 * [active] exists because [AndroidTapCapture.ensureWrapped] cannot remove a wrapper another SDK has
 * since wrapped; a retired wrapper stays in the chain as a pure pass-through.
 *
 * Both `ACTION_UP` and `ACTION_POINTER_UP` count: with several fingers down, only the last one lifts
 * as `ACTION_UP`, so watching that alone loses the taps made by every other finger.
 */
internal class TapWindowCallback(
    private val window: Window,
    private val delegate: Window.Callback,
    private val onTouchUp: (Window, MotionEvent) -> Unit,
) : Window.Callback by delegate {

    var active: Boolean = true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val isTouchUp = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        val consumed = delegate.dispatchTouchEvent(event)
        // After the delegate, never before: the pressed state that identifies the target is set by
        // the dispatch this line just performed.
        if (active && isTouchUp) onTouchUp(window, event)
        return consumed
    }
}
