@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.ynagai.autograph.uikit

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.invoke
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.UIKit.UIViewController
import platform.darwin.IMP
import platform.objc.class_getInstanceMethod
import platform.objc.method_setImplementation
import platform.objc.object_getClass
import platform.objc.sel_registerName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the Objective-C swizzling mechanism [installAutographNativeScreenCapture] is built on — that
 * a Kotlin `staticCFunction` can serve as an ObjC `IMP` for `UIViewController` lifecycle methods, that
 * chaining to the replaced implementation works, and that the swizzle applies class-wide including to
 * subclasses that call `super`. If a Kotlin/Native toolchain upgrade ever breaks any of these, this
 * test goes red *here* — a self-contained mechanism check — rather than the production capture failing
 * silently in the field, where lifecycle behavior is invisible to unit tests and is covered instead by
 * the sample-ios `NativeScreensUITests` harness.
 *
 * This was the throwaway measurement probe for #65 (`probe/65-swizzle-measurement`), promoted to
 * permanent coverage per the plan. It swizzles independently of the production code and restores what
 * it replaced, so it neither depends on nor disturbs [installAutographNativeScreenCapture]. It is the
 * *mechanism* it pins, not the production wiring — that is the XCUITest harness's job, because a probe
 * passing has never meant the real screen transition was observed.
 *
 * #62's lesson is why this is written in Kotlin, in the real module, compiled by the real toolchain: a
 * design validated only with a *Swift* probe turned out unbuildable in Kotlin. A Kotlin probe cannot
 * make that mistake.
 */
@OptIn(ExperimentalForeignApi::class)
class SwizzleMechanismTest {

    @Test
    fun swizzles_viewDidAppear_and_chains_to_the_original() {
        val vc = UIViewController()
        val cls: ObjCClass? = object_getClass(vc)
        assertTrue(cls != null, "object_getClass returned null")

        val sel = sel_registerName("viewDidAppear:")
        val method = class_getInstanceMethod(cls, sel)
        assertTrue(method != null, "class_getInstanceMethod(viewDidAppear:) returned null")

        // The measurement that matters: can a Kotlin `staticCFunction` serve as an ObjC IMP? Use the
        // IMP method_setImplementation RETURNS as the chained original — the same atomic swap the
        // production install relies on, not a separate method_getImplementation read.
        original = method_setImplementation(method, staticCFunction(::hook).reinterpret())
        assertTrue(original != null, "method_setImplementation returned no prior IMP to chain to")

        vc.viewDidAppear(false)
        assertEquals(1, hookCallCount, "hook did not run")
        assertEquals(vc.objcPtr().toLong(), hookSelf, "hook received the wrong `self`")
        assertTrue(chainedToOriginal, "hook could not call through to the original IMP")

        // A second, distinct controller — confirms the swizzle is on the class, not the instance —
        // and that the BOOL argument survives the IMP call.
        val other = UIViewController()
        other.viewDidAppear(true)
        assertEquals(2, hookCallCount, "swizzle did not apply to a second instance")
        assertEquals(other.objcPtr().toLong(), hookSelf, "second call got the wrong `self`")
        assertTrue(hookAnimatedArg, "the BOOL argument did not survive the IMP call")

        // Real app screens are all subclasses; a subclass that overrides and calls super must be hit.
        SubclassCallingSuper().viewDidAppear(false)
        assertEquals(3, hookCallCount, "base-class swizzle missed a subclass that calls super")

        // The documented blind spot: a subclass that overrides WITHOUT calling super is invisible.
        SubclassSkippingSuper().viewDidAppear(false)
        assertEquals(3, hookCallCount, "expected a subclass skipping super to be invisible")

        // Restore, then confirm the restore actually took effect (so this test leaves UIViewController
        // as it found it, and the assertion is non-vacuous).
        method_setImplementation(method, original)
        vc.viewDidAppear(false)
        assertEquals(3, hookCallCount, "restoring the original IMP did not unhook")
    }

    @Test
    fun swizzles_viewDidDisappear_the_same_way() {
        // The production capture swizzles viewDidDisappear: too; the probe only ever measured
        // viewDidAppear:. Pin the disappear side of the identical mechanism.
        val vc = UIViewController()
        val method = class_getInstanceMethod(object_getClass(vc), sel_registerName("viewDidDisappear:"))
        assertTrue(method != null, "class_getInstanceMethod(viewDidDisappear:) returned null")

        disappearOriginal = method_setImplementation(method, staticCFunction(::disappearHook).reinterpret())
        assertTrue(disappearOriginal != null, "no prior viewDidDisappear: IMP to chain to")

        vc.viewDidDisappear(false)
        assertEquals(1, disappearHookCallCount, "viewDidDisappear: hook did not run")
        assertTrue(disappearChained, "could not chain to the original viewDidDisappear:")

        method_setImplementation(method, disappearOriginal)
        vc.viewDidDisappear(false)
        assertEquals(1, disappearHookCallCount, "restoring the original viewDidDisappear: IMP did not unhook")
    }
}

private class SubclassCallingSuper : UIViewController(nibName = null, bundle = null) {
    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
    }
}

private class SubclassSkippingSuper : UIViewController(nibName = null, bundle = null) {
    override fun viewDidAppear(animated: Boolean) {
        // deliberately does not call super
    }
}

private var original: IMP? = null
private var hookCallCount = 0
private var hookSelf = 0L
private var hookAnimatedArg = false
private var chainedToOriginal = false

// Top-level and capture-free: staticCFunction rejects anything else, and Kotlin/Native forbids fields
// on the companion of an ObjC-derived class, so hook state cannot live in the test class either.
@OptIn(ExperimentalForeignApi::class)
private fun hook(self: COpaquePointer?, cmd: COpaquePointer?, animated: Boolean) {
    hookCallCount++
    hookSelf = self?.rawValue?.toLong() ?: 0L
    hookAnimatedArg = animated
    original?.let { orig ->
        orig.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?, Boolean) -> Unit>>()(self, cmd, animated)
        chainedToOriginal = true
    }
}

private var disappearOriginal: IMP? = null
private var disappearHookCallCount = 0
private var disappearChained = false

@OptIn(ExperimentalForeignApi::class)
private fun disappearHook(self: COpaquePointer?, cmd: COpaquePointer?, animated: Boolean) {
    disappearHookCallCount++
    disappearOriginal?.let { orig ->
        orig.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?, Boolean) -> Unit>>()(self, cmd, animated)
        disappearChained = true
    }
}
