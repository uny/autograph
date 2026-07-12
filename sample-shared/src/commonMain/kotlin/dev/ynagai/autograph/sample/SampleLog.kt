package dev.ynagai.autograph.sample

/** Platform-appropriate logging (`Log.d` on Android, `NSLog` on iOS) for the sample app's [LoggingTracker]. */
internal expect fun sampleLog(message: String)
