package dev.ynagai.autograph.android

import android.view.View
import android.view.ViewGroup

/**
 * What resolving a tap on Android View content produced. Tri-state for the same reason iOS's
 * `NativeHitTestResolution` is: only [Unresolved] means "nothing here could be named", which is the
 * one outcome worth telling a developer about once. [Ambiguous] is this library declining to guess,
 * and spending the single diagnostic on it would print a misleading line.
 */
internal sealed interface TapResolution {
    /** The tap named [identifier], which is a developer-set view id. */
    data class Target(val identifier: String) : TapResolution

    /** Nothing under the finger could be named — see [warnOnceIfATapResolvedToNothing]. */
    data object Unresolved : TapResolution

    /** More than one element was being touched (multi-touch); attributing to one would be a guess. */
    data object Ambiguous : TapResolution
}

/**
 * Resolves a tap to the view that actually handled it — **without any geometry**.
 *
 * The touch dispatch itself marks the view it delivered to as pressed, so reading that state back
 * inherits, by construction, every question a hit-test walk has to answer by imitation: sibling
 * z-ordering, whether a view was enabled, whether a parent (a scrolling container) stole the
 * gesture, and whether the touch was consumed at all. Measured against a geometric walk on the same
 * taps, the walk got three of those wrong — it named the lower sibling of an elevation-reordered
 * pair, it named a disabled button that no click ever reached, and it named an element at the end of
 * a scroll — while this matched what actually fired in every one of those cases.
 *
 * **The root of the pressed subtree, not the deepest pressed view.** `ViewGroup.dispatchSetPressed`
 * propagates the pressed state *down* into children that are not themselves clickable, so a tap on
 * the label inside a clickable row leaves both the row and its label pressed; the deepest one is the
 * label, which handled nothing. Taking the root also makes the answer independent of *where* in the
 * row the finger landed, which the deepest-node rule was not. Nested clickables still resolve to the
 * inner one, because that same method deliberately does not propagate into a clickable child.
 *
 * **Must be called synchronously while the `ACTION_UP` dispatch is still on the stack.** `View`
 * clears its pressed state from a *posted* runnable, so anything that waits for the next main-loop
 * message — including the `post` that would be needed to observe the click itself — sees nothing.
 */
internal fun resolveTapTarget(root: View): TapResolution {
    val pressed = ArrayList<View>(2)
    collectPressedRoots(root, pressed)
    // Zero is the common, healthy case for content this pipeline does not own: Compose islands (their
    // pointer input never touches the View pressed state), a scroll, a disabled control. More than one
    // means several fingers were down on separate elements; `ACTION_POINTER_UP` cannot say which of
    // them lifted, so this declines rather than picking one.
    val view = pressed.singleOrNull() ?: return if (pressed.isEmpty()) {
        TapResolution.Unresolved
    } else {
        TapResolution.Ambiguous
    }
    return view.developerSetIdentifier()?.let(TapResolution::Target) ?: TapResolution.Unresolved
}

/**
 * Collects the shallowest pressed view of each pressed subtree — the roots, never their propagated
 * descendants. Descending stops at a root precisely because everything below it may be pressed only
 * by propagation.
 */
private fun collectPressedRoots(view: View, acc: MutableList<View>) {
    if (view.visibility != View.VISIBLE) return
    if (view.isPressed) {
        acc.add(view)
        return
    }
    if (view is ViewGroup) {
        for (i in view.childCount - 1 downTo 0) {
            collectPressedRoots(view.getChildAt(i), acc)
        }
    }
}

/**
 * The view's resource entry name, or null when it has none that a developer chose.
 *
 * Three ways a view has no such name, and all three are real:
 * - [View.NO_ID] — a view built in code without an id, which is most of them in a hand-rolled layout.
 * - An id from `View.generateViewId()`, which is a valid non-[View.NO_ID] value with **no resource
 *   entry behind it**, so `getResourceEntryName` throws rather than returning null.
 * - An id owned by the `android` package — the ids inside platform layouts, e.g. the `text1` that
 *   every `simple_list_item_1` row in every list in the app shares. Reporting those would produce a
 *   `target` that names nothing in particular and collides across unrelated screens.
 *
 * Displayed text is never consulted, here or anywhere: the identifier is developer-set or absent.
 */
private fun View.developerSetIdentifier(): String? {
    if (id == View.NO_ID) return null
    val resources = resources ?: return null
    val entry = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return null
    val packageName = runCatching { resources.getResourcePackageName(id) }.getOrNull()
    if (packageName == "android") return null
    return entry
}
