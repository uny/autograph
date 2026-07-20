@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.AutographInternalApi
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

    // Screens whose next resume is a configuration-change re-creation, not a fresh view. Keyed by class
    // name because the leaving instance and the re-created one are different objects. Emit is skipped
    // for them; the self-previous guard in emitScreenView separately keeps previous_screen clean.
    private val pendingConfigChange = HashSet<String>()

    private class FragmentRegistration(
        val fragmentManager: FragmentManager,
        val callbacks: FragmentManager.FragmentLifecycleCallbacks,
    )

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!active) return
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
        if (activityFrames.containsKey(activity)) return // dedup: a re-resume that never stopped
        val configChange = pendingConfigChange.remove(activity.javaClass.name)
        if (!isCapturableActivity(activity)) return
        val name = activityScreenName(activity) ?: return
        pushAndMaybeEmit(name, configChange) { activityFrames[activity] = it }
    }

    override fun onActivityStopped(activity: Activity) {
        if (!active) return
        if (activity.isChangingConfigurations) pendingConfigChange.add(activity.javaClass.name)
        removeFrame(activityFrames, activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!active) return
        removeFrame(activityFrames, activity) // backstop if stop was skipped
        fragmentRegistrations.remove(activity)?.let {
            it.fragmentManager.unregisterFragmentLifecycleCallbacks(it.callbacks)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private inner class FragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!active) return
            if (fragmentFrames.containsKey(f)) return // dedup
            val configChange = pendingConfigChange.remove(f.javaClass.name)
            if (!isCapturableFragment(f)) return
            val name = fragmentScreenName(f) ?: return
            pushAndMaybeEmit(name, configChange) { fragmentFrames[f] = it }
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
        }
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
        fragmentRegistrations.values.forEach {
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
