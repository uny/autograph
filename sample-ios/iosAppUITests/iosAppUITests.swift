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

    /// The full properties JSON an event was attributed with — screen, section, and scope keys — which
    /// the `target`-only label cannot show. See `App.kt`'s `last_event_props_label`.
    private func lastEventProps(_ app: XCUIApplication) -> String {
        app.staticTexts["last_event_props_label"].label
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

    /// An autocaptured tap must carry the screen, section, and scope it happened under — not just its
    /// target. The sample wraps its content in `AutographScope("article_id" to "42")` +
    /// `TrackedScreen("Sample", section = "Main")`, all of which mirror into the ambient stack the
    /// autocapture observer reads. Before the harness widening, this context was surfaced nowhere and
    /// the swizzle work (#65) would have had no way to verify its output on-device.
    func testAutocapturedTapCarriesScreenSectionAndScope() {
        let app = XCUIApplication()
        app.launch()
        app.buttons["plain_button"].tap()
        // Pin the props to the TAP's own event. The launch-time trackImpression is itself enriched
        // with the same screen/section/scope, so `last_event_props_label` already satisfies the
        // assertions below before any tap — asserting the last event was the plain_button tap
        // (target label, whose value only the tap produces) is what makes this observe the tap and
        // not the stale impression. Without it, a tap that fired no event at all would pass green.
        XCTAssertEqual(lastEventLabel(app), "Last event target: plain_button")
        let props = lastEventProps(app)
        XCTAssertTrue(props.contains("\"screen\":\"Sample\""), "screen missing from props: \(props)")
        XCTAssertTrue(props.contains("\"section\":\"Main\""), "section missing from props: \(props)")
        XCTAssertTrue(props.contains("\"article_id\":\"42\""), "scope missing from props: \(props)")
    }

    /// The Screen Viewed channel the native screen capture (#65) reports through, proven on the Compose
    /// side first: `TrackedScreen` fires exactly one Screen Viewed on entry, with no `previous_screen`
    /// (it is the first screen). The ordered log — not a last-value label — is what will later let a
    /// test see a screen was not double-emitted.
    func testScreenViewIsObservableAndFiresOnce() {
        let app = XCUIApplication()
        app.launch()
        // TrackedScreen fires its Screen Viewed from a composition effect, so the label recomposes
        // from "(none yet)" a beat after launch. Wait for the value rather than reading it eagerly.
        let label = app.staticTexts["screen_view_log_label"]
        let expected = "Screen views: Sample:(none)"
        expectation(for: NSPredicate(format: "label == %@", expected), evaluatedWith: label)
        waitForExpectations(timeout: 5)
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

/// Coverage for #65's **explicit** SwiftUI screen API (`.autographScreen`) on a real `NavigationStack`
/// (`SwiftUIScreensView` in `ContentView.swift`). The UIKit swizzle can't see SwiftUI screens, so they
/// name themselves; this drives the same Kotlin facade the shipped `AutographUI` modifier drives (via a
/// shape-identical sample modifier — see `AutographSampleScreen`) through real synthetic navigation.
///
/// SwiftUI delivers `onAppear`/`onDisappear` in an order unit tests can't see (measured: on push the
/// destination appears *before* the source disappears; on pop the parent re-appears), so this reads the
/// cumulative `name:previous_screen` log after real pushes and pops. The log is exact, so a screen that
/// wrongly named itself its own `previous_screen`, or a missed re-appearance, fails the test.
final class SwiftUIScreensUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-swiftui-screens"]
        app.launch()
        return app
    }

    private func waitForScreenLog(_ app: XCUIApplication, _ expected: String) {
        expectation(
            for: NSPredicate(format: "label == %@", "Screen views: \(expected)"),
            evaluatedWith: app.staticTexts["swiftui_screen_view_log_label"]
        )
        waitForExpectations(timeout: 5)
    }

    /// The first SwiftUI screen fires exactly one Screen Viewed on entry, with no previous_screen.
    func testFirstScreenFiresOnEntry() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
    }

    /// Pushing a destination reports it, carrying the screen it replaced as previous_screen — even
    /// though SwiftUI fires the destination's onAppear before the source's onDisappear.
    func testPushReportsSecondWithPrevious() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
        app.buttons["swiftui_go_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst")
    }

    /// Popping re-shows First as a fresh screen view whose previous_screen is Second.
    func testPopReReportsFirst() {
        let app = launch()
        app.buttons["swiftui_go_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst")
        app.buttons["swiftui_back_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst|SwiftUIFirst:SwiftUISecond")
    }

    /// Returning to First from an *untracked* screen (one with no `.autographScreen`) must not name
    /// First its own previous_screen: nothing capturable was recorded in between, so `record` sees
    /// First as the last screen and the self-previous guard drops the previous to none. This is the
    /// SwiftUI mirror of `testReturnFromExcludedScreenHasNoSelfPrevious`.
    func testReturnFromUntrackedHasNoSelfPrevious() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
        app.buttons["swiftui_go_untracked"].tap()
        // The untracked screen reports nothing; returning brings First back with previous = none.
        app.buttons["swiftui_back_untracked"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUIFirst:(none)")
    }
}

/// Coverage for #65's native **screen** capture — the `viewDidAppear:` swizzle — on a real UIKit
/// `UIViewController` hierarchy (`NativeScreensRootView` in `ContentView.swift`).
///
/// This is the surface that class of work has to reach: screen capture is lifecycle-driven, and every
/// lifecycle defect this library has shipped (a scroll's stale begin position, a passthrough overlay,
/// a screen frame that leaks) was invisible to unit tests and only surfaced under real synthetic
/// touches. Each test drives real pushes, presents and tab switches and reads the cumulative
/// `Screen Viewed` log the sample surfaces (a UI test cannot read Kotlin state).
///
/// The log is `name:previous_screen` entries joined by `|`. Because every assertion pins the *exact*
/// log, a container controller that wrongly reported itself (a `UINavigationController`, a
/// `UITabBarController`) would show up as an extra entry and fail the test — the exclusion of those is
/// checked here, not just asserted in a comment.
final class NativeScreensUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-native-screens"]
        app.launch()
        return app
    }

    /// Waits for the cumulative screen-view log to reach [expected]. The log updates from a
    /// `viewDidAppear:`-driven callback a beat after each transition, so this waits rather than reading
    /// eagerly — and, being an exact match, fails if a container controller added a spurious entry.
    ///
    /// `.firstMatch`, not a bare subscript: a modal presented `.overFullScreen` leaves its presenter in
    /// the accessibility tree, so two labels carry this identifier at once. Both show the same value
    /// (the sample updates every screen's labels on every event), so taking the first is correct — and
    /// a bare `app.staticTexts["id"]` would be an ambiguous query that never resolves in the predicate.
    private func screenLog(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(identifier: "native_screen_view_log_label").firstMatch
    }

    private func waitForScreenLog(_ app: XCUIApplication, _ expected: String) {
        expectation(
            for: NSPredicate(format: "label == %@", "Screen views: \(expected)"),
            evaluatedWith: screenLog(app)
        )
        waitForExpectations(timeout: 5)
    }

    private func lastTarget(_ app: XCUIApplication) -> String {
        app.staticTexts.matching(identifier: "native_last_event_label").firstMatch.label
    }

    private func lastProps(_ app: XCUIApplication) -> String {
        app.staticTexts.matching(identifier: "native_last_event_props_label").firstMatch.label
    }

    /// The first UIKit screen fires exactly one Screen Viewed on entry, with no previous_screen — and
    /// neither the hosting controller nor the navigation controller reports one alongside it.
    func testFirstScreenFiresOnEntry() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
    }

    /// Pushing a controller reports the new screen, carrying the one it replaced as previous_screen.
    /// One push produces exactly one new entry — the navigation container is not itself a screen.
    func testPushReportsTheNewScreenWithPrevious() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_push_second"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen")
    }

    /// Popping re-shows First as a fresh screen view whose previous_screen is Second. A re-appearance
    /// is a screen view; the dedup rule only skips a re-fire while the *same* controller's frame is
    /// still live (a cancelled interactive pop), which a completed pop is not.
    func testPopReReportsFirst() {
        let app = launch()
        app.buttons["native_push_second"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen")
        app.buttons["native_pop"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen|FirstScreen:SecondScreen")
    }

    /// A native tap on a UIKit screen carries the `screen` the swizzle pushed onto the shared stack —
    /// and, deliberately, no `section` (a `UIViewController` has no section; section is Compose-only).
    /// This is the assertion the removed `testNativeTapCarriesScreenAndSection` used to make against a
    /// hand-pushed fixture frame; it now rides a real swizzle-produced frame. The target is pinned
    /// first so the props are read off the tap's own event, not a stale one.
    func testNativeTapCarriesScreenAndNoSection() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_first_button"].tap()
        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")
        let props = lastProps(app)
        XCTAssertTrue(props.contains("\"screen\":\"FirstScreen\""), "screen missing from props: \(props)")
        XCTAssertFalse(props.contains("\"section\""), "a native screen must carry no section: \(props)")
    }

    /// A modal presented *over* its presenter (`.overFullScreen`) stacks on top and restores the
    /// presenter on dismiss. The presenter (`FirstScreen`) gets no `viewDidDisappear:` on present nor
    /// `viewDidAppear:` on dismiss, so its frame is never removed and never re-added: the screen-view
    /// log gains `SheetScreen:FirstScreen` but *not* a second `FirstScreen` on dismiss, and a tap after
    /// dismiss carries `FirstScreen` again because its frame was underneath the whole time. A single
    /// "current screen" slot could not do this — it would drop to nothing once the sheet closed.
    func testModalOverPresenterStacksAndRestoresIt() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")

        app.buttons["native_present_sheet"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SheetScreen:FirstScreen")
        app.buttons["native_sheet_button"].tap()
        XCTAssertTrue(
            lastProps(app).contains("\"screen\":\"SheetScreen\""),
            "a tap on the sheet should carry screen=SheetScreen: \(lastProps(app))"
        )

        app.buttons["native_dismiss_sheet"].tap()
        // No new screen view on dismiss (the presenter got no viewDidAppear:); the log is unchanged.
        // FirstScreen is proven restored by a tap carrying its screen again.
        waitForScreenLog(app, "FirstScreen:(none)|SheetScreen:FirstScreen")
        app.buttons["native_first_button"].tap()
        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")
        XCTAssertTrue(
            lastProps(app).contains("\"screen\":\"FirstScreen\""),
            "after dismiss the presenter's frame should be current again: \(lastProps(app))"
        )
    }

    /// Returning to a screen from an *excluded* one (here a SwiftUI `UIHostingController` modal, which
    /// the capture skips) must not name the screen as its own `previous_screen`. FirstScreen disappears
    /// and reappears around the excluded modal with nothing capturable recorded in between, so
    /// `record` sees FirstScreen as the last screen — the re-entry emits FirstScreen again but with no
    /// previous, not `FirstScreen:FirstScreen`.
    func testReturnFromExcludedScreenHasNoSelfPrevious() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_present_excluded"].tap()
        // The excluded modal reports no screen view; the dismiss brings FirstScreen back as a fresh
        // view whose previous is unknown (none), never itself.
        app.buttons["native_dismiss_excluded"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|FirstScreen:(none)")
    }

    /// Switching tabs reports the newly selected content controller. The `UITabBarController` itself is
    /// a container and reports nothing; only `TabA` (shown first) and then `TabB` do.
    func testTabSwitchReportsTheSelectedTab() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_present_tabs"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|TabAScreen:FirstScreen")
        app.tabBars.buttons["TabB"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|TabAScreen:FirstScreen|TabBScreen:TabAScreen")
    }
}
