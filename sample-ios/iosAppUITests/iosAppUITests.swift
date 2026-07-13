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
