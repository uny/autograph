package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.AutographInternalApi
import dev.ynagai.autograph.context.ScopeStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowLevelNormal

/**
 * Covers the parts of the native tap capture that a headless test can reach: which windows get
 * instrumented, the recognizer configuration that keeps the capture from disturbing the app, and the
 * attach/uninstall bookkeeping.
 *
 * What is deliberately *not* here: that a tap is recognized and reported at all. That needs a real
 * touch, which cannot be fabricated — `UITouch` has no constructible form — so it is verified
 * on-device instead (see the class kdocs for the measured results).
 */
@OptIn(AutographInternalApi::class)
class NativeTapCaptureTest {

    private fun observer() = NativeTapObserver { _, _ -> }

    private fun UIWindow.allTapRecognizers() =
        gestureRecognizers?.filterIsInstance<UITapGestureRecognizer>().orEmpty()

    /**
     * The capture's own recognizers, identified the way [AutographNativeTapCapture.uninstall]
     * identifies them — by delegate, not by type.
     *
     * Type is not enough, and that is not a hypothetical: a freshly constructed [UIWindow] already
     * carries UIKit's own recognizers, one of them a [UITapGestureRecognizer] for keyboard dismissal.
     * Counting by type alone made every assertion here off by one, and a `uninstall` that removed by
     * type alone would tear out that recognizer and break keyboard dismissal in the host app.
     */
    private fun UIWindow.captureRecognizers() =
        allTapRecognizers().filter { it.delegate is NativeTapObserver }

    @Test
    fun onlyNormalLevelWindowsAreCapturable() {
        val window = UIWindow()

        assertEquals(UIWindowLevelNormal, window.windowLevel, "a plain UIWindow should start at the normal level")
        assertTrue(window.isCapturableWindow())

        // UIKit puts the keyboard, alerts and the status bar in windows above the normal level, and
        // they fire the same visibility notification install() listens to. Instrumenting one would
        // report a tap on a keyboard key as an app interaction.
        window.windowLevel = UIWindowLevelAlert
        assertFalse(window.isCapturableWindow())
    }

    @Test
    fun attachInstrumentsACapturableWindowExactlyOnce() {
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")
        val window = UIWindow()

        capture.attach(window)
        assertEquals(1, window.captureRecognizers().size)

        // UIWindowDidBecomeVisibleNotification fires every time a window becomes visible, not only
        // the first time — a window shown, hidden and shown again must not accumulate recognizers
        // and report each of its taps twice.
        capture.attach(window)
        assertEquals(1, window.captureRecognizers().size)
    }

    @Test
    fun attachSkipsWindowsAboveTheNormalLevel() {
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")
        val window = UIWindow()
        window.windowLevel = UIWindowLevelAlert

        capture.attach(window)

        assertTrue(window.captureRecognizers().isEmpty())
    }

    /**
     * The safety promises the capture makes to the host app, pinned as assertions: it observes taps
     * without consuming or delaying them, and never competes with the app's own gestures. Verified
     * on-device too (a SwiftUI `Button`, `Toggle` and `.onTapGesture` all kept working while their
     * taps were observed) — this pins the configuration that made that true.
     */
    @Test
    fun theRecognizerNeverBlocksOrDelaysTheAppsOwnGestures() {
        val observer = observer()

        val recognizer = observer.makeRecognizer()

        assertFalse(recognizer.cancelsTouchesInView)
        assertFalse(recognizer.delaysTouchesBegan)
        assertFalse(recognizer.delaysTouchesEnded)
        assertTrue(
            observer.gestureRecognizer(recognizer, shouldRecognizeSimultaneouslyWithGestureRecognizer = recognizer),
            "the capture must yield to every other recognizer, or it would starve the app's own gestures",
        )
    }

    /**
     * The delegate is a *weak* reference on `UIGestureRecognizer`. The observer stays alive because it
     * is also the recognizer's target, which is retained — if that ever stopped being true the
     * delegate would go nil and the recognizer would silently revert to competing with the app's
     * gestures rather than yielding to them.
     */
    @Test
    fun theRecognizerKeepsItsDelegateAlive() {
        val window = UIWindow()
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")
        capture.attach(window)

        val recognizer = window.captureRecognizers().single()

        assertTrue(recognizer.delegate != null, "the delegate was deallocated — nothing is retaining the observer")
    }

    /**
     * Uninstall must disarm this capture's recognizers and nothing else: an app is free to have its
     * own tap recognizers on the same window, and taking those down would break the app's UI.
     *
     * This also pins the interop comparison `uninstall` filters by. The recognizers it inspects arrive
     * through `window.gestureRecognizers` — an Objective-C crossing, where Kotlin/Native hands back a
     * fresh wrapper per fetch — so an identity (`===`) check can come back false for the capture's own
     * recognizer and leave it attached, still reporting into the tracker `uninstall` exists to
     * release. Silently, with nothing in the logs. Hence `==`.
     */
    @Test
    fun uninstallRemovesOnlyThisCapturesRecognizers() {
        val window = UIWindow()
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")
        capture.attach(window)

        // A second capture stands in for the app's own recognizer: same class, different owner, so
        // the filter cannot pass by matching on type alone.
        val other = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "other_event")
        other.attach(window)
        assertEquals(2, window.captureRecognizers().size)
        val uikitOwnBefore = window.allTapRecognizers().size - 2

        capture.uninstall()

        assertEquals(
            1,
            window.captureRecognizers().size,
            "uninstall should have removed exactly its own recognizer, leaving the other capture's attached",
        )
        assertEquals(
            uikitOwnBefore,
            window.allTapRecognizers().size - 1,
            "uninstall tore out a recognizer UIKit put on the window itself — filtering by type instead of by delegate would do exactly this, and it breaks keyboard dismissal",
        )
    }

    @Test
    fun uninstallIsSafeToCallMoreThanOnceAndWithoutInstalling() {
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")

        capture.uninstall()
        capture.uninstall()
    }

    /**
     * A recognized tap with no recorded begin position should not happen — `shouldReceiveTouch` runs
     * first for every touch. If it ever does, the end position is the only thing left to resolve
     * against, and a measured drag drifts ~100px between the two, so reporting would attribute the tap
     * to an element the finger never went down on. Dropping is the correct degrade.
     */
    @Test
    fun aTapWithNoRecordedBeginPositionIsDroppedRatherThanGuessed() {
        var reported: AxPoint? = null
        val observer = NativeTapObserver { position, _ -> reported = position }
        val window = UIWindow()
        val recognizer = observer.makeRecognizer()
        window.addGestureRecognizer(recognizer)

        observer.handleTap(recognizer)

        assertNull(reported)
    }

    /**
     * The bug this pins: a touch sequence that never becomes a tap — a scroll, a drag, a long press —
     * never reaches `handleTap`, so it never clears the recorded begin position. Recording only when
     * the slot is empty would preserve that dead position and hand it to the *next* real tap, which
     * would then resolve against whatever the finger last went down on rather than what it just
     * tapped. Every tap following any scroll would be attributed to the wrong element, silently.
     */
    @Test
    fun aBeginPositionLeftBehindByAGestureThatNeverTappedIsNotReusedByTheNextTap() {
        var reported: AxPoint? = null
        val observer = NativeTapObserver { position, _ -> reported = position }
        val window = UIWindow()
        val recognizer = observer.makeRecognizer()
        window.addGestureRecognizer(recognizer)

        // A scroll: the touch begins and is recorded, but the tap recognizer fails, so no action fires.
        observer.recordBegin(recognizer, touchesAlreadyHeld = 0uL, position = AxPoint(10f, 10f))

        // Then a real tap somewhere else entirely.
        observer.recordBegin(recognizer, touchesAlreadyHeld = 0uL, position = AxPoint(200f, 400f))
        observer.handleTap(recognizer)

        assertEquals(AxPoint(200f, 400f), reported, "the tap was attributed to where the earlier scroll began")
    }

    /**
     * A second finger landing while the recognizer already holds a touch is not a new tap, and letting
     * it overwrite would resolve against whichever finger happened to land last.
     */
    @Test
    fun aSecondFingerMidSequenceDoesNotMoveTheRecordedPosition() {
        var reported: AxPoint? = null
        val observer = NativeTapObserver { position, _ -> reported = position }
        val recognizer = observer.makeRecognizer()

        observer.recordBegin(recognizer, touchesAlreadyHeld = 0uL, position = AxPoint(10f, 10f))
        observer.recordBegin(recognizer, touchesAlreadyHeld = 1uL, position = AxPoint(300f, 300f))
        UIWindow().addGestureRecognizer(recognizer)
        observer.handleTap(recognizer)

        assertEquals(AxPoint(10f, 10f), reported)
    }

    /**
     * One observer is the delegate for every instrumented window's recognizer, so the recorded position
     * has to be scoped to the recognizer that produced it — otherwise a touch beginning in one window
     * is reported as the position of a tap recognized in another.
     */
    @Test
    fun aTapIsDroppedWhenTheBeginPositionBelongsToAnotherWindowsRecognizer() {
        var reported: AxPoint? = null
        val observer = NativeTapObserver { position, _ -> reported = position }
        val recognizerA = observer.makeRecognizer()
        val recognizerB = observer.makeRecognizer()
        UIWindow().addGestureRecognizer(recognizerB)

        observer.recordBegin(recognizerA, touchesAlreadyHeld = 0uL, position = AxPoint(10f, 10f))
        observer.handleTap(recognizerB)

        assertNull(reported)
    }

    /**
     * `addObserverForName` delivers its block as an operation on the main queue, so a window-visible
     * notification posted before `uninstall` can drain after it. Re-attaching then would leave a
     * recognizer in no set anyone iterates again, permanently reporting into the retired tracker.
     */
    @Test
    fun attachAfterUninstallDoesNothing() {
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "test_event")
        val window = UIWindow()

        capture.uninstall()
        capture.attach(window)

        assertTrue(window.captureRecognizers().isEmpty())
    }
}

/** Stands in for a real tracker where the test asserts on the capture's plumbing, not on its output. */
private class NoopTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) = Unit

    override fun screen(name: String, properties: JsonObject) = Unit

    override fun identify(userId: String, traits: JsonObject) = Unit
}
