import XCTest

/// Permanent on-device regression coverage for `ElementResolver.ios.kt`'s hit-testing —
/// `compose.uiTest`'s iOS scene doesn't render into `LocalUIView`, so unit tests alone can't
/// exercise a live resolve. `XCUIApplication` taps drive real synthetic touches through the OS,
/// exactly like a user; the app under test surfaces what got resolved via a plain Compose `Text`
/// (`last_event_label`, see `App.kt`) since a UI test can't read Kotlin state directly.
final class iosAppUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["last_event_label"].label
    }

    func testPlainButtonAttribution() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["plain_button"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: plain_button")
    }

    /// A tap on the inner element of a clickable-inside-clickable pair must attribute to the
    /// inner element, not the outer ancestor it's nested in.
    func testNestedClickableAttributesToInnerElement() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["inner_button"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: inner_button")
    }

    /// A tap on the outer container, outside the inner element's bounds, must attribute to the
    /// outer element, not always default to whichever is nested deepest.
    func testOuterContainerAttributesToOuterElement() {
        let app = XCUIApplication()
        app.launch()
        // Coordinate well outside inner_button's bounds but inside outer_container's.
        let outer = app.buttons["outer_container"]
        outer.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: outer_container")
    }

    /// Modifier.trackClick fires its own explicit event; autocapture must not also report it.
    func testExplicitTrackClickFiresExactlyOnce() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["explicit_tracked_button"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: explicit_tracked_button")
    }

    /// Modifier.autographIgnore excludes a subtree from autocapture entirely — the label (last set
    /// by the initial trackImpression on launch) must stay unchanged.
    func testIgnoredElementIsNotCaptured() {
        let app = XCUIApplication()
        app.launch()
        let beforeTap = lastEventLabel(app)
        app.buttons["ignored_button"].tap()
        XCTAssertEqual(lastEventLabel(app), beforeTap)
    }
}

/// Coverage for the *native* (UIKit/SwiftUI) capture in `autograph-uikit` — a separate pipeline from
/// the Compose one above, sharing only the accessibility-tree walk.
///
/// This suite exists because that walk's defects have consistently been invisible to unit tests: a
/// hand-built `UIView` tree has none of the structure a real SwiftUI hierarchy produces. #77 (no
/// identifier off UIKit views), #82 (a full-screen passthrough overlay swallowing every tap) and #83
/// (a scroll leaving its touch-begin position behind for the next tap) all shipped green unit suites.
/// Every test below is aimed at one of those shapes.
final class NativeSampleUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchNativeSample() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-native-sample"]
        app.launch()
        return app
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["native_last_event_label"].label
    }

    /// Scrolls the list with a real touch stream.
    ///
    /// **Deliberately not `swipeUp()`.** Measured on the simulator: XCUITest's swipe synthesis is
    /// reported as a *tap* by a window-level `UITapGestureRecognizer` — at `.slow`, `.default` and
    /// `.fast` alike — while an explicit press-and-drag is not, at durations both shorter (0.05s) and
    /// longer (0.2s) than the swipe. So the variable is XCUITest's synthesis, not gesture speed, and a
    /// `swipeUp` does not exercise what a user scrolling actually delivers.
    ///
    /// This matters beyond one assertion: a "scroll" that is really a tap fires the action that clears
    /// the recorded touch-begin position, so `testNativeTapAfterScrollAttributesToTheTappedRow` would
    /// pass without ever reproducing the #83 bug it exists to pin. It was written with `swipeUp` first
    /// and did exactly that.
    private func scrollList(_ app: XCUIApplication) {
        let list = app.collectionViews.firstMatch
        list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
            .press(
                forDuration: 0.2,
                thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
            )
    }

    func testNativeButtonAttribution() {
        let app = launchNativeSample()
        app.buttons["native_plain_button"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: native_plain_button")
    }

    /// The #82 case, end to end: on a real SwiftUI `List` a full-screen `_UITouchPassthroughView`
    /// sits on top of the cells. The walk used to commit to it and never reach them, so every tap
    /// resolved to nothing.
    func testNativeListRowAttribution() {
        let app = launchNativeSample()
        app.buttons["native_row_2"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: native_row_2")
    }

    /// The #83 case, end to end, and the one no unit test could have caught: it needs two gestures in
    /// sequence. A scroll never becomes a tap, so it never reaches the action that clears the recorded
    /// touch-begin position — and the next real tap was then resolved against wherever the scroll went
    /// down, silently attributing to the wrong element.
    func testNativeTapAfterScrollAttributesToTheTappedRow() {
        let app = launchNativeSample()

        scrollList(app)

        let row = app.buttons
            .matching(NSPredicate(format: "identifier BEGINSWITH 'native_row_'"))
            .allElementsBoundByIndex
            .first { $0.isHittable }
        guard let row, !row.identifier.isEmpty else {
            return XCTFail("no row was hittable after scrolling")
        }
        let identifier = row.identifier
        row.tap()

        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: \(identifier)",
            "the tap was attributed to something other than the row it landed on — a begin position left behind by the scroll"
        )
    }

    /// Asserts that capture is still live, so a preceding "nothing was reported" assertion means the
    /// gesture was declined rather than that the pipeline was dead the whole time.
    ///
    /// Without this, both negative tests below pass on an app where `installNativeSampleCapture`
    /// never ran: the label simply stays at its initial value and every `XCTAssertEqual(label, before)`
    /// holds. Their Compose counterpart (`testIgnoredElementIsNotCaptured`) doesn't need the check
    /// because its baseline is already a value the pipeline produced.
    private func assertCaptureIsStillLive(_ app: XCUIApplication) {
        app.buttons["native_plain_button"].tap()
        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: native_plain_button",
            "capture reported nothing for a known-good tap either — the preceding assertion proved nothing"
        )
    }

    /// A scroll is not an interaction worth reporting. Measured on-device, a scroll delivers
    /// `touchesEnded` rather than `touchesCancelled`, so nothing but `UITapGestureRecognizer`
    /// declining to fire keeps it from being reported as a tap.
    func testNativeScrollAloneReportsNothing() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        scrollList(app)

        XCTAssertEqual(lastEventLabel(app), before)
        assertCaptureIsStillLive(app)
    }

    /// An element with no `accessibilityIdentifier` has no stable name to report. Identification must
    /// never fall back to `accessibilityLabel`, which is user-facing display text — that fallback is
    /// where the "never capture displayed text" guarantee would break.
    func testNativeButtonWithoutAnIdentifierIsNotReported() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        app.buttons["Unidentified"].tap()

        XCTAssertEqual(lastEventLabel(app), before)
        assertCaptureIsStillLive(app)
    }
}

/// The Compose/native boundary, on-device.
///
/// Both pipelines hit-test the same accessibility tree, so a tap on Compose content is visible to
/// both. `AutographComposeHosts` is what keeps the native side off it — and the registration that
/// populates it has to happen whether or not Compose autocapture is on, because the invariant is
/// *content under a Compose host belongs to the Compose pipeline exclusively*, not *content the
/// Compose pipeline reported*.
final class HybridBoundaryUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchHybridSample() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-hybrid-sample"]
        app.launch()
        return app
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["native_last_event_label"].label
    }

    /// The privacy case. Compose runs here with autocapture *off*, so it reports nothing; if the
    /// native pipeline is not held off the Compose subtree, it reports the tap instead — capturing
    /// content whose `autographIgnore()` exclusions live in Compose state it cannot see.
    ///
    /// The Compose button carries a `testTag`, so it reaches the bridged tree with an identifier and a
    /// button trait: it is something the native side genuinely *could* name. Without that the
    /// assertion would hold for the wrong reason.
    func testNativeCaptureDoesNotReportTapsOnComposeContent() {
        let app = launchHybridSample()
        let before = lastEventLabel(app)

        app.buttons["compose_button_in_hybrid"].tap()

        XCTAssertEqual(
            lastEventLabel(app),
            before,
            "the native pipeline reported a tap on Compose-owned content — the host boundary is not holding"
        )

        // Proves the native capture was alive for the assertion above, rather than never installed.
        app.buttons["native_button_in_hybrid"].tap()
        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: native_button_in_hybrid",
            "native capture reported nothing for a known-good tap either — the assertion above proved nothing"
        )
    }
}
