package dev.ynagai.autograph.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ynagai.autograph.compose.AutocaptureConfig
import dev.ynagai.autograph.compose.AutographProvider
import dev.ynagai.autograph.compose.AutographScope
import dev.ynagai.autograph.compose.TrackedScreen
import dev.ynagai.autograph.compose.autographIgnore
import dev.ynagai.autograph.compose.trackClick
import dev.ynagai.autograph.compose.trackImpression

/**
 * Runs every README Quick Start / Autocapture snippet for real, against a [LoggingTracker] that
 * prints each event (`Log.d` on Android — see [sampleLog]). Check the platform log to see events
 * as you tap.
 */
@Composable
public fun App() {
    var lastTarget by remember { mutableStateOf(noEventYet) }
    var lastProps by remember { mutableStateOf(noEventYet) }
    var screenLog by remember { mutableStateOf(noEventYet) }
    var trackLog by remember { mutableStateOf(noEventYet) }
    val tracker = remember {
        LoggingTracker(
            onTrack = { name, props, target ->
                lastTarget = targetOrNoTarget(target)
                // The whole properties object, so a UI test can observe the screen/section/scope an
                // autocaptured tap was attributed with — not just its target.
                lastProps = props.toString()
                trackLog = appendTrackLog(trackLog, name, target)
            },
            onScreen = { name, props ->
                screenLog = appendScreenLog(screenLog, name, props.reservedOrNone("previous_screen"))
            },
        )
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // autocapture = AutocaptureConfig() reports every tap without instrumenting each
            // element — see the README's "Autocapture" section.
            AutographProvider(tracker = tracker, autocapture = AutocaptureConfig()) {
                // AutographScope attaches a property to every event fired inside — see the README's
                // "Scoped context" section. Everything below carries article_id: the explicitly
                // tracked events lexically, and autocaptured taps via the ambient scope stack this
                // mirrors into. One scope is mounted at a time here, which is the shape that
                // attributes exactly (see the README on why per-list-row scopes do not).
                AutographScope("article_id" to "42") {
                    // TrackedScreen fires a `Screen Viewed` and mirrors screen+section into that same
                    // ambient stack, so every autocaptured tap below also carries screen=Sample and
                    // section=Main. The section is screen-wide (a tab/variant label), not a region.
                    TrackedScreen("Sample", section = "Main") {
                        DemoScreen(lastTarget, lastProps, screenLog, trackLog)
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(lastTarget: String, lastProps: String, screenLog: String, trackLog: String) {
    Column(
        // systemBarsPadding, not the host relying on `.ignoresSafeArea()`: on iOS this is also
        // what exercises ElementResolver.ios.kt's real-world case, a Compose root that doesn't
        // fill its window — see the resolver's own kdoc for why that used to misattribute taps.
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        // 12.dp, not 16: every fixture below has to stay hittable by `XCUIElement.tap()`, and this
        // Column does not scroll, so the screen's height is the budget for adding one. The four
        // observation labels at the bottom are already past it and are only ever read, never tapped.
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Autograph sample", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every element below is captured automatically by AutocaptureConfig — no " +
                "Modifier.trackClick needed. Watch the platform log as you tap.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Autocapture, identified by testTag alone.
        Button(onClick = {}, modifier = Modifier.testTag("plain_button")) {
            Text("Autocaptured (testTag = plain_button)")
        }

        // A clickable nested inside another clickable: autocapture must attribute a tap to
        // whichever one was actually tapped, not always the outer ancestor.
        Box(
            modifier = Modifier
                .testTag("outer_container")
                .fillMaxWidth()
                .height(96.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable {},
        ) {
            Box(
                modifier = Modifier
                    .testTag("inner_button")
                    .padding(20.dp)
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {},
            )
        }

        // Modifier.trackImpression fires once, the first time this becomes visible.
        Card(modifier = Modifier.trackImpression("Recipe Viewed", target = "recipe_card")) {
            Text("Recipe card (Modifier.trackImpression)", modifier = Modifier.padding(16.dp))
        }

        // Modifier.trackClick is explicit instrumentation — autocapture never double-reports it.
        // A plain clickable Box, not a Button: Button applies its own internal onClick-driven
        // clickable, and stacking a second .trackClick()-owned clickable via `modifier` on top of
        // it means only one of the two ever sees the tap — the outer (trackClick's) one silently
        // never fires. Mirrors the README's own `Text("Save", Modifier.trackClick(...) { save() })`
        // snippet.
        Box(
            modifier = Modifier
                .testTag("explicit_tracked_button")
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.primary)
                .trackClick("Recipe Saved", target = "explicit_tracked_button") {},
        ) {
            Text(
                "Explicitly tracked (Modifier.trackClick)",
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(16.dp),
            )
        }

        // The same explicit instrumentation on an element SHORTER than the minimum touch target.
        // Deliberately a plain Text at its natural height: iOS resolves "already instrumented" by
        // comparing the claim's boundsInWindow() against the accessibility frame, and Compose
        // expands the latter to the minimum touch target for an element this small — so this is the
        // shape that regressed in #151 while the 56.dp box above kept passing. It is also the shape
        // the README's own quick start uses.
        Text(
            "Explicitly tracked, small (Modifier.trackClick)",
            modifier = Modifier
                .testTag("explicit_tracked_small")
                .trackClick("Recipe Saved", target = "explicit_tracked_small") {},
        )

        // A trackImpression element that is not clickable itself, inside a clickable that IS. The
        // outer is deliberately NOT instrumented: autocapture owns its taps and must keep reporting
        // them. iOS cannot read the marker off the ancestry and used to consult a registered rect
        // instead, which the inner element's own frame was indistinguishable from — so the outer's
        // real tap silently vanished (#153, #158). trackImpression now registers nothing at all.
        //
        // The inner fillMaxSize()es deliberately: coincident bounds are the shape that failed
        // whichever way the old rect match was qualified, so it is the stronger fixture of the two
        // geometries — a centred sub-minimum inner (#153's own) only failed against pre-#155 code.
        Box(
            modifier = Modifier
                .testTag("impression_inner_host")
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Impression filling a plain 48dp clickable",
                modifier = Modifier
                    .fillMaxSize()
                    .trackImpression("Recipe Viewed", target = "impression_inner"),
            )
        }

        // The same explicit instrumentation as explicit_tracked_small, under a scale transform.
        // Compose qualifies the touch target on the MEASURED size and then draws the result through
        // the transform, so the accessibility frame is neither the drawn rect nor the drawn rect
        // expanded to the plain minimum — and the claim, being boundsInWindow(), is already scaled.
        // The element was reported twice until the claim carried its measured size too (#159).
        Text(
            "Scaled trackClick",
            modifier = Modifier
                .testTag("scaled_tracked_small")
                .scale(0.5f)
                .trackClick("Recipe Saved", target = "scaled_tracked_small") {},
        )

        // Modifier.autographIgnore excludes a subtree from autocapture entirely.
        Box(
            modifier = Modifier
                .testTag("ignored_button")
                .fillMaxWidth()
                .height(56.dp)
                .background(Color.Gray)
                .autographIgnore()
                .clickable {},
        ) {
            Text(
                "Not captured (Modifier.autographIgnore)",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }

        // A disabled clickable swallows the tap and fires nothing, so autocapture reports nothing —
        // not a click that never happened. It still takes the hit rather than falling through to
        // whatever is underneath, which never received the tap either. See #128 (Android) and #134
        // (iOS, where this depends on Compose Multiplatform bridging the element as
        // `UIAccessibilityTraitNotEnabled`, something only an on-device test can establish).
        Box(
            modifier = Modifier
                .testTag("disabled_button")
                .fillMaxWidth()
                .height(56.dp)
                .background(Color.LightGray)
                .clickable(enabled = false) {},
        ) {
            Text(
                "Not captured (clickable(enabled = false))",
                modifier = Modifier.padding(16.dp),
            )
        }

        // Read by sample-iosUITests, which can't inspect Kotlin state directly — see LoggingTracker.
        // target: which element a tap resolved to. props: the full properties (screen/section/scope)
        // it was attributed with. screen views: the ordered log of Screen Viewed events.
        Text("Last event target: $lastTarget", modifier = Modifier.testTag("last_event_label"))
        Text("Last event props: $lastProps", modifier = Modifier.testTag("last_event_props_label"))
        Text("Screen views: $screenLog", modifier = Modifier.testTag("screen_view_log_label"))
        // The ordered `name:target` log — the only channel that distinguishes one explicit
        // event from an explicit event plus an autocaptured duplicate of it (#151).
        Text("Tracks: $trackLog", modifier = Modifier.testTag("track_log_label"))
    }
}
