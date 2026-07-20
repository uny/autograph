package dev.ynagai.autograph.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Fragment fixtures must be public top-level (or public static) classes: the framework recreates
// fragments from instance state via reflection, so a private inner class throws IllegalStateException.
class HarnessFragmentActivity : FragmentActivity()

class FirstHarnessFragment : Fragment()

class SecondHarnessFragment : Fragment()

/**
 * The Android lifecycle test harness for #65 PR-E, stood up ahead of the PR-E logic itself.
 *
 * PR-E auto-emits `Screen Viewed` from `Application.ActivityLifecycleCallbacks` and
 * `FragmentManager.FragmentLifecycleCallbacks`. That is a lifecycle-dependent defect class a plain
 * unit test cannot see (the iOS side proved this: #77/#82/#83/#85 were all invisible to a green
 * suite and only surfaced when real UI was driven). This repo therefore requires the logic to reach
 * a *real* lifecycle harness.
 *
 * These are **harness sentinels**, not PR-E tests: each proves the harness can drive and observe one
 * transition PR-E must get right, using Robolectric's real framework lifecycle dispatch (not mocks).
 * If any regresses, the harness — not PR-E logic — is broken. PR-E's own production tests + mutation
 * (revert-to-red) checks build on top of this.
 *
 * Boundary (documented deliberately): Robolectric is a JVM shadow of Android, not a device. It does
 * NOT cover process death/restore, real windowing, or multiple-resumed-fragment visibility policy;
 * PR-E adds a narrow instrumented smoke for those once it has a real install to exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Match compileSdk; Robolectric 4.16 supports the API 36 framework.
class LifecycleHarnessTest {

    @Test
    fun applicationActivityLifecycleCallbacksAreDispatched() {
        val app = RuntimeEnvironment.getApplication()
        val events = mutableListOf<String>()
        app.registerActivityLifecycleCallbacks(recordingActivityCallbacks(events))

        Robolectric.buildActivity(HarnessFragmentActivity::class.java).setup()

        assertTrue(
            "expected a real onActivityResumed for HarnessFragmentActivity, saw $events",
            events.contains("resumed:HarnessFragmentActivity"),
        )
    }

    @Test
    fun activityLifecycleCallbackOrderIsObservableAcrossTheFullLifecycle() {
        val app = RuntimeEnvironment.getApplication()
        val events = mutableListOf<String>()
        app.registerActivityLifecycleCallbacks(recordingActivityCallbacks(events))

        // create -> start -> resume, then wind it back down the way a screen leaving does.
        Robolectric.buildActivity(HarnessFragmentActivity::class.java)
            .setup()
            .pause()
            .stop()
            .destroy()

        // PR-E emits on resume and removes its scope frame on the way out; the harness must be able to
        // observe that whole ordered sequence, not just a single callback.
        assertEquals(
            listOf(
                "created:HarnessFragmentActivity",
                "started:HarnessFragmentActivity",
                "resumed:HarnessFragmentActivity",
                "paused:HarnessFragmentActivity",
                "stopped:HarnessFragmentActivity",
                "destroyed:HarnessFragmentActivity",
            ),
            events,
        )
    }

    @Test
    fun activityRecreateProducesADistinctInstance() {
        // The config-change proxy: recreate() destroys and rebuilds the Activity. PR-E must not leak a
        // scope frame across it, and the harness must be able to see the old and new instances differ.
        val controller = Robolectric.buildActivity(HarnessFragmentActivity::class.java).setup()
        val original = controller.get()

        val recreated = controller.recreate().get()

        assertNotSame("recreate() must yield a new Activity instance", original, recreated)
    }

    @Test
    fun fragmentManagerLifecycleCallbacksAreDispatched() {
        val fragmentManager = Robolectric.buildActivity(HarnessFragmentActivity::class.java)
            .setup()
            .get()
            .supportFragmentManager
        val events = mutableListOf<String>()
        fragmentManager.registerFragmentLifecycleCallbacks(recordingFragmentCallbacks(events), false)

        fragmentManager.beginTransaction().add(FirstHarnessFragment(), "first").commitNow()

        assertTrue(
            "expected a real onFragmentResumed for FirstHarnessFragment, saw $events",
            events.contains("resumed:FirstHarnessFragment"),
        )
    }

    @Test
    fun fragmentBackStackReplaceAndPopIsObservable() {
        val fragmentManager = Robolectric.buildActivity(HarnessFragmentActivity::class.java)
            .setup()
            .get()
            .supportFragmentManager
        val events = mutableListOf<String>()
        fragmentManager.registerFragmentLifecycleCallbacks(recordingFragmentCallbacks(events), false)

        // add First -> replace with Second (back stack) -> pop back to First. This is the fragment
        // analog of push/pop that PR-E's screen frames must track; the harness must observe both the
        // Second appearing and the First re-appearing on pop.
        fragmentManager.beginTransaction()
            .add(android.R.id.content, FirstHarnessFragment(), "first")
            .commit()
        fragmentManager.executePendingTransactions()

        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SecondHarnessFragment(), "second")
            .addToBackStack(null)
            .commit()
        fragmentManager.executePendingTransactions()

        fragmentManager.popBackStack()
        fragmentManager.executePendingTransactions()

        assertEquals(
            listOf(
                "resumed:FirstHarnessFragment",
                "resumed:SecondHarnessFragment",
                "resumed:FirstHarnessFragment",
            ),
            events.filter { it.startsWith("resumed:") },
        )
    }

    private fun recordingActivityCallbacks(events: MutableList<String>) =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                events += "created:${activity.javaClass.simpleName}"
            }
            override fun onActivityStarted(activity: Activity) {
                events += "started:${activity.javaClass.simpleName}"
            }
            override fun onActivityResumed(activity: Activity) {
                events += "resumed:${activity.javaClass.simpleName}"
            }
            override fun onActivityPaused(activity: Activity) {
                events += "paused:${activity.javaClass.simpleName}"
            }
            override fun onActivityStopped(activity: Activity) {
                events += "stopped:${activity.javaClass.simpleName}"
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                events += "destroyed:${activity.javaClass.simpleName}"
            }
        }

    private fun recordingFragmentCallbacks(events: MutableList<String>) =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                events += "resumed:${f.javaClass.simpleName}"
            }
            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                events += "paused:${f.javaClass.simpleName}"
            }
        }
}
