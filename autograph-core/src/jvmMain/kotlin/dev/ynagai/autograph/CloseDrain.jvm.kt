package dev.ynagai.autograph

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun drainBlocking(timeoutMillis: Long, block: suspend () -> Unit) {
    runBlocking { withTimeoutOrNull(timeoutMillis) { block() } }
}
