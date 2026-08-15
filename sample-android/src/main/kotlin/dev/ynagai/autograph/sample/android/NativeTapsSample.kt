package dev.ynagai.autograph.sample.android

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.ynagai.autograph.android.isAutographIgnored

/**
 * A plain View/XML screen demonstrating `autograph-android`'s native **tap** capture, and the target
 * the instrumented smoke drives on a real device.
 *
 * Every element here is one of the cases the capture's documented behaviour rests on, so the smoke can
 * assert them against the shipped pipeline rather than a reimplementation of it: an identified button,
 * an unidentified one, a compound row whose id is on the parent, a disabled control, two overlapping
 * views whose z-order disagrees with their child order, nested clickables, a Compose island, a
 * `ListView`, a `RecyclerView`, a view the app excluded with `isAutographIgnored`, a clickable inside
 * an excluded container, and enough height to scroll.
 *
 * `ComponentActivity` rather than `Activity` because the `ComposeView` needs a `ViewTreeLifecycleOwner`.
 * That also makes this a Compose host, so the screen capture deliberately skips it — the Compose
 * content reports its own screen.
 */
public class NativeTapsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Keeps the first row clear of the status bar, which would otherwise swallow taps on it
            // before the app's window sees them at all.
            setPadding(0, 200, 0, 0)
        }

        column.addView(button("Plain button", id = R.id.tap_button))
        // Reports nothing: the capture only ever names a developer-set id.
        column.addView(button("Unidentified button", id = View.NO_ID))
        column.addView(row())
        column.addView(button("Disabled button", id = R.id.tap_disabled).apply { isEnabled = false })
        column.addView(overlapping())
        column.addView(composeIsland())
        column.addView(nestedClickables())
        column.addView(list())
        column.addView(recycler())
        // The spacer goes before the excluded views deliberately. `aScrollReportsNothing` swipes from
        // the bottom of the viewport, and anything that pushes real content down to that line makes
        // that test pass because the view under the finger was excluded rather than because a scroll
        // cancels the press — the same assertion, no longer pinning the same thing. Below the spacer,
        // nothing added here can reach the first screen on any device. Both excluded views are reached
        // by `scrollTo()` in the tests, so their position costs nothing.
        column.addView(TextView(this).apply { text = "scroll me"; height = 2000 })
        column.addView(ignoredButton())
        column.addView(ignoredSection())

        return ScrollView(this).apply {
            id = R.id.tap_scroller
            addView(column)
        }
    }

    private fun button(label: String, id: Int): Button =
        Button(this).apply {
            text = label
            this.id = id
            setOnClickListener { }
        }

    /** The id is on the clickable row; the label under the finger has one of its own and no click. */
    private fun row(): View =
        LinearLayout(this).apply {
            id = R.id.tap_row
            isClickable = true
            setPadding(24, 24, 24, 24)
            setOnClickListener { }
            addView(
                TextView(context).apply {
                    id = R.id.tap_row_label
                    text = "Row with an inner label"
                },
            )
        }

    /** `tap_under` is added first but carries a higher elevation, so Android dispatches to *it*. */
    private fun overlapping(): View =
        FrameLayout(this).apply {
            val params = { FrameLayout.LayoutParams(600, 160, Gravity.START) }
            addView(
                View(context).apply {
                    id = R.id.tap_under
                    isClickable = true
                    elevation = 32f
                    setBackgroundColor(Color.parseColor("#FFCDD2"))
                    setOnClickListener { }
                    layoutParams = params()
                },
            )
            addView(
                View(context).apply {
                    id = R.id.tap_over
                    isClickable = true
                    setBackgroundColor(Color.parseColor("#80C8E6C9"))
                    setOnClickListener { }
                    layoutParams = params()
                },
            )
        }

    /** Compose content inside the View tree: the View pipeline must report nothing for it. */
    private fun composeIsland(): View =
        ComposeView(this).apply {
            id = R.id.tap_compose_host
            setContent {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(ComposeColor(0xFFB3E5FC))
                        .clickable { NativeTapLog.composeClicks.add("compose_island") },
                ) { BasicText("Compose island") }
            }
        }

    private fun nestedClickables(): View =
        LinearLayout(this).apply {
            id = R.id.tap_nested_parent
            isClickable = true
            setPadding(24, 24, 24, 24)
            setOnClickListener { }
            addView(button("Nested child", id = R.id.tap_nested_child))
        }

    /** `AbsListView` presses the list as well as the row, so a row tap reports the list. */
    private fun list(): View =
        ListView(this).apply {
            id = R.id.tap_list
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                listOf("List row 0", "List row 1"),
            )
            setOnItemClickListener { _, _, _, _ -> }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 260)
        }

    /** A `RecyclerView` row is an ordinary pressed view, so it reports its own id. */
    private fun recycler(): View =
        RecyclerView(this).apply {
            id = R.id.tap_recycler
            layoutManager = LinearLayoutManager(context)
            adapter = RowAdapter()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 260)
        }

    /** Excluded by the app: it is clicked, and handles the click, but the capture reports nothing. */
    private fun ignoredButton(): View =
        Button(this).apply {
            text = "Ignored button"
            id = R.id.tap_ignored
            isAutographIgnored = true
            setOnClickListener { NativeTapLog.viewClicks.add("tap_ignored") }
        }

    /** The mark is on the container; the clickable inside it is not marked and is excluded anyway. */
    private fun ignoredSection(): View =
        LinearLayout(this).apply {
            id = R.id.tap_ignored_section
            orientation = LinearLayout.VERTICAL
            isAutographIgnored = true
            addView(
                Button(context).apply {
                    text = "Inside an ignored section"
                    id = R.id.tap_ignored_child
                    setOnClickListener { NativeTapLog.viewClicks.add("tap_ignored_child") }
                },
            )
        }

    private class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                TextView(parent.context).apply {
                    id = R.id.tap_recycler_row
                    isClickable = true
                    setPadding(24, 32, 24, 32)
                },
            )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.label.text = "Recycler row $position"
            holder.label.setOnClickListener { }
        }

        override fun getItemCount(): Int = 2
    }
}
