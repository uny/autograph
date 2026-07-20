package dev.ynagai.autograph.sample

import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.uikit.AutographScreenCapture
import dev.ynagai.autograph.uikit.AutographScreenView

/**
 * Installs the SwiftUI-screens sample's [AutographScreenCapture] — #65's explicit `.autographScreen`
 * path — wired to a [LoggingTracker] that surfaces the cumulative `name:previous_screen` log for the
 * XCUITest. This is the SwiftUI counterpart to [installNativeScreensCapture]: the UIKit `viewDidAppear:`
 * swizzle can't see SwiftUI screens (each is one system-bundle `UIHostingController`, and
 * `NavigationStack` swaps destinations inside it), so they name themselves.
 *
 * The Swift side drives this **by name** — [swiftUIScreenAppeared] / [swiftUIScreenDisappeared] — so no
 * `autograph-uikit` type has to cross into the sample app; the [AutographScreenView] tokens are held
 * here, keyed by screen name. A shipped app instead uses the `AutographUI` product's `.autographScreen`
 * modifier, which holds the token in SwiftUI `@State`; this sample can't add that SwiftPM product
 * without pulling in the `Autograph` umbrella framework alongside `sample_shared`, so it drives the same
 * Kotlin facade directly. The lifecycle binding — `onAppear` → [AutographScreenCapture.appeared],
 * `onDisappear` → [AutographScreenView.disappeared] — is identical either way, which is what this
 * verifies through real NavigationStack navigation.
 */
public fun installSwiftUIScreensCapture(onScreenLog: (log: String) -> Unit) {
    if (swiftUICapture != null) return
    val scopeStack = ScopeStack()
    val tracker = LoggingTracker(
        onScreen = { name, properties ->
            swiftUIScreenLog = appendScreenLog(swiftUIScreenLog, name, properties.reservedOrNone("previous_screen"))
            onScreenLog(swiftUIScreenLog)
        },
    )
    swiftUICapture = AutographScreenCapture(tracker = tracker, scopeStack = scopeStack)
}

/** Reports that the SwiftUI screen [name] appeared. Mirrors `.autographScreen`'s `onAppear`. */
public fun swiftUIScreenAppeared(name: String) {
    val capture = swiftUICapture ?: return
    liveSwiftUIViews[name] = capture.appeared(name)
}

/** Reports that the SwiftUI screen [name] disappeared. Mirrors `.autographScreen`'s `onDisappear`. */
public fun swiftUIScreenDisappeared(name: String) {
    liveSwiftUIViews.remove(name)?.disappeared()
}

private var swiftUICapture: AutographScreenCapture? = null
private val liveSwiftUIViews = mutableMapOf<String, AutographScreenView>()
private var swiftUIScreenLog = noEventYet
