package dev.ynagai.autograph

internal actual class ThreadLocalCount actual constructor() {
    private val value = ThreadLocal.withInitial { 0 }

    actual fun get(): Int = value.get()

    actual fun set(value: Int) {
        if (value == 0) this.value.remove() else this.value.set(value)
    }
}
