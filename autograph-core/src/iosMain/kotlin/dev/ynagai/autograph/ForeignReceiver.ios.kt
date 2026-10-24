package dev.ynagai.autograph

import kotlinx.cinterop.ExperimentalForeignApi
import platform.objc.class_getSuperclass
import platform.objc.object_getClass

/** The Objective-C class every Kotlin object in this framework descends from (`KotlinBase` in Swift). */
@OptIn(ExperimentalForeignApi::class)
private val kotlinBase by lazy { object_getClass(Any()) }

// Measured (#299): a Kotlin object's Objective-C class chain runs through the framework's `KotlinBase`
// (an anonymous object, a public class and `AutographTracker` alike), while a Swift class's runs through
// `SwiftObject` or `NSObject` only. `is ObjCObject` does not tell them apart: it is true for all of them.
// A Kotlin class cannot subclass an Objective-C class and implement a Kotlin interface at once (the
// compiler rejects mixing the two), so no Kotlin conformer lacks `KotlinBase`.
@OptIn(ExperimentalForeignApi::class)
internal actual fun isImplementedOutsideKotlin(receiver: Any): Boolean {
    var cls = object_getClass(receiver)
    while (cls != null) {
        if (cls == kotlinBase) return false
        cls = class_getSuperclass(cls)
    }
    return true
}
