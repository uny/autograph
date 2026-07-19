package dev.ynagai.autograph.sample

import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.uikit.AutographInternalApi
import dev.ynagai.autograph.uikit.AutographNativeTapCapture
import dev.ynagai.autograph.uikit.installAutographNativeTapCapture

/**
 * Installs native (UIKit/SwiftUI) tap capture for the sample's SwiftUI-only screen, reporting each
 * resolved tap through [onTap] as its `target` and its full `properties` (JSON).
 *
 * This is the counterpart to `MainViewController()`: that entry point hosts the Compose sample and
 * exercises `autograph-compose`'s pipeline, this one exercises `autograph-uikit`'s. The SwiftUI screen
 * it serves (see `ContentView.swift`) contains no Compose at all, so every tap it reports came through
 * the accessibility-tree walk aimed at a real SwiftUI hierarchy — the shape that unit tests cannot
 * reproduce and that has hidden three separate defects (#77, #82, #83).
 *
 * [onTap] is what the XCUITest suite reads: it can't inspect Kotlin state, so the sample surfaces
 * every reported tap on-screen. Mirrors `LoggingTracker`'s role in the Compose sample.
 *
 * A fresh [ScopeStack] rather than a shared one, because this screen renders no Compose to share it
 * with. A hybrid app would pass the same stack it gives `AutographProvider`. A screen frame is pushed
 * onto it so native taps demonstrably carry `screen`/`section` through the shared
 * [dev.ynagai.autograph.context.AmbientContext.enrich] path — the exact path #65's native screen
 * capture feeds. That capture (the `viewDidAppear` swizzle) is what will push this frame for real;
 * pushing it here lets the harness observe the enrichment end of the path before the swizzle exists.
 *
 * **Idempotent.** SwiftUI may run `.onAppear` more than once for the same view, and installing twice
 * would attach a second recognizer to the same window and report every tap twice.
 */
@OptIn(AutographInternalApi::class)
public fun installNativeSampleCapture(onTap: (target: String, properties: String) -> Unit) {
    if (installed != null) return
    val scopeStack = ScopeStack().apply { push(screen = "NativeSample", section = "NativeMain") }
    installed = installAutographNativeTapCapture(
        tracker = LoggingTracker(onTrack = { props, target -> onTap(targetOrNoTarget(target), props.toString()) }),
        scopeStack = scopeStack,
    )
}

@OptIn(AutographInternalApi::class)
private var installed: AutographNativeTapCapture? = null
