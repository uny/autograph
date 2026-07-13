package dev.ynagai.autograph.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point called from `sample-ios/iosApp`'s SwiftUI shell (`ContentView.swift`) via
 * `UIViewControllerRepresentable`. `enforceStrictPlistSanityCheck = false`: belt-and-suspenders —
 * a bare hand-built `.app` without it crashed via `PlistSanityCheck` during this library's own
 * on-device verification; Xcode's generated `Info.plist` shouldn't trigger it, but there's no
 * reason to find out the hard way twice.
 */
public fun MainViewController(): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { App() }
