package dev.ynagai.autograph.sample

import dev.ynagai.autograph.context.ScopeStack
import dev.ynagai.autograph.context.AutographInternalApi
import dev.ynagai.autograph.uikit.defaultScreenName
import dev.ynagai.autograph.uikit.installAutographNativeScreenCapture
import dev.ynagai.autograph.uikit.installAutographNativeTapCapture
import platform.UIKit.UIViewController

/**
 * Installs `autograph-uikit`'s **native screen capture** (#65's `viewDidAppear:` swizzle) alongside
 * native tap capture, both feeding one shared [ScopeStack], for the UIKit-navigation sample
 * (`NativeScreensFlow.swift`).
 *
 * This is the surface #65's screen capture is verified on. The SwiftUI-only native sample cannot serve
 * it: its screen is a `UIHostingController` from a system bundle, which the capture deliberately
 * excludes — a real UIKit `UIViewController` hierarchy (a `UINavigationController` pushing app
 * controllers, presenting a sheet, switching tabs) is what actually fires the swizzle. Sharing one
 * [ScopeStack] between the two captures is also what lets a native tap carry the `screen` the screen
 * capture pushed — the whole point of #65 for #62's taps.
 *
 * [onScreenLog] receives the cumulative `name:previous_screen` log (built the same way the Compose
 * sample builds it), and [onTap] the last resolved tap's target and full properties JSON — both
 * surfaced on-screen because the XCUITest suite cannot read Kotlin state. See [LoggingTracker].
 *
 * **Idempotent.** The swizzle is process-global and installs once; re-entry here would only replace the
 * sink and add a second tap recognizer, so it is guarded.
 */
@OptIn(AutographInternalApi::class)
public fun installNativeScreensCapture(
    onScreenLog: (log: String) -> Unit,
    onTap: (target: String, properties: String) -> Unit,
) {
    if (screensInstalled) return
    screensInstalled = true
    val scopeStack = ScopeStack()
    val tracker = LoggingTracker(
        onTrack = { properties, target -> onTap(targetOrNoTarget(target), properties.toString()) },
        onScreen = { name, properties ->
            screenLog = appendScreenLog(screenLog, name, properties.reservedOrNone("previous_screen"))
            onScreenLog(screenLog)
        },
    )
    installAutographNativeScreenCapture(
        tracker = tracker,
        scopeStack = scopeStack,
        screenName = ::sampleScreenName,
    )
    installAutographNativeTapCapture(tracker = tracker, scopeStack = scopeStack)
}

/**
 * The sample's [installAutographNativeScreenCapture] naming override: the class name with its module
 * qualifier dropped (`"iosApp.FirstScreen"` → `"FirstScreen"`). Exactly the cleanup a real app does —
 * the default keeps the module prefix precisely so the app, not the library, owns the convention.
 */
@OptIn(AutographInternalApi::class)
private fun sampleScreenName(controller: UIViewController): String? =
    defaultScreenName(controller)?.substringAfterLast('.')

private var screensInstalled = false
private var screenLog = noEventYet
