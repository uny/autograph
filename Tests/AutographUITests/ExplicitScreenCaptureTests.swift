import Autograph
@testable import AutographUI
import SwiftUI
import XCTest

/// Exercises the Kotlin `AutographScreenCapture` facade **through the umbrella framework, from Swift** —
/// the exact path `AutographUI`'s `.autographScreen` drives. A fast linkage + behaviour check to sit
/// under the sample-ios XCUITest that drives the real SwiftUI lifecycle.
final class ExplicitScreenCaptureTests: XCTestCase {

    /// In-memory `Tracker` (the umbrella's exported protocol, implemented in Swift the same way
    /// `AutographSegmentBridge` implements `SegmentBridge`) that records the screen views it receives.
    final class RecordingTracker: Tracker {
        var screens: [(name: String, previous: String?)] = []
        var onScreen: ((String) -> Void)?

        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {
            let previous = properties["previous_screen"].map { String(describing: $0) }
            screens.append((name, previous))
            onScreen?(name)
        }

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() {}
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}
    }

    func testAppearedEmitsScreenViewWithPreviousAndDisappearedIsIdempotent() {
        let tracker = RecordingTracker()
        let capture = AutographScreenCapture(tracker: tracker, scopeStack: ScopeStack())

        let first = capture.appeared(name: "First")
        let second = capture.appeared(name: "Second")

        XCTAssertEqual(tracker.screens.map(\.name), ["First", "Second"])
        // The first screen has no previous; the second carries the first (JsonPrimitive prints quoted).
        XCTAssertNil(tracker.screens[0].previous)
        XCTAssertEqual(tracker.screens[1].previous, "\"First\"")

        // disappeared() must be safe to call more than once, in any order.
        second.disappeared()
        first.disappeared()
        second.disappeared()
        first.disappeared()
    }

    /// Returning to a screen with nothing recorded in between must not name the screen its own
    /// previous_screen — the self-previous guard, mirrored from the UIKit swizzle.
    func testReEnteringTheSameScreenHasNoSelfPrevious() {
        let tracker = RecordingTracker()
        let capture = AutographScreenCapture(tracker: tracker, scopeStack: ScopeStack())

        capture.appeared(name: "Home").disappeared()
        // Nothing else recorded; re-entering Home sees Home as the last screen.
        capture.appeared(name: "Home")

        XCTAssertEqual(tracker.screens.map(\.name), ["Home", "Home"])
        XCTAssertNil(tracker.screens[0].previous)
        XCTAssertNil(tracker.screens[1].previous, "re-entry named itself as previous_screen")
    }

    /// Drives the **real** `AutographUI` `.autographScreen` modifier through an actual SwiftUI
    /// appearance: hosting the view in a window fires `onAppear`, which must call the facade and emit
    /// the screen. This is what proves the modifier's lifecycle binding, not just the facade in
    /// isolation — the binding to `onAppear`/`onDisappear` is exactly the class of thing unit tests of
    /// the Kotlin side can't see.
    @available(iOS 14.0, *)
    @MainActor
    func testRealModifierEmitsWhenHostedAndDrivenBySwiftUI() {
        let tracker = RecordingTracker()
        let capture = AutographScreenCapture(tracker: tracker, scopeStack: ScopeStack())

        let appeared = expectation(description: "onAppear reported the screen")
        tracker.onScreen = { if $0 == "Hosted" { appeared.fulfill() } }

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(
            rootView: Color.clear
                .autographScreen("Hosted")
                .autographScreenCapture(capture)
        )
        window.makeKeyAndVisible()

        wait(for: [appeared], timeout: 5)
        XCTAssertEqual(tracker.screens.map(\.name), ["Hosted"])

        window.isHidden = true
    }
}
