package dev.ynagai.autograph.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.ComposeUIViewController
import dev.ynagai.autograph.compose.AutographProvider
import platform.UIKit.UIViewController

/**
 * A Compose screen hosted inside the SwiftUI native sample, with **autocapture deliberately off**.
 *
 * This is the exact configuration the Compose/native boundary has to survive: a hybrid app whose
 * Compose side captures nothing and whose native side captures everything. The native pipeline walks
 * the same accessibility tree Compose bridges its semantics into, so without a host registration that
 * is independent of the autocapture flag, it descends into Compose's content and reports it — content
 * whose exclusions (`Modifier.autographIgnore()`) live in Compose-side state the native side cannot
 * see.
 *
 * The button carries a `testTag`, which reaches the bridged tree as an `accessibilityIdentifier` and
 * carries a button trait. That matters for the test to mean anything: if the boundary failed, this
 * button is something the native pipeline *could* name and would report. A button the native side
 * couldn't identify anyway would make the assertion pass for the wrong reason.
 */
public fun HybridViewController(): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { HybridContent() }

@Composable
private fun HybridContent() {
    // No `autocapture` argument: this provider reports nothing on its own.
    AutographProvider(tracker = LoggingTracker()) {
        Column {
            Text("Compose area")
            Button(onClick = {}, modifier = Modifier.testTag("compose_button_in_hybrid")) {
                Text("Compose button")
            }
        }
    }
}
