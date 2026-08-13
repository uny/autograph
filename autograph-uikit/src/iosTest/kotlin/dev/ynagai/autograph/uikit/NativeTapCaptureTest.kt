package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.context.ScopeStack
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIButton
import platform.UIKit.UIScreen
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowLevelNormal
import platform.UIKit.setAccessibilityFrame
import platform.UIKit.setAccessibilityTraits
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * Covers the parts of the native tap capture that a headless test can reach: which windows get
 * instrumented, the recognizer configuration that keeps the capture from disturbing the app, and the
 * attach/uninstall bookkeeping.
 *
 * What is deliberately *not* here: that a tap is recognized and reported at all. That needs a real
 * touch, which cannot be fabricated — `UITouch` has no constructible form — so it is verified
 * on-device instead (see the class kdocs for the measured results).
 */
@OptIn(AutographInternalApi::class, ExperimentalForeignApi::class)
class NativeTapCaptureTest {

    /**
     * [warnOnceIfAccessibilityTreeIsCold] spends its one-per-process check exactly once — reset it.
     *
     * Both hooks, not just [AfterTest]: the flag is process-global and the K/N test binary is one
     * process, so a `@BeforeTest` reset is what keeps these tests independent of whether some other
     * class ran first and left it set. Cleaning up after ourselves is the courtesy; not trusting anyone
     * else to is the guarantee.
     */
    @BeforeTest
    @AfterTest
    fun resetAccessibilityTreeColdnessCheck() {
        checkedAccessibilityTreeColdness = false
    }

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

    // --- #170: the one-time cold-accessibility-tree warning ---

    @Test
    fun aColdTreeWarnsOnTheFirstDrop() {
        assertFalse(checkedAccessibilityTreeColdness, "precondition: nothing has run it yet this test")

        assertTrue(warnOnceIfAccessibilityTreeIsCold(UIWindow()), "a cold tree is what the warning is for")
        assertTrue(checkedAccessibilityTreeColdness)
    }

    /** The join between the drop and the tree question: a warm tree spends the check but stays quiet. */
    @Test
    fun aWarmTreeSpendsTheCheckWithoutWarning() {
        val warmWindow = UIWindow()
        warmWindow.setAccessibilityFrame(CGRectMake(0.0, 0.0, 10.0, 10.0))

        assertFalse(warnOnceIfAccessibilityTreeIsCold(warmWindow), "an ordinary miss on a warm tree is not news")
        assertTrue(checkedAccessibilityTreeColdness, "spent either way — the answer cannot change later")
    }

    /**
     * Once spent, a second call is a no-op *and returns as one* — asserted on the return value, not on
     * the flag, which production never clears and which would therefore report success even if the
     * early-return guard were deleted outright. The window here is cold, so without the guard this
     * would warn a second time.
     *
     * This is also what makes it safe for every dropped tap to call
     * [warnOnceIfAccessibilityTreeIsCold] unconditionally, as [AutographNativeTapCapture.report] does,
     * rather than every caller having to check the flag first.
     */
    @Test
    fun aSecondCallNeitherChecksNorWarns() {
        assertTrue(warnOnceIfAccessibilityTreeIsCold(UIWindow()))

        assertFalse(warnOnceIfAccessibilityTreeIsCold(UIWindow()), "the second call must not warn again")
    }

    /**
     * The wiring itself: a dropped tap has to reach the warning. Everything above drives
     * [warnOnceIfAccessibilityTreeIsCold] directly, which leaves the single line in
     * [AutographNativeTapCapture.report] that calls it uncovered — and restoring the `?: return` it
     * replaced reads as a simplification, so nothing else would catch the whole feature disappearing.
     *
     * A bare [UIWindow] resolves nothing (it is cold, so no frame contains any position), which is the
     * drop this needs; [AutographNativeTapCapture.report] is reachable without a `UITouch`, unlike the
     * recognizer path the class kdoc describes.
     */
    @Test
    fun aDroppedTapReachesTheColdnessCheck() {
        val capture = AutographNativeTapCapture(NoopTracker(), ScopeStack(), "Element Clicked")

        capture.report(AxPoint(5f, 5f), UIWindow())

        assertTrue(checkedAccessibilityTreeColdness, "a drop must ask the coldness question")
    }

    // --- #189: the two resolvers, and the order report() runs them in ---

    private val scale: Float get() = UIScreen.mainScreen.scale.toFloat()

    /**
     * The `hitTest` resolver answers first, and its answer is reported — on a tree with no
     * accessibility state whatsoever, which is what a cold process looks like. This is the whole of
     * #189 seen from the pipeline's end rather than the resolver's.
     */
    @Test
    fun aTapOnAUIKitControlIsReportedFromAColdTree() {
        val tracker = RecordingTracker()
        val capture = AutographNativeTapCapture(tracker, ScopeStack(), "Element Clicked")
        val root = UIView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0))
        root.addSubview(
            UIButton(frame = CGRectMake(10.0, 10.0, 20.0, 20.0))
                .also { (it as NSObject).setValue("share_button", forKey = "accessibilityIdentifier") },
        )

        capture.report(AxPoint(15f, 15f), root)

        assertEquals(listOf<String?>("share_button"), tracker.targets)
        assertFalse(
            checkedAccessibilityTreeColdness,
            "a tap the hitTest route resolved is not a cold-tree symptom and must not spend the check",
        )
    }

    /**
     * **The reason [NativeHitTestResolution] is a tri-state rather than a nullable `String`.** A veto is
     * terminal: it must not fall through to a resolver that would answer differently.
     *
     * The fixture makes the two resolvers genuinely disagree, so this is not vacuous. `hitTest` follows
     * real frames into the registered Compose host and drops the tap as Compose-owned. The accessibility
     * walk cannot see that host at all — its `accessibilityFrame` is zero, exactly as a cold one is — so
     * it prunes that branch, finds the accessibility-only sibling instead, and would happily report it.
     * With a nullable resolver and a `?:`, this tap on Compose-owned content leaks out under another
     * element's name.
     *
     * **Fault-injected** (manual, per repo discipline): collapsing `report`'s `Dropped -> return` into
     * the `Unresolved` branch makes this test fail and no other. Without that check it would be
     * measuring nothing.
     */
    @Test
    fun aTapVetoedByTheHitTestResolverIsNotRescuedByTheAccessibilityOne() {
        val tracker = RecordingTracker()
        val capture = AutographNativeTapCapture(tracker, ScopeStack(), "Element Clicked")

        val root = UIView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0))
        root.setAccessibilityFrame(CGRectMake(0.0, 0.0, 100.0, 100.0))

        val composeHost = UIView(frame = CGRectMake(0.0, 0.0, 100.0, 100.0))
        root.addSubview(composeHost)
        composeHost.addSubview(
            UIButton(frame = CGRectMake(10.0, 10.0, 20.0, 20.0))
                .also { (it as NSObject).setValue("compose_button", forKey = "accessibilityIdentifier") },
        )

        // Visible only to the accessibility walk: zero real frame, so hitTest never reaches it.
        val axOnlySibling = UIView()
        axOnlySibling.setAccessibilityFrame(CGRectMake(10.0, 10.0, 20.0, 20.0))
        axOnlySibling.setAccessibilityTraits(UIAccessibilityTraitButton)
        (axOnlySibling as NSObject).setValue("ax_button", forKey = "accessibilityIdentifier")
        root.addSubview(axOnlySibling)

        assertEquals(
            "ax_button",
            resolveNativeTapTarget(root, AxPoint(15f * scale, 15f * scale), scale),
            "precondition: the accessibility resolver does resolve this tap, so falling through would report it",
        )

        AutographComposeHosts.register(composeHost)
        try {
            capture.report(AxPoint(15f, 15f), root)
        } finally {
            AutographComposeHosts.unregister(composeHost)
        }

        assertTrue(tracker.targets.isEmpty(), "a tap on Compose-owned content must not be reported by this pipeline")
        assertFalse(checkedAccessibilityTreeColdness, "a deliberate veto is not a cold-tree symptom")
    }

    /**
     * The other half of the sequencing: `hitTest` having nothing to say is *not* a drop. This fixture is
     * the SwiftUI-shaped case in miniature — no real geometry to hit-test, identity only on the
     * accessibility tree — and it must still resolve, or #189 would have deleted the warm SwiftUI half
     * of native capture while fixing the cold UIKit one.
     */
    @Test
    fun aTapTheHitTestResolverCannotSeeStillFallsThroughToTheAccessibilityOne() {
        val tracker = RecordingTracker()
        val capture = AutographNativeTapCapture(tracker, ScopeStack(), "Element Clicked")

        val root = UIView()
        root.setAccessibilityFrame(CGRectMake(0.0, 0.0, 100.0, 100.0))
        val axButton = UIView()
        axButton.setAccessibilityFrame(CGRectMake(10.0, 10.0, 20.0, 20.0))
        axButton.setAccessibilityTraits(UIAccessibilityTraitButton)
        (axButton as NSObject).setValue("swiftui_button", forKey = "accessibilityIdentifier")
        root.addSubview(axButton)

        capture.report(AxPoint(15f, 15f), root)

        assertEquals(listOf<String?>("swiftui_button"), tracker.targets)
    }
}

/** Records what was tracked, for the tests that assert on the pipeline's output rather than its plumbing. */
private class RecordingTracker : Tracker {

    val targets = mutableListOf<String?>()

    override fun track(name: String, properties: JsonObject, target: String?) {
        targets += target
    }

    override fun screen(name: String, properties: JsonObject) = Unit

    override fun identify(userId: String, traits: JsonObject) = Unit
}

/** Stands in for a real tracker where the test asserts on the capture's plumbing, not on its output. */
private class NoopTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) = Unit

    override fun screen(name: String, properties: JsonObject) = Unit

    override fun identify(userId: String, traits: JsonObject) = Unit
}
