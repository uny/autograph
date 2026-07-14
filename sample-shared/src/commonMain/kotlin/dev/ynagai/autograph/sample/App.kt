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
    var lastEvent by remember { mutableStateOf("(none yet)") }
    val tracker = remember { LoggingTracker(onTrack = { target -> lastEvent = target ?: "(no target)" }) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // autocapture = AutocaptureConfig() reports every tap without instrumenting each
            // element — see the README's "Autocapture" section.
            AutographProvider(tracker = tracker, autocapture = AutocaptureConfig()) {
                // AutographScope attaches a property to every event fired inside — see the README's
                // "Scoped context" section. Here the explicitly-tracked events below carry
                // article_id (autocaptured taps fire above this scope and don't — that's the
                // documented boundary).
                AutographScope("article_id" to "42") {
                    DemoScreen(lastEvent)
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(lastEvent: String) {
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

        // Read by sample-iosUITests to assert which element a tap resolved to, since a UI test
        // can't inspect Kotlin state directly — see LoggingTracker's onTrack kdoc.
        Text("Last event target: $lastEvent", modifier = Modifier.testTag("last_event_label"))
    }
}
