package dev.ynagai.autograph.sample

import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.AutographInternalApi
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
 * A fresh [ScopeStack] rather than a shared one, because this SwiftUI screen renders no Compose to
 * share it with. A hybrid app would pass the same stack it gives `AutographProvider`.
 *
 * **No screen frame is pushed here.** Native *screen* capture (#65's `viewDidAppear:` swizzle) works
 * on real UIKit controllers, which this SwiftUI-only screen is not — its host is a system-bundle
 * `UIHostingController` the swizzle excludes. So a tap reported here carries no `screen`, and that is
 * correct: the screen-attribution path is exercised by the UIKit-navigation sample
 * (`installNativeScreensCapture`) instead, where a swizzle-produced frame actually exists. (Until #65
 * this installer pushed a stand-in screen frame; that fixture is gone now that the swizzle supplies
 * real frames on the surface built for it.)
 *
 * **Idempotent.** SwiftUI may run `.onAppear` more than once for the same view, and installing twice
 * would attach a second recognizer to the same window and report every tap twice.
 */
@OptIn(AutographInternalApi::class)
public fun installNativeSampleCapture(onTap: (target: String, properties: String) -> Unit) {
    if (installed != null) return
    val scopeStack = ScopeStack()
    installed = installAutographNativeTapCapture(
        tracker = LoggingTracker(onTrack = { props, target -> onTap(targetOrNoTarget(target), props.toString()) }),
        scopeStack = scopeStack,
    )
}

@OptIn(AutographInternalApi::class)
private var installed: AutographNativeTapCapture? = null
