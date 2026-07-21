package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.AutographInternalApi
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelNormal

/**
 * Whether this window is one the capture should instrument.
 *
 * Only `UIWindowLevelNormal` carries app content. UIKit puts the keyboard, alerts and the status bar
 * in their own higher-level windows, and those fire the same visibility notification the install path
 * listens to. Attaching there would at best waste a walk on a tree with no app content in it, and at
 * worst report a tap on a keyboard key as an app interaction.
 *
 * A gesture recognizer only ever sees touches delivered to the window it is attached to, so this is
 * also why the keyboard's own taps stay invisible to the capture — measured: an on-device probe
 * attached only to the normal-level window saw nothing at all from taps on the keyboard.
 */
@AutographInternalApi
public fun UIWindow.isCapturableWindow(): Boolean = windowLevel == UIWindowLevelNormal
