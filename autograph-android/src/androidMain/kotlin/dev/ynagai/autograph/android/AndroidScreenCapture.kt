@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import androidx.fragment.app.Fragment
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.AutographInternalApi
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
 * Activity report before the Fragment is seen; old-style `show()`/`hide()` navigation that leaves
 * several fragments `RESUMED` at once reports whichever resumes, without reconciling them; and there
 * is no Android equivalent of iOS's app-bundle filter (every class shares one `ClassLoader`), so a
 * view-bearing library Fragment can report unless excluded via [fragmentScreenName]. Return `null`
 * from [activityScreenName] / [fragmentScreenName] to opt a screen out.
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
 * ## Lifecycle
 *
 * A frame is removed when its screen is **stopped**, not paused: pause also fires for a dialog, a
 * permission prompt, or a partially-covering Activity, none of which mean the screen was left. This
 * matches iOS using `viewDidDisappear:` rather than `viewWillDisappear:`.
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
