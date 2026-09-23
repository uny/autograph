package dev.ynagai.autograph

import kotlin.native.concurrent.ThreadLocal

internal actual class ThreadLocalCount actual constructor() {
    actual fun get(): Int = Counts.byInstance[this] ?: 0

    actual fun set(value: Int) {
        if (value == 0) Counts.byInstance.remove(this) else Counts.byInstance[this] = value
    }
}

// Kotlin/Native has no per-instance thread-local, only per-thread objects: one map per thread, keyed by
// instance. A zero entry is removed, so a thread holds nothing once it has left every call.
@ThreadLocal
private object Counts {
    val byInstance = HashMap<ThreadLocalCount, Int>()
}
