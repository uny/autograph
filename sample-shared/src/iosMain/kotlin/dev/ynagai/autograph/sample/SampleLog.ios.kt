package dev.ynagai.autograph.sample

import platform.Foundation.NSLog

internal actual fun sampleLog(message: String) {
    // A single format-string argument, no varargs: passing `message` as a `%@` vararg crashed
    // with EXC_BAD_ACCESS inside NSLog's own formatting machinery when built into this app's
    // exported framework (confirmed on-device this session) — interpolating the whole line
    // up front and passing it as NSLog's sole argument sidesteps that interop path entirely.
    NSLog("AutographSample: $message")
}
