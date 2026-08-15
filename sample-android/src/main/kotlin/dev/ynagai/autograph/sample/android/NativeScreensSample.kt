@file:OptIn(AutographInternalApi::class)

package dev.ynagai.autograph.sample.android

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.android.installAutographNativeScreenCapture
import dev.ynagai.autograph.android.installAutographNativeTapCapture
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.context.ScopeStack
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A non-Compose Activity/Fragment flow that demonstrates `autograph-android`'s native screen capture,
 * and the target the instrumented smoke drives on a real device. Deliberately plain Views — this is
 * exactly the "no Compose" case the swizzle-equivalent lifecycle capture exists to serve.
 *
 * [NativeSampleApplication] installs the capture at process start (the recommended spot), pointing it
 * at [NativeScreenLog]. [NativeScreensActivity] hosts fragments from `onCreate`, so it is an excluded
 * shell and its `ScreenAFragment` / `ScreenBFragment` are the reported screens.
 */
public class NativeSampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // One stack for both captures, as the install kdocs ask for: it is what lets a native screen
        // scope the taps made on it, and what keeps `previous_screen` continuous across Compose↔native.
        val scopeStack = ScopeStack()
        installAutographNativeScreenCapture(
            application = this,
            tracker = NativeSampleTracker,
            scopeStack = scopeStack,
            fragmentScreenName = { it.javaClass.simpleName },
        )
        installAutographNativeTapCapture(
            application = this,
            tracker = NativeSampleTracker,
            scopeStack = scopeStack,
        )
    }
}

/** The ordered `name:previous_screen` log of captured screen views, observable by the UI and the test. */
public object NativeScreenLog {
    public val entries: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    @Volatile
    internal var onChange: (() -> Unit)? = null

    internal fun record(entry: String) {
        entries.add(entry)
        onChange?.invoke()
    }

    /** The `|`-delimited log, matching the sample-ios label shape. */
    public fun text(): String = if (entries.isEmpty()) "(none yet)" else entries.joinToString("|")
}

/** The ordered log of captured tap targets, observable by the instrumented smoke. */
public object NativeTapLog {
    public val targets: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /**
     * Clicks the Compose island handled itself. Its only purpose is to keep the smoke's "the View
     * pipeline reports nothing for Compose content" assertion honest: without a witness that the tap
     * reached the island at all, that assertion would also pass if the tap had missed it entirely.
     */
    public val composeClicks: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /**
     * Clicks a `View`'s own `OnClickListener` handled, which the capture never sees. Same job as
     * [composeClicks], for the opt-out cases: "an excluded view reports nothing" would pass just as
     * well if the touch had missed the view, so those assertions are paired with a witness that it
     * did not.
     */
    public val viewClicks: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
}

/**
 * Records each `Screen Viewed` as "name:previous_screen" into [NativeScreenLog], and each captured
 * tap's `target` into [NativeTapLog].
 */
internal object NativeSampleTracker : Tracker {
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        NativeTapLog.targets.add(target ?: "(no target)")
    }
    override fun screen(name: String, properties: Map<String, JsonElement>) {
        val previous = (properties.asJsonObject()["previous_screen"] as? JsonPrimitive)?.content ?: "(none)"
        NativeScreenLog.record("$name:$previous")
    }
    override fun identify(userId: String, traits: Map<String, JsonElement>): Unit = Unit
}

/** Single-Activity host: a screen-view log on top, a fragment container below. */
public class NativeScreensActivity : FragmentActivity() {

    internal var containerId: Int = View.NO_ID
        private set

    private var logView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val log = TextView(this).apply { contentDescription = "screen_log" }
        logView = log
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        containerId = container.id
        root.addView(log)
        root.addView(container)
        setContentView(root)

        if (savedInstanceState == null) {
            // commitNow so ScreenAFragment (with its view) is present at this Activity's resume, which
            // is what makes the Activity register as a fragment host and stay excluded.
            supportFragmentManager.beginTransaction()
                .add(container.id, ScreenAFragment())
                .commitNow()
        }
    }

    // Register in onStart / clear in onStop, not onCreate/onDestroy: across a rotation the old
    // instance's onStop runs before the new instance's onStart, so the new registration is never
    // clobbered by the old teardown (the reverse order onCreate/onDestroy would cause).
    override fun onStart() {
        super.onStart()
        refreshLog()
        NativeScreenLog.onChange = { runOnUiThread { refreshLog() } }
    }

    override fun onStop() {
        NativeScreenLog.onChange = null
        super.onStop()
    }

    private fun refreshLog() {
        logView?.text = NativeScreenLog.text()
    }
}

/** A screen with a "Next" button that pushes [ScreenBFragment]. */
public class ScreenAFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = screenView("Screen A", "Next") {
        val hostId = (requireActivity() as NativeScreensActivity).containerId
        parentFragmentManager.beginTransaction()
            .replace(hostId, ScreenBFragment())
            .addToBackStack(null)
            .commit()
    }
}

/** A screen reached by pushing from A; the device back button pops it, re-showing A. */
public class ScreenBFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = screenView("Screen B", button = null, onClick = null)
}

private fun Fragment.screenView(title: String, button: String?, onClick: (() -> Unit)?): View {
    val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
    layout.addView(TextView(requireContext()).apply { text = title })
    if (button != null) {
        layout.addView(
            Button(requireContext()).apply {
                text = button
                setOnClickListener { onClick?.invoke() }
            },
        )
    }
    return layout
}
