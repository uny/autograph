@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeHandle
import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.context.emitScreenView

/**
 * The engine behind [installAutographNativeScreenCapture]: an [Application.ActivityLifecycleCallbacks]
 * that also installs a recursive [FragmentManager.FragmentLifecycleCallbacks] on each Activity, pushes
 * a [ScopeStack] frame and emits a `Screen Viewed` for each capturable screen, and removes the frame
 * when the screen stops. Main-thread-confined (see the install kdoc), so the plain maps need no guard.
 */
internal class AndroidScreenCapture(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
    private val activityScreenName: (Activity) -> String?,
    private val fragmentScreenName: (Fragment) -> String?,
) : Application.ActivityLifecycleCallbacks {

    // active guard: any callback still in flight after tearDown() no-ops, belt-and-suspenders with the
    // explicit unregisters. Frames are keyed by screen identity; weak keys so a screen destroyed without
    // its stop callback (should not happen, but cheap insurance) cannot pin the Activity/Fragment.
    private var active = true
    // Frame maps are weak: their values (ScopeHandle) do not reference the key, so a screen whose stop
    // callback was somehow missed can still be collected. The registration map cannot be — its value
    // holds the FragmentManager, which pins the Activity through its host, so weak keys would never be
    // collected anyway. It is a plain map cleared explicitly on onActivityDestroyed and tearDown (both
    // reliable short of process death, which frees everything regardless).
    private val activityFrames = java.util.WeakHashMap<Activity, ScopeHandle>()
    private val fragmentFrames = java.util.WeakHashMap<Fragment, ScopeHandle>()
    private val fragmentRegistrations = HashMap<Activity, FragmentRegistration>()
    // The "no screen here" frames (see maskScreen in ScopeStack). Kept apart from the screen frames
    // above, not folded into them: onScreenResumed dedups on membership of THOSE maps, so a frame that
    // doubled as both would make every first resume look like a re-resume.
    //
    // The fragment masks live on each FragmentCallbacks instance rather than here, and deliberately:
    // they are the one thing that must be released when the host Activity is destroyed, and this class
    // cannot enumerate that Activity's fragments at that moment (a fragment removed with
    // addToBackStack is no longer in `fm.fragments`). One map per Activity's callbacks is complete by
    // construction. See the sweep in onActivityDestroyed.
    private val activityMasks = java.util.WeakHashMap<Activity, ScopeHandle>()

    // Screens whose next resume is a configuration-change re-creation, not a fresh view. Keyed by class
    // name because the leaving instance and the re-created one are different objects. Emit is skipped
    // for them; the self-previous guard in emitScreenView separately keeps previous_screen clean.
    private val pendingConfigChange = HashSet<String>()

    private class FragmentRegistration(
        val fragmentManager: FragmentManager,
        val callbacks: FragmentCallbacks,
    )

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!active) return
        // Dispatched from inside super.onCreate, so before setContentView — and therefore before any
        // Compose content of this Activity can push a frame of its own. See pushInertMask.
        activityMasks[activity] = pushInertMask()
        if (activity is FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            val callbacks = FragmentCallbacks()
            // recursive = true so a NavHostFragment's / ViewPager2's child FragmentManager is covered.
            fragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
            fragmentRegistrations[activity] = FragmentRegistration(fragmentManager, callbacks)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (!active) return
        // Evaluated once and shared with onScreenResumed: the mask exists for a screen this capture
        // *structurally* declines (a Compose host, a fragment-hosting shell), NOT for one the adopter
        // opted out of by returning null from activityScreenName. That callback is documented as "opt
        // a screen out", and an opt-out that also blanked the screen underneath would be a different,
        // undocumented contract. So the name is never consulted here.
        val capturable = isCapturableActivity(activity)
        if (!capturable) activityMasks[activity]?.let(::activateMask)
        onScreenResumed(
            frames = activityFrames,
            key = activity,
            className = activity.javaClass.name,
            isCapturable = { capturable },
            screenName = { activityScreenName(activity) },
        )
    }

    override fun onActivityStopped(activity: Activity) {
        if (!active) return
        if (activity.isChangingConfigurations) pendingConfigChange.add(activity.javaClass.name)
        removeFrame(activityFrames, activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!active) return
        removeFrame(activityFrames, activity) // backstop if stop was skipped
        removeFrame(activityMasks, activity)
        fragmentRegistrations.remove(activity)?.let {
            // BEFORE unregistering, and that order is load-bearing: this callback is dispatched from
            // Activity.onDestroy(), which FragmentActivity calls via super.onDestroy() *before*
            // mFragments.dispatchDestroy(). So onFragmentDetached / onFragmentDestroyed never fire for
            // the fragments still attached here, and the masks they own would otherwise stay on the
            // ScopeStack for the life of the process — one per fragment per rotation, each of them
            // masking, and each walked by recompute() on every push.
            it.callbacks.releaseMasks()
            it.fragmentManager.unregisterFragmentLifecycleCallbacks(it.callbacks)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (!active) return
        // Symmetric with the activation at resume: a mask may not outlive the moment its surface is
        // the one on display. An Activity that is stopped-but-not-destroyed (reordered to back rather
        // than finished) would otherwise keep masking the Activity the user actually returned to.
        activityMasks[activity]?.let(::deactivateMask)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private inner class FragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
        /** This Activity's fragment masks — see the note at [activityMasks] for why they live here. */
        private val fragmentMasks = java.util.WeakHashMap<Fragment, ScopeHandle>()

        /** Drops every mask this Activity's fragments own. Called before the callbacks unregister. */
        fun releaseMasks() {
            fragmentMasks.values.forEach(scopeStack::remove)
            fragmentMasks.clear()
        }

        override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (!active) return
            fragmentMasks[f] = pushInertMask()
        }

        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // A headless / retained worker fragment (Glide's SupportRequestManagerFragment and its
            // kind) is not a surface at all: it attaches on top of whatever screen is showing and does
            // resume, so leaving its mask in place would blank that screen. It cannot be recognised at
            // attach — the view does not exist yet — so the mask is reserved for every fragment and
            // dropped here. The attach -> started window is synchronous inside the transaction, so no
            // event can be captured while a mask that is about to be dropped is still standing (and it
            // is inert until resume regardless).
            if (f.view == null) removeFrame(fragmentMasks, f)
        }

        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
            if (!active) return
            removeFrame(fragmentMasks, f)
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // Evaluated once and shared with onScreenResumed. Two gates decide whether this surface
            // masks, and both only ever *narrow* the mask — which is what makes them safe: a surface
            // that does not mask resolves exactly as it did before masks existed.
            //
            //  - `capturable`: the mask is for a screen this capture *structurally* declines. Returning
            //    null from fragmentScreenName is documented as "opt a screen out"; an opt-out that also
            //    blanked the screen underneath would be a different, undocumented contract. So the name
            //    is never consulted here.
            //  - `hasFramedAncestor`: a mask asserts "the surface on display names no screen", which
            //    presumes this surface *covers* the screen it hides. A fragment nested inside another
            //    fragment that has a live screen frame cannot cover its own host — it is a section of
            //    that screen, not a replacement for it — so masking there would blank a screen that
            //    names itself perfectly well and is on display. A DialogFragment is the exception and
            //    is exempt: it draws its own window over everything, so it covers its host no matter
            //    whose FragmentManager it was shown through — measured, `show(parent.childFragmentManager)`
            //    otherwise left the dialog reporting its parent's screen.
            val capturable = isCapturableFragment(f)
            if (!capturable && (f is DialogFragment || !hasFramedAncestor(f))) activateMask(f)
            onScreenResumed(
                frames = fragmentFrames,
                key = f,
                className = f.javaClass.name,
                isCapturable = { capturable },
                screenName = { fragmentScreenName(f) },
            )
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // Symmetric with the activation at resume, and the fix for the mask outliving its surface.
            // Pause — not stop — because the shapes that strand a mask never reach stop: a ViewPager2
            // page demoted to STARTED and a fragment `detach`ed both pause only. Un-masking can only
            // reveal frames that were already on the stack, so the worst it can do is leave more of
            // #216 unfixed (a permission prompt pauses an unnamed host, and the screen underneath
            // answers again for that window) — never report a screen that would have been right
            // without masks at all.
            fragmentMasks[f]?.let(this@AndroidScreenCapture::deactivateMask)
        }

        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
            if (!active) return
            // The host Activity is the leaving instance here, so its flag reports the rotation.
            if (f.activity?.isChangingConfigurations == true) pendingConfigChange.add(f.javaClass.name)
            removeFrame(fragmentFrames, f)
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (!active) return
            removeFrame(fragmentFrames, f) // backstop
            removeFrame(fragmentMasks, f) // backstop
        }

        private fun activateMask(f: Fragment) {
            fragmentMasks[f]?.let(this@AndroidScreenCapture::activateMask)
        }
    }

    /**
     * Whether some fragment *containing* [f] currently has a live screen frame.
     *
     * Containment, unlike stack position, is the question a mask actually wants answered: an excluded
     * fragment nested inside a named one is part of that screen, not a surface covering it. Walking
     * `parentFragment` is the one containment signal a `FragmentManager` gives for free — it says
     * nothing about a *sibling* added into another container of the same manager, which is why a
     * non-covering sibling is a documented residual rather than something this catches.
     */
    private fun hasFramedAncestor(f: Fragment): Boolean {
        var parent = f.parentFragment
        while (parent != null) {
            if (fragmentFrames.containsKey(parent)) return true
            parent = parent.parentFragment
        }
        return false
    }

    /**
     * Reserves a mask's **position** in the stack without masking yet.
     *
     * Position is the whole reason this is a separate step. The mask has to land BELOW anything the
     * surface's own content pushes, or a Compose host whose content *does* declare a `TrackedScreen`
     * would lose it. `AbstractComposeView` creates its composition from `onAttachedToWindow`, and the
     * fragment's view is attached to its container **before `onFragmentViewCreated`** — measured — so
     * every hook from `onFragmentViewCreated` onwards is potentially too late. `onFragmentAttached`
     * runs before the view exists at all, which makes it the one hook that cannot be too late under
     * any timing. (`ComposeHostMaskTest` pins that a declared screen wins, but cannot pin the *hook*:
     * under Robolectric the composition is deferred to the looper and lands after every fragment
     * callback either way. Attach is chosen because it is unconditionally safe, not because the test
     * would catch moving it.)
     *
     * Masking from that moment would be wrong for the opposite reason: an attached surface is not
     * necessarily the visible one. A `ViewPager2` page cached by `offscreenPageLimit` attaches while a
     * *different* page is resumed, and a mask live from attach then reports no screen at all —
     * permanently, and for a screen that names itself perfectly well. Measured: an off-screen page
     * turns `screen` from `DetailFragment` into `null`.
     *
     * So the frame is pushed inert and switched on by [activateMask] at resume, the moment the surface
     * becomes the one on display. `ScopeStack.maskScreen` flips it in place, keeping its position, so
     * the switch costs nothing in ordering.
     */
    private fun pushInertMask(): ScopeHandle = scopeStack.push()

    /** Switches a reserved mask on, in place — see [pushInertMask]. */
    private fun activateMask(handle: ScopeHandle) {
        scopeStack.maskScreen(handle)
    }

    /**
     * Switches a mask back off, in place, keeping its reserved position for the next resume.
     *
     * Off, not removed: the position was reserved at attach precisely because no later hook is early
     * enough (see [pushInertMask]), and a surface that pauses can come back — a pager page scrolled
     * to and back, a fragment `detach`ed and `attach`ed — without ever passing through attach again.
     */
    private fun deactivateMask(handle: ScopeHandle) {
        scopeStack.unmaskScreen(handle)
    }

    /**
     * Handles one screen reaching `RESUMED`, for both Activities and Fragments: pushes its frame and
     * emits, unless this resume is a configuration-change re-creation or a screen that never left.
     *
     * **A screen that already has a frame is not always a no-op.** Two different situations reach a
     * resume with a frame still standing, and the screen history is what tells them apart:
     * - Nothing was viewed in between — a dialog, a permission prompt, a partially-covering Activity.
     *   This is one continuous view of one screen (frames are removed on stop, not pause, exactly so
     *   that it stays one), so re-emitting would be a duplicate. Early return, as before.
     * - Another screen *was* viewed since. Then this screen was superseded while still `RESUMED` and
     *   never stopped — a `ViewPager2`/`FragmentStateAdapter` page moved down to `STARTED`, which
     *   calls `onPause` but never `onStop`. Its frame is still on the stack, but *buried* under the
     *   frame of the page that superseded it, and screen resolves by insertion order (see
     *   `ScopeStack.recompute`), so leaving it there attributes every tap on this page to the page
     *   the user just left. The frame is moved back on top and the return is reported.
     *
     * Re-pushing is safe for these frames specifically: native frames carry no `parent` link, so
     * nothing else refers to the handle whose identity changes.
     *
     * The discriminator is the screen *name*, not the instance, so two pages that produce the same
     * name (the default `fragmentScreenName` on a pager of one Fragment class) take the first branch
     * and stay silent. That is deliberate: nothing observable in the event stream distinguishes them.
     */
    private fun <K> onScreenResumed(
        frames: MutableMap<K, ScopeHandle>,
        key: K,
        className: String,
        isCapturable: () -> Boolean,
        screenName: () -> String?,
    ) {
        val existing = frames[key]
        // Only a genuinely fresh resume consumes the marker; a re-resume of a still-framed screen is
        // not the re-creation the marker was left for.
        val configChange = existing == null && pendingConfigChange.remove(className)
        if (!isCapturable()) return
        val name = screenName() ?: return
        if (existing != null) {
            if (scopeStack.screenHistory.lastScreen == name) return
            frames.remove(key)
            scopeStack.remove(existing)
        }
        pushAndMaybeEmit(name, configChange) { frames[key] = it }
    }

    /**
     * Pushes a screen frame (so taps carry the screen even if the emit throws or is suppressed) and
     * emits unless this resume is a configuration-change re-creation. [store] records the handle in the
     * right map. Mirrors the iOS push-before-emit ordering.
     */
    private inline fun pushAndMaybeEmit(name: String, configChange: Boolean, store: (ScopeHandle) -> Unit) {
        store(scopeStack.push(screen = name))
        if (!configChange) {
            try {
                scopeStack.emitScreenView(tracker, name)
            } catch (_: Throwable) {
                // A throwing tracker must never crash a lifecycle callback; the frame stays regardless.
            }
        }
    }

    private fun <K> removeFrame(frames: MutableMap<K, ScopeHandle>, key: K) {
        frames.remove(key)?.let(scopeStack::remove)
    }

    /** Removes every frame this capture pushed and stops it reporting. Idempotent. */
    fun tearDown() {
        active = false
        activityFrames.values.forEach(scopeStack::remove)
        activityFrames.clear()
        fragmentFrames.values.forEach(scopeStack::remove)
        fragmentFrames.clear()
        activityMasks.values.forEach(scopeStack::remove)
        activityMasks.clear()
        fragmentRegistrations.values.forEach {
            it.callbacks.releaseMasks()
            it.fragmentManager.unregisterFragmentLifecycleCallbacks(it.callbacks)
        }
        fragmentRegistrations.clear()
        pendingConfigChange.clear()
    }

    // --- Static capturability filter (mirrors iOS isCapturableScreen; all decidable at resume) -------

    private fun isCapturableActivity(activity: Activity): Boolean {
        if (contentHostsComposeView(activity)) return false
        // A single-Activity app's Fragments are the screens; the Activity hosting them is a shell.
        if (activity is FragmentActivity &&
            activity.supportFragmentManager.fragments.any { it.view != null }
        ) {
            return false
        }
        return true
    }

    private fun isCapturableFragment(fragment: Fragment): Boolean {
        val view = fragment.view ?: return false // headless / retained worker fragment
        if (isNavHostFragment(fragment)) return false // a container, not a screen
        return !viewSubtreeHostsComposeView(view)
    }

    private fun contentHostsComposeView(activity: Activity): Boolean {
        val content = activity.findViewById<View>(android.R.id.content) ?: return false
        return viewSubtreeHostsComposeView(content)
    }

    private fun viewSubtreeHostsComposeView(root: View): Boolean {
        val composeClass = abstractComposeViewClass ?: return false
        return viewSubtreeContains(root, composeClass)
    }

    private fun viewSubtreeContains(root: View, klass: Class<*>): Boolean {
        if (klass.isInstance(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (viewSubtreeContains(root.getChildAt(i), klass)) return true
            }
        }
        return false
    }

    private fun isNavHostFragment(fragment: Fragment): Boolean =
        navHostFragmentClass?.isInstance(fragment) == true

    private companion object {
        // Resolved reflectively, once, so this module compiles and runs without Compose / navigation on
        // the classpath — a plain View/Fragment app depends on neither. Null when the class is absent
        // (nothing to exclude), which is exactly the right answer for such an app.
        private val abstractComposeViewClass: Class<*>? =
            runCatching { Class.forName("androidx.compose.ui.platform.AbstractComposeView") }.getOrNull()
        private val navHostFragmentClass: Class<*>? =
            runCatching { Class.forName("androidx.navigation.fragment.NavHostFragment") }.getOrNull()
    }
}
