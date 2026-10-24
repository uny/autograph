package dev.ynagai.autograph

// No Swift or Objective-C object can implement a Kotlin interface on this platform.
internal actual fun isImplementedOutsideKotlin(receiver: Any): Boolean = false
