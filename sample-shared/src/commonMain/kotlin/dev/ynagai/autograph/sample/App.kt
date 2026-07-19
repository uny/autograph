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
import androidx.compose.ui.Modifier
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
    val tracker = remember {
        LoggingTracker(
            onTrack = { props, target ->
                lastTarget = targetOrNoTarget(target)
                // The whole properties object, so a UI test can observe the screen/section/scope an
                // autocaptured tap was attributed with — not just its target.
                lastProps = props.toString()
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
                        DemoScreen(lastTarget, lastProps, screenLog)
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(lastTarget: String, lastProps: String, screenLog: String) {
    Column(
        // systemBarsPadding, not the host relying on `.ignoresSafeArea()`: on iOS this is also
        // what exercises ElementResolver.ios.kt's real-world case, a Compose root that doesn't
        // fill its window — see the resolver's own kdoc for why that used to misattribute taps.
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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

        // Read by sample-iosUITests, which can't inspect Kotlin state directly — see LoggingTracker.
        // target: which element a tap resolved to. props: the full properties (screen/section/scope)
        // it was attributed with. screen views: the ordered log of Screen Viewed events.
        Text("Last event target: $lastTarget", modifier = Modifier.testTag("last_event_label"))
        Text("Last event props: $lastProps", modifier = Modifier.testTag("last_event_props_label"))
        Text("Screen views: $screenLog", modifier = Modifier.testTag("screen_view_log_label"))
    }
}
