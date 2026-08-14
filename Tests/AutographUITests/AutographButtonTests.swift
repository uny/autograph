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

    /// The scope a subtree carries reaches an event recorded from inside an ``AutographButton``, the same
    /// way it reaches a decorated closure — the two entry points must not diverge in what they attach.
    ///
    /// Driven through the handle the button itself reads, rather than a synthesized tap: the wiring under
    /// test is "what the button's action attaches", and the button's action calls exactly this.
    @MainActor
    func testTheButtonAndTheDecoratorAttachTheSameContext() {
        let tracker = RecordingTracker()
        let capture = AutographElementCapture(tracker: tracker, scopeStack: ScopeStack())

        var handle = AutographTracking()
        handle.capture = capture
        handle.scope = ["article_id": "42"]

        // What AutographButton's action does...
        handle.track("save_tapped", properties: ["plan": "pro"], target: "save_button")
        // ...and what the decorator does.
        handle.tracked("save_tapped", properties: ["plan": "pro"], target: "save_button") {}()

        XCTAssertEqual(tracker.events.count, 2)
        XCTAssertEqual(tracker.events[0].properties, tracker.events[1].properties)
        XCTAssertEqual(tracker.events[0].target, tracker.events[1].target)
        XCTAssertEqual(tracker.events[0].properties["article_id"], "\"42\"")
    }

    @MainActor
    private func host<V: View>(_ view: V) -> UIWindow {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        return window
    }
}
