package dev.ynagai.autograph.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.zIndex
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.autocaptureScope
import platform.Foundation.NSLog
import platform.UIKit.UIViewController

/**
 * THROWAWAY measurement rig for [#185](https://github.com/uny/autograph/issues/185) — delete with the
 * spike. Reached from `ContentView.swift` behind `-autograph-measure-185`.
 *
 * The question: with `Modifier.autocaptureScope` recording itself during Compose's own pointer
 * dispatch (rather than through the UIKit accessibility bridge, which carries no custom semantics),
 * does an autocaptured iOS tap carry the scope — and does that scope agree with the `target` the
 * accessibility walk independently resolved?
 *
 * Read with:
 * ```
 * xcrun simctl spawn <udid> log stream --style compact --predicate 'eventMessage BEGINSWITH "AX185"'
 * ```
 * (`BEGINSWITH`, not `CONTAINS` — the device name matches unrelated system logs.)
 *
 * Each fixture logs `ORACLE` from its own `onClick` (ground truth for which element Compose routed
 * the pointer to) and `GEOM` from `onGloballyPositioned`; the tracker logs `REPORT` with the target
 * and the full properties. A tap is a **pass** when `REPORT` names the `ORACLE` element and carries
 * that element's ancestry's scope.
 */
public fun Measure185ViewController(): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { Measure185() }

private fun ax(message: String) = NSLog("AX185 $message")

@Composable
private fun Measure185() {
    val tracker = LoggingTracker(
        onTrack = { name, props, target -> ax("REPORT name=$name target=$target props=$props") },
    )
    // No AutographScope / TrackedScreen anywhere: the ambient stack would contribute properties of
    // its own and this rig is measuring exactly one thing — what the element's own ancestry carries.
    AutographProvider(tracker = tracker, autocapture = AutocaptureConfig()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            F1CanonicalNesting()
            F2Coincident()
            F3OverlappingSiblings()
            F4WrapperWithOverhang()
            F5ScopeOnlyVeil()
        }
    }
}

private fun Modifier.geom(tag: String): Modifier = onGloballyPositioned {
    val b = it.boundsInWindow()
    ax("GEOM $tag ${b.left},${b.top} ${b.width}x${b.height}")
}

/**
 * F1 — the canonical case from `autocaptureScope`'s own kdoc: an enclosing scope plus a per-row one,
 * with the row's own scope on the clickable's chain. Pass = `target=f1_row`, scope
 * `{section:for_you, article_id:42}`.
 */
@Composable
private fun F1CanonicalNesting() {
    Column(Modifier.autocaptureScope("section" to "for_you")) {
        Box(
            Modifier
                .testTag("f1_row")
                .autocaptureScope("article_id" to "42")
                .size(280.dp, 44.dp)
                .background(Color(0xFFB3E5FC))
                .geom("f1_row")
                .clickable { ax("ORACLE f1_row") },
        )
    }
}

/**
 * F2 — the coincident-overlay counterexample that closed #68's iOS half, with both layers scoped.
 * Compose routes the pointer to `f2_over`, so pass = `target=f2_over` + `card=over`. The failure this
 * exists to catch is `target=f2_under` + `card=over`: the two sources of truth naming different
 * elements, which is #185's unverified point 2.
 */
@Composable
private fun F2Coincident() {
    Box(Modifier.padding(top = 8.dp)) {
        Box(
            Modifier
                .testTag("f2_under")
                .autocaptureScope("card" to "under")
                .size(280.dp, 44.dp)
                .background(Color(0xFFC8E6C9))
                .geom("f2_under")
                .clickable { ax("ORACLE f2_under") },
        )
        Box(
            Modifier
                .testTag("f2_over")
                .zIndex(1f)
                .autocaptureScope("card" to "over")
                .size(280.dp, 44.dp)
                .geom("f2_over")
                .clickable { ax("ORACLE f2_over") },
        )
    }
}

/**
 * F3 — partially overlapping siblings, the shape #134/#140 measured the accessibility bridge getting
 * wrong: it orders siblings by reading position, so the element *lower on screen* can win the walk's
 * tie-break even though Compose routed the pointer to the one drawn on top. Tapping the overlap
 * should fire `f3_upper`; if the walk answers `f3_lower` while the scope says `zone=upper`, that is
 * the disagreement again — this time with a known cause.
 */
@Composable
private fun F3OverlappingSiblings() {
    Box(Modifier.padding(top = 8.dp).size(280.dp, 80.dp)) {
        Box(
            Modifier
                .testTag("f3_lower")
                .offset(y = 40.dp)
                .autocaptureScope("zone" to "lower")
                .size(280.dp, 40.dp)
                .background(Color(0xFFFFE0B2))
                .geom("f3_lower")
                .clickable { ax("ORACLE f3_lower") },
        )
        Box(
            Modifier
                .testTag("f3_upper")
                .zIndex(1f)
                .autocaptureScope("zone" to "upper")
                .size(280.dp, 60.dp)
                .geom("f3_upper")
                .clickable { ax("ORACLE f3_upper") },
        )
    }
}

/**
 * F5 — the cost of the pointer node existing at all. A `PointerInputModifierNode` is a hit-test
 * target, and by default Compose stops descending to siblings underneath one it has hit. So a
 * *scope-only* element — no `clickable` anywhere on it — could now swallow the tap of a clickable
 * sibling beneath it, turning an observation-only modifier into one that changes routing.
 *
 * `sharePointerInputWithSiblings() = true` is the documented fix, but that is what F2/F3 measured
 * redirecting the pointer to the wrong sibling. Pass = `ORACLE f5_button` still fires.
 */
@Composable
private fun F5ScopeOnlyVeil() {
    Box(Modifier.padding(top = 48.dp)) {
        Box(
            Modifier
                .testTag("f5_button")
                .size(280.dp, 44.dp)
                .background(Color(0xFFB0BEC5))
                .geom("f5_button")
                .clickable { ax("ORACLE f5_button") },
        )
        // No clickable, no background: purely a scope, drawn over the button.
        Box(Modifier.zIndex(1f).autocaptureScope("veil" to "yes").size(280.dp, 44.dp).geom("f5_veil"))
    }
}

/**
 * F4 — a scope on a wrapper whose clickable child is drawn *outside* the wrapper's own bounds, the
 * case `autocaptureScope`'s kdoc calls out as working on Android since #126. Pointer dispatch has to
 * reach an ancestor's node for a pointer the ancestor's own bounds do not contain; whether it does is
 * the load-bearing unknown here. Pass = `target=f4_badge` + `list=main`.
 */
@Composable
private fun F4WrapperWithOverhang() {
    Box(
        Modifier
            .padding(top = 8.dp)
            .autocaptureScope("list" to "main")
            .size(280.dp, 24.dp)
            .background(Color(0xFFD1C4E9))
            .geom("f4_wrapper"),
    ) {
        Box(
            Modifier
                .testTag("f4_badge")
                .offset(y = 24.dp)
                // requiredSize, not size: the wrapper is 24.dp tall and would otherwise clamp the
                // badge to its own height, defeating the overhang this fixture exists to create.
                .requiredSize(120.dp, 40.dp)
                .background(Color(0xFFF8BBD0))
                .geom("f4_badge")
                .clickable { ax("ORACLE f4_badge") },
        )
    }
}
