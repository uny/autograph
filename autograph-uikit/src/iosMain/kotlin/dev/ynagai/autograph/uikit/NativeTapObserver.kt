package dev.ynagai.autograph.uikit

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UITouch
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Watches a window's taps through a `UITapGestureRecognizer`, without disturbing them.
 *
 * **Why a stock recognizer rather than a `UIGestureRecognizer` subclass.** The subclass route — the
 * obvious one, and what an equivalent Swift implementation would do — is unavailable from
 * Kotlin/Native: `touchesBegan:withEvent:` and the writable `state` are declared in
 * `UIGestureRecognizerSubclass.h`, which is not part of UIKit's umbrella header, so cinterop never
 * generates them. Only umbrella-visible API is reachable here, and this class is built out of it.
 *
 * That constraint turned out to be a simplification. A raw touch observer has to decide for itself
 * whether a touch sequence was a tap or a drag — measured on-device, a scroll delivers `touchesEnded`
 * rather than `touchesCancelled`, so without a movement threshold every scroll reports as a tap.
 * `UITapGestureRecognizer` already embodies that judgement: it fires its action only for what UIKit
 * itself considers a tap. Verified on-device against a real scroll — the touch arrives at
 * [gestureRecognizer] and no tap is ever reported.
 *
 * **The position comes from where the touch began.** `locationInView` at action time is where the
 * touch *ended*, and a measured drag drifts ~100px between the two, which would resolve the tap
 * against a different element than the finger went down on. The delegate's `shouldReceiveTouch` runs
 * per touch before recognition, which is where the begin position is captured.
 *
 * **It never blocks anything.** `cancelsTouchesInView = false`, both `delaysTouches*` off, and a
 * delegate that permits simultaneous recognition with every other gesture. Verified on-device: a
 * SwiftUI `Button`, `Toggle` and `.onTapGesture` all kept working while every one of their taps was
 * observed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, AutographInternalApi::class)
internal class NativeTapObserver(
    private val onTap: (positionInWindowPoints: AxPoint, window: UIView) -> Unit,
) : NSObject(), UIGestureRecognizerDelegateProtocol {

    private var beganAt: AxPoint? = null

    /**
     * Which recognizer [beganAt] belongs to. One observer is the delegate for every instrumented
     * window's recognizer, so without this a touch beginning in one window could be reported as the
     * position of a tap recognized in another. Mismatch drops the tap rather than guessing.
     */
    private var beganFor: UIGestureRecognizer? = null

    /** The recognizer to attach to a window. Retains this object as its target; see [delegate]. */
    fun makeRecognizer(): UITapGestureRecognizer =
        UITapGestureRecognizer(target = this, action = HANDLE_TAP_SELECTOR).apply {
            cancelsTouchesInView = false
            delaysTouchesBegan = false
            delaysTouchesEnded = false
            // `delegate` is a *weak* reference. This object is retained by the recognizer's target,
            // which is the same object — so the delegate cannot be deallocated out from under the
            // recognizer and silently revert it to competing with the app's own gestures.
            delegate = this@NativeTapObserver
        }

    /**
     * Records where a touch began, and lets it through untouched.
     *
     * Only the first touch of a sequence is recorded: a second finger landing mid-gesture is not a
     * new tap, and overwriting would resolve against whichever finger happened to land last.
     *
     * The slot must therefore be replaced at the *start of a sequence*, not merely when it is empty.
     * A sequence that never becomes a tap — a scroll, a drag, a long press — never reaches
     * [handleTap], so an "only if empty" test would preserve that dead position and hand it to the
     * next real tap, resolving it against whatever the finger last went down on. `numberOfTouches`
     * is the sequence boundary available from the umbrella header: it is zero exactly when this
     * recognizer is holding no touches yet.
     */
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldReceiveTouch: UITouch,
    ): Boolean {
        recordBegin(
            recognizer = gestureRecognizer,
            touchesAlreadyHeld = gestureRecognizer.numberOfTouches(),
            position = shouldReceiveTouch.locationInView(null)
                .useContents { AxPoint(x.toFloat(), y.toFloat()) },
        )
        return true
    }

    /**
     * The state machine behind [gestureRecognizer], split out from it so tests can drive it: `UITouch`
     * has no constructible form, so the delegate callback itself is unreachable from a unit test.
     */
    internal fun recordBegin(recognizer: UIGestureRecognizer, touchesAlreadyHeld: ULong, position: AxPoint) {
        if (touchesAlreadyHeld != 0uL) return
        beganAt = position
        beganFor = recognizer
    }

    /** Never competes: whatever else wants this gesture is welcome to it. */
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer,
    ): Boolean = true

    @ObjCAction
    fun handleTap(sender: UITapGestureRecognizer) {
        val began = beganAt
        val begunFor = beganFor
        beganAt = null
        beganFor = null
        val window = sender.view ?: return
        // A recognized tap with no recorded begin position — or one recorded for a different window's
        // recognizer — should not happen: shouldReceiveTouch runs first for every touch. But resolving
        // against the end position, or against another window's, would quietly attribute to the wrong
        // element, so drop it rather than guess.
        if (began == null || begunFor != sender) return
        onTap(began, window)
    }
}

/**
 * Top-level rather than a companion field: Kotlin/Native does not allow fields on the companion of a
 * class that subclasses an Objective-C type.
 */
@OptIn(ExperimentalForeignApi::class)
private val HANDLE_TAP_SELECTOR = NSSelectorFromString("handleTap:")
