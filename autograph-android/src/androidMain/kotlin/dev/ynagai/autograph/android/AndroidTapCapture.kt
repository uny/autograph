@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Application
import android.util.Log
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.DEFAULT_AUTOCAPTURE_EVENT_NAME
import dev.ynagai.autograph.context.ScopeStack

/**
 * Starts reporting taps on native **Android View** content (XML layouts, `RecyclerView` rows, legacy
 * screens) as [eventName]. This is the Android counterpart of the iOS UIKit tap capture, and the
 * pipeline `autograph-compose` cannot serve: Compose autocapture hit-tests the semantics tree, which
 * does not see an `android.view.View` hierarchy at all.
 *
 * Opt-in, exactly as Compose autocapture is: observing every tap is a different privacy posture than
 * instrumenting individual elements, so nothing happens until an app calls this.
 *
 * Pass the **same** [scopeStack] the app gives `AutographProvider` and
 * [installAutographNativeScreenCapture]. Sharing one stack is what lets a View-screen tap carry the
 * screen and scope a Compose screen pushed, and vice versa.
 *
 * ## How a tap is resolved, and why it is not a hit test
 *
 * Every touch is seen by wrapping the Activity's `Window.Callback`, which delegates everything
 * unchanged — nothing is consumed, delayed or reordered. On touch-up the target is the **root of the
 * pressed subtree**: touch dispatch marks the view it delivered to as pressed, so reading that back
 * inherits sibling z-ordering, enabled state, gesture stealing by a scrolling parent, and
 * consumption, rather than re-deriving them geometrically. Measured against a geometric walk on the
 * same taps, the walk named the wrong sibling of an elevation-reordered pair, named a disabled
 * button no click reached, and named an element at the end of a *scroll*; this rule matched what
 * actually fired in every case. See [resolveTapTarget].
 *
 * `target` is the view's resource entry name and nothing else — never displayed text, never a
 * `contentDescription`. A view with no developer-set id reports nothing.
 *
 * ## What is not captured — none of it silently, all of it by construction
 *
 * - **Taps that never travel through touch dispatch.** A `View` whose `OnTouchListener` consumes the
 *   gesture and calls `performClick()` itself (the pattern Android lint recommends for custom
 *   views), a `GestureDetector`-driven custom view, and — the case worth stating loudest — a click
 *   made with a **keyboard, D-pad, or an accessibility service's `ACTION_CLICK`**. Those never set
 *   the pressed state and are invisible here. The last of them means this capture's silence is not
 *   evenly distributed across users: someone driving the app with TalkBack or a keyboard produces no
 *   taps at all, which downstream is indistinguishable from not tapping.
 * - **Dialogs, `PopupWindow`s, and everything built on them** — a `Spinner`'s dropdown, an overflow
 *   menu, an `AutoCompleteTextView`'s suggestions. Each renders in a window this capture is not on.
 *   This is a deliberate boundary rather than an impossibility: the routes that would cover them are
 *   reflection into framework internals (what Curtains does) or an explicit per-window registration
 *   API, and neither is in this release.
 * - **Compose content**, which has its own pipeline. Nothing special is done to exclude it and none
 *   is needed: Compose routes pointer input itself and never sets the View pressed state, so a tap
 *   on a `ComposeView` resolves to nothing here and is reported exactly once, by `autograph-compose`.
 * - **Multi-touch on separate elements.** With two fingers down on two views, a pointer lifting
 *   cannot be attributed to one of them, so nothing is reported for it.
 * - **A `ListView` row reports the list, not the row.** `AbsListView` presses both, and the row of a
 *   platform row layout carries a platform id (`text1`) shared by every list in the app, so the
 *   list's own id is the better of the two available answers. A `RecyclerView` row is an ordinary
 *   pressed view and reports its own id.
 *
 * One known misreport, kept deliberately: a **long press consumed by an `OnLongClickListener`**
 * reports as a tap on that element. The framework's own "the long press was handled" flag is
 * private, and the only public proxy — press duration — would drop *real* clicks, because a long
 * press on a view whose listener returns `false` does fire a click. The element named is correct;
 * the gesture kind is not.
 *
 * ## Threading
 *
 * Main thread only, to install, to [AutographNativeTapCapture.uninstall], and throughout — the
 * lifecycle callbacks and touch dispatch are both delivered there.
 *
 * Keep the returned handle: it is the only way to [AutographNativeTapCapture.uninstall], and the
 * capture holds [tracker] and [scopeStack] strongly until then.
 */
@AutographInternalApi
public fun installAutographNativeTapCapture(
    application: Application,
    tracker: Tracker,
    scopeStack: ScopeStack,
    eventName: String = DEFAULT_AUTOCAPTURE_EVENT_NAME,
): AutographNativeTapCapture {
    val capture = AndroidTapCapture(tracker, scopeStack, eventName)
    application.registerActivityLifecycleCallbacks(capture)
    return AutographNativeTapCapture(application, capture)
}

/**
 * A running native tap capture. Created by [installAutographNativeTapCapture]; keep it to
 * [uninstall].
 */
@AutographInternalApi
public class AutographNativeTapCapture internal constructor(
    private val application: Application,
    private val capture: AndroidTapCapture,
) {

    /**
     * Stops this capture: unregisters its lifecycle callbacks and switches off every callback wrapper
     * it installed. Safe to call more than once.
     *
     * The wrappers themselves stay in the windows' callback chains as pure pass-throughs. Removing
     * them would mean restoring the callback each one captured, and if another SDK wrapped ours after
     * we installed it, that restore would silently uninstall *them*. An inert delegating wrapper
     * costs one virtual call per event; unwrapping someone else's instrumentation costs them their
     * data.
     */
    public fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(capture)
        capture.tearDown()
    }
}

/**
 * Set the first time [warnOnceIfATapResolvedToNothing] runs. What it reports is a property of the
 * app's own view hierarchy — which views carry ids, which surfaces are Compose — so it does not
 * change between taps and nothing is gained by logging it on every one.
 *
 * Global rather than per capture instance, and for the same reason as the iOS counterpart: an app
 * that installs, uninstalls and reinstalls this capture must not see the warning repeat.
 *
 * Internal rather than private only so tests can observe that the warning was spent — whether a log
 * line reaches logcat is not observable in a unit test, so the return value is what lets a test pin
 * the thing that is: that it fires at most once.
 */
internal var warnedATapResolvedToNothing = false

/**
 * The first time a tap resolves to nothing, logs once — loud enough that a developer running the app
 * during integration sees it instead of silent nothing. Never logs again.
 *
 * Only [TapResolution.Unresolved] reaches here, never [TapResolution.Ambiguous]: a declined
 * multi-touch is this library refusing to guess, and letting one spend the single warning would
 * print a line blaming an integration that is fine, and then never print the right one.
 */
internal fun warnOnceIfATapResolvedToNothing(): Boolean {
    if (warnedATapResolvedToNothing) return false
    warnedATapResolvedToNothing = true
    Log.i(
        "Autograph",
        "A tap resolved to nothing, so no event was sent for it. installAutographNativeTapCapture " +
            "names the view that actually received the touch, and only ever reports that view's " +
            "resource id — so a view built without an id reports nothing. Other taps that land here " +
            "by design: Compose content (captured by autograph-compose instead), a scroll or a " +
            "cancelled press, a disabled control, and any click that does not travel through touch " +
            "dispatch at all, including keyboard, D-pad and accessibility-service clicks. This is " +
            "expected, not a bug in your integration; see installAutographNativeTapCapture's kdoc.",
    )
    return true
}
