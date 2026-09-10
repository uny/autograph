@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import androidx.fragment.app.Fragment
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeStack

/**
 * Starts reporting native **Android** screen transitions: on every Activity or Fragment that comes to
 * the foreground and names a screen, a `Screen Viewed` event is emitted via [tracker] and a screen
 * frame is pushed onto [scopeStack] so autocaptured events on that screen carry it; the frame is
 * removed when the screen is stopped. This is the Android counterpart of the iOS UIKit
 * `viewDidAppear:` capture, sharing the same [ScopeStack]/`ScreenHistory` and so the same
 * `previous_screen` chain across a Compose↔native transition.
 *
 * Opt-in, like the Compose autocapture: nothing happens until an app calls this. Pass the **same**
 * [scopeStack] handed to `AutographProvider` in a hybrid app — one stack is what lets a native screen
 * become the `previous_screen` of the next Compose screen.
 *
 * ## What this captures, precisely — and what it does not
 *
 * Both Activities (`Application.ActivityLifecycleCallbacks`) and Fragments
 * (`FragmentManager.FragmentLifecycleCallbacks`, registered recursively so child fragment managers —
 * a `NavHostFragment`'s destinations, a `ViewPager2`'s pages — are seen). A screen is reported when
 * one comes to `RESUMED` and is not filtered out below.
 *
 * The filter is **static** — everything it needs is decidable at resume, mirroring iOS's
 * `isCapturableScreen`. It deliberately does **not** try to reconcile several simultaneously-visible
 * screens after the fact.
 *
 * A screen filtered out **by this filter** is **masked**, not merely skipped (see
 * `ScopeStack.maskScreen`). While such a surface is the one on display it clears the ambient screen,
 * so events captured on it carry no screen rather than the screen it covered — which is what they
 * would otherwise inherit whenever the screen underneath was only paused, never stopped (a fragment
 * `add`ed on top, a dialog fragment). Content that names a screen for itself — a Compose
 * `TrackedScreen` inside an excluded Compose host — is pushed above the mask and still wins.
 *
 * Masking is deliberately **narrower** than the filter, because a mask asserts something the filter
 * does not: that this surface *covers* the screen it hides. Three cases are filtered out but never
 * masked. A surface opted out with a `null` [activityScreenName] / [fragmentScreenName] is skipped
 * exactly as before — opting a library or consent-SDK fragment out of reporting must not also blank
 * the screen it sits on. A headless fragment (`view == null`, a retained worker like Glide's) is not
 * a surface at all. And an excluded fragment **nested inside** one that names a screen is part of
 * that screen, not a replacement for it — except a `DialogFragment`, which draws its own window and
 * so covers its host whichever `FragmentManager` showed it.
 * - **Compose hosts are skipped.** An Activity or Fragment whose view subtree contains an
 *   `AbstractComposeView` renders Compose content, which reports its own `Screen Viewed` through
 *   `TrackedScreen` / `NavController.TrackScreenViews`; capturing the Activity too would double-count
 *   and, worse, attribute a coarse Activity-class name into the `previous_screen` chain. The check is
 *   **reflective** so this module stays Compose-free.
 * - **Fragment-hosting Activities are skipped.** In a single-Activity app the Fragments are the
 *   screens and the Activity is a shell; reporting both would double-count. An Activity that, at
 *   resume, already has an added Fragment with a view is treated as a host, not a screen.
 * - **Container fragments are skipped** — a `NavHostFragment` hosts destinations that are themselves
 *   captured; it is not itself a screen. (Checked reflectively; navigation-fragment is not a
 *   dependency.)
 * - **Headless fragments are skipped** (`view == null`) — retained worker fragments like Glide's
 *   `SupportRequestManagerFragment` are not screens.
 *
 * Known limits, documented rather than silently mis-attributed (as iOS documents embedded-child /
 * split-pane): a Fragment attached *after* its host Activity has resumed can momentarily let the
 * Activity report before the Fragment is seen; old-style `show()`/`hide()` navigation leaves several
 * fragments `RESUMED` at once and changes only their hidden state, which
 * `FragmentManager.FragmentLifecycleCallbacks` has no callback for (only `Fragment.onHiddenChanged`
 * does, on the fragment itself), so a re-shown fragment cannot be observed from here at all and the
 * fragments left `RESUMED` all keep answering — the innermost-mounted one wins (a legacy
 * `FragmentPagerAdapter` in `BEHAVIOR_SET_USER_VISIBLE_HINT` mode has the same shape and the same
 * limit; its `BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT` mode, like `ViewPager2`, is covered); and there
 * is no Android equivalent of iOS's app-bundle filter (every class shares one `ClassLoader`), so a
 * view-bearing library Fragment can report unless excluded via [fragmentScreenName]. Return `null`
 * from [activityScreenName] / [fragmentScreenName] to opt a screen out.
 *
 * Three limits belong specifically to the mask, and all three are one-sided — they leave a screen
 * *absent*, or leave part of the stale-screen problem unfixed; none reports a screen that would have
 * been wrong without masking. `show()`/`hide()` gives no callback, so an excluded fragment hidden
 * that way keeps masking until it is detached or its Activity is destroyed. An excluded fragment
 * added as a *sibling* into another container of the same `FragmentManager` — an embedded mini-player
 * or banner rather than a cover — masks the screen it sits beside; only nesting (`parentFragment`) is
 * a containment signal a `FragmentManager` gives, and a sibling has none. And a `DialogFragment` that
 * builds its content in `onCreateDialog()` rather than `onCreateView()` has a null `Fragment.view`,
 * is indistinguishable here from a retained worker fragment, and so is skipped rather than masked —
 * measured, such a dialog reports the screen behind it.
 *
 * A fourth is not the mask's: an excluded fragment added directly to a *capturable Activity* — that
 * Activity is a screen, and the fragment cannot tell that it covers it — masks that Activity's
 * screen. It is the Activity-level twin of the sibling case above.
 *
 * ## Rotation
 *
 * A configuration change (rotation) destroys and re-creates the Activity. The self-previous guard in
 * `emitScreenView` already keeps a rotation from polluting `previous_screen` (the re-created screen's
 * previous would be itself, which is dropped), and this additionally **suppresses the duplicate
 * `Screen Viewed`**: an Activity leaving with `isChangingConfigurations` marks its re-creation, and
 * the re-created instance pushes its frame without re-emitting. A genuine process-death restore is
 * *not* a config change, so its screen view is still reported.
 *
 * ## Lifecycle: what a surface says, and whether it is the one saying it
 *
 * Each surface owns **one** frame, reserved (empty and inert) the moment it is created or attached
 * and dropped when it is destroyed or detached. Between those, two separate switches decide what it
 * contributes, because two different questions are being asked and the same signal cannot answer
 * both.
 *
 * **Attribution** — is this the surface on display? — follows resume and pause. A frame that is not
 * selected contributes nothing, as if it were off the stack, but keeps its position so the surface
 * can come back without being rebuilt. Position alone cannot answer this: a host that keeps several
 * surfaces mounted and merely demotes the off-screen ones (a `ViewPager2` caching neighbouring pages,
 * which moves a page down to `STARTED` — `onPause`, never `onStop`) leaves the demoted frame exactly
 * where it was, and the innermost-wins rule then names the page the user just left.
 *
 * **Reporting** — has a `Screen Viewed` already been sent for what is showing? — does *not* follow
 * pause, which is what keeps a dialog, a permission prompt or a partially-covering Activity from
 * producing a duplicate on return, exactly as iOS uses `viewDidDisappear:` rather than
 * `viewWillDisappear:`. A view of a screen ends when the surface **stops**, when it is demoted while
 * its host is still on display (the pager swipe), or when another screen is reported while it is off
 * display (the translucent Activity that never stops the one beneath).
 *
 * One case is described by no callback at all and is settled a beat later: a host can change which
 * surface is selected **while it is paused** — a pager page swapped behind a permission prompt — and
 * neither page pauses or resumes, because both are already `STARTED`. Which of them the host came
 * back to is only readable once its resume dispatch has finished, so that one check is posted to the
 * main looper. Nothing else is deferred: attribution and reporting are both applied in the callback
 * they belong to.
 *
 * ## Only transitions after install are seen
 *
 * There is no public API to enumerate already-resumed Activities, so a screen already on display when
 * this is called is not reported until its next transition. Install from `Application.onCreate()` —
 * no Activity exists yet, so nothing is missed.
 *
 * ## Threading
 *
 * Main thread only, to install, to [AutographNativeScreenCapture.uninstall], and throughout — the
 * lifecycle callbacks are delivered on the main thread and it touches [ScopeStack] throughout.
 *
 * Keep the returned handle: it is the only way to [AutographNativeScreenCapture.uninstall].
 */
@AutographInternalApi
public fun installAutographNativeScreenCapture(
    application: Application,
    tracker: Tracker,
    scopeStack: ScopeStack,
    activityScreenName: (Activity) -> String? = { it.javaClass.name },
    fragmentScreenName: (Fragment) -> String? = { it.javaClass.name },
): AutographNativeScreenCapture {
    val capture = AndroidScreenCapture(tracker, scopeStack, activityScreenName, fragmentScreenName)
    application.registerActivityLifecycleCallbacks(capture)
    return AutographNativeScreenCapture(application, capture)
}

/**
 * A running native screen capture. Created by [installAutographNativeScreenCapture]; keep it to
 * [uninstall].
 */
@AutographInternalApi
public class AutographNativeScreenCapture internal constructor(
    private val application: Application,
    private val capture: AndroidScreenCapture,
) {

    /**
     * Stops this capture: unregisters its lifecycle callbacks (both the Activity ones and every
     * per-Activity Fragment callback it registered) and removes the screen frames it pushed. Safe to
     * call more than once. Unlike the iOS swizzle, the Android callbacks can genuinely be removed, so
     * this leaves no inert hook behind.
     */
    public fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(capture)
        capture.tearDown()
    }
}
