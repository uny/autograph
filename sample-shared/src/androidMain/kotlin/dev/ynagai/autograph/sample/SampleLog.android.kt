package dev.ynagai.autograph.sample

import android.util.Log

internal actual fun sampleLog(message: String) {
    Log.d("AutographSample", message)
}
