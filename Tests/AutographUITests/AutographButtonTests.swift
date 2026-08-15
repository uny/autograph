import Autograph
@testable import AutographUI
import SwiftUI
import XCTest

/// Covers ``AutographButton``.
///
/// The initializer surface is checked by this file **compiling**: all three forms are constructed below,
/// including a bare string literal, which is the case that would break if the `LocalizedStringKey` and
/// `StringProtocol` overloads ever became ambiguous. That is not a runtime assertion and does not need
/// to be — an ambiguity is a build failure, which is the strongest form this check can take.
final class AutographButtonTests: XCTestCase {

    final class RecordingTracker: Tracker {
        var events: [(name: String, properties: [String: String], target: String?)] = []
        var onTrack: ((String) -> Void)?

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {
            events.append((name, properties.mapValues { String(describing: $0) }, target))
            onTrack?(name)
        }

        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() {}
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}
    }

    /// Every initializer, including the string-literal case that pins the overload pair. A test body that
    /// only builds views looks weak, but the build *is* the assertion here.
    @MainActor
    func testTheWholeInitializerSurfaceCompilesAndRenders() {
        let tracker = RecordingTracker()

        let window = host(
            VStack {
                // 1. bare literal — resolves to the LocalizedStringKey overload, as `Button` does
                AutographButton("Save", event: "save_tapped", target: "save_button") {}

                // 2. a runtime String — resolves to the StringProtocol overload
                AutographButton(String("Delete"), event: "delete_tapped") {}

                // 3. free-form label
                AutographButton(event: "share_tapped", properties: ["channel": "sms"]) {} label: {
                    HStack { Image(systemName: "square.and.arrow.up"); Text("Share") }
                }
            }
            .autographElementCapture(AutographElementCapture(tracker: tracker, scopeStack: ScopeStack()))
        )

        XCTAssertNotNil(window.rootViewController?.view)
        window.isHidden = true
    }

    /// Modifiers applied from outside must reach the wrapped `Button`. `.disabled` is the one that
    /// matters: if it did not travel, an instrumented button could record clicks while disabled — the
    /// invariant #128/#134 established. Verified here as *composition* (the view still builds and hosts);
    /// that the disabled button records nothing under a real touch is measured by the XCUITest, because a
    /// touch is what the claim is about and no unit test can fabricate one.
    @MainActor
    func testExternalModifiersCompose() {
        let tracker = RecordingTracker()

        let window = host(
            AutographButton("Save", event: "save_tapped") {}
                .disabled(true)
                .buttonStyle(.plain)
                .autographElementCapture(AutographElementCapture(tracker: tracker, scopeStack: ScopeStack()))
        )

        XCTAssertNotNil(window.rootViewController?.view)
        XCTAssertTrue(tracker.events.isEmpty, "rendering alone must not record anything")
        window.isHidden = true
    }

    /// The event an ``AutographButton`` records carries the subtree's scope, exactly as one recorded by
    /// hand does — the two entry points must not diverge in what they attach.
    ///
    /// Driven through the handle the button reads rather than through the button, because **the button's
    /// action cannot be reached from a unit test**: a `UITouch` has no constructible form, and activating
    /// it through the accessibility action was tried and does not work headlessly — SwiftUI exposes no
    /// activatable element until an accessibility client has warmed the process (the cold-tree behaviour
    /// #134/#135 measured). A test that skipped on that would report "covered" while never running, so
    /// what a real activation proves — that the action is wired to the recording, that recording happens
    /// **before** the caller's work, and that a disabled button records nothing — is the XCUITest's job,
    /// and is not claimed here.
    @MainActor
    func testTheButtonAttachesTheSameContextAsRecordingByHand() {
        let tracker = RecordingTracker()

        var handle = AutographTracking()
        handle.capture = AutographElementCapture(tracker: tracker, scopeStack: ScopeStack())
        handle.scope = ["article_id": "42"]

        handle.track("save_tapped", properties: ["plan": "pro"], target: "save_button")

        let event = try? XCTUnwrap(tracker.events.first)
        XCTAssertEqual(event?.name, "save_tapped")
        XCTAssertEqual(event?.target, "save_button")
        XCTAssertEqual(event?.properties["article_id"], "\"42\"")
        XCTAssertEqual(event?.properties["plan"], "\"pro\"")
    }

    @MainActor
    private func host<V: View>(_ view: V) -> UIWindow {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        return window
    }
}
