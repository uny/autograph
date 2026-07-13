package dev.ynagai.autograph.sample

/** Platform-appropriate logging for the sample app's [LoggingTracker] (`Log.d` on Android; other platforms add an `actual` here as they gain a sample). */
internal expect fun sampleLog(message: String)
