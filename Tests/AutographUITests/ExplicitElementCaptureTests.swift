import Autograph
@testable import AutographUI
import SwiftUI
import XCTest

/// Exercises `AutographUI`'s explicit instrumentation — the `@Environment(\.autograph)` handle,
/// `.autographScope`, and the `tracked(_:)` decorator — **through the umbrella framework, from Swift**,
/// the exact path a consuming app drives.
///
/// The tests that host a real `UIHostingController` are the load-bearing ones: the lexical scope channel
/// is a claim about how SwiftUI's environment propagates, and only SwiftUI can settle that. A test that
/// built the handle by hand would pass no matter what `.autographScope` actually does.
final class ExplicitElementCaptureTests: XCTestCase {

    /// In-memory `Tracker` recording the events it receives, with properties flattened to strings —
    /// `JsonElement` has no Swift-constructible form, and its `description` is the only thing a Swift
    /// caller can read out of it.
    final class RecordingTracker: Tracker {
        var events: [(name: String, properties: [String: String], target: String?)] = []
        var onTrack: ((String) -> Void)?

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {
            let flattened = properties.mapValues { String(describing: $0).trimmingCharacters(in: ["\""]) }
            events.append((name, flattened, target))
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

    private func capture(_ tracker: Tracker, _ stack: ScopeStack = ScopeStack()) -> AutographElementCapture {
        AutographElementCapture(tracker: tracker, scopeStack: stack)
    }

    /// The decorator records **before** it calls the action, matching `Modifier.trackClick`'s order.
    /// The observed sequence is the assertion: checking only that both happened would pass either way.
    func testTrackedRecordsBeforeItRunsTheAction() {
        let tracker = RecordingTracker()
        var sequence: [String] = []
        tracker.onTrack = { _ in sequence.append("tracked") }

        var handle = AutographTracking()
        handle.capture = capture(tracker)

        let action = handle.tracked("save_tapped", target: "save_button") { sequence.append("action") }
        action()

        XCTAssertEqual(sequence, ["tracked", "action"])
        XCTAssertEqual(tracker.events.map(\.name), ["save_tapped"])
        XCTAssertEqual(tracker.events.first?.target, "save_button")
    }

    /// A screen on the shared stack reaches an explicit event — the half of attribution that is
    /// deliberately still read dynamically.
    ///
    /// The screen is pushed through `AutographScreenCapture`, not `ScopeStack.push`, because that is both
    /// the real path (`.autographScreen` drives it) and the only one available: `push` takes a
    /// `JsonObject` for its scope, which Swift cannot construct — passing a dictionary **crashes at
    /// runtime** rather than failing to compile. Same root cause as the properties bridge this feature
    /// exists to provide (see `AutographElementCapture`'s kdoc).
    func testScreenFromTheSharedStackIsAttached() {
        let tracker = RecordingTracker()
        let stack = ScopeStack()
        AutographScreenCapture(tracker: tracker, scopeStack: stack).appeared(name: "ArticleDetail")

        var handle = AutographTracking()
        handle.capture = capture(tracker, stack)
        handle.track("save_tapped")

        XCTAssertEqual(tracker.events.first?.properties["screen"], "ArticleDetail")
    }

    /// `.autographScope` through real SwiftUI: nesting merges outer-then-inner and the inner key wins,
    /// while a call site's own property still beats both.
    @available(iOS 14.0, *)
    @MainActor
    func testNestedScopesMergeWithInnerWinningWhenDrivenBySwiftUI() {
        let tracker = RecordingTracker()
        let recorded = expectation(description: "the nested scope reached the event")
        tracker.onTrack = { _ in recorded.fulfill() }

        let window = host(
            Recorder(event: "nested", properties: ["shared": "call_site"])
                .autographScope(["depth": "inner", "shared": "inner"])
                .autographScope(["section": "feed", "depth": "outer", "shared": "outer"])
                .autographElementCapture(capture(tracker))
        )

        wait(for: [recorded], timeout: 5)
        let properties = tracker.events.first?.properties
        XCTAssertEqual(properties?["section"], "feed", "an outer-only key must survive")
        XCTAssertEqual(properties?["depth"], "inner", "the inner scope must win over the outer")
        XCTAssertEqual(properties?["shared"], "call_site", "the call site must win over every scope")

        window.isHidden = true
    }

    /// The reason scope travels lexically instead of through `ScopeStack`: two sibling subtrees are
    /// mounted at once, each with its own scope, and each event must carry *its own* row. Resolving this
    /// from the stack would drop both as ambiguous — there is no tap position to choose between them.
    @available(iOS 14.0, *)
    @MainActor
    func testSiblingScopesEachAttributeToTheirOwnSubtree() {
        let tracker = RecordingTracker()
        let recorded = expectation(description: "both siblings recorded")
        recorded.expectedFulfillmentCount = 2
        tracker.onTrack = { _ in recorded.fulfill() }

        let window = host(
            VStack {
                Recorder(event: "row").autographScope(["row": "first"])
                Recorder(event: "row").autographScope(["row": "second"])
            }
            .autographElementCapture(capture(tracker))
        )

        wait(for: [recorded], timeout: 5)
        XCTAssertEqual(
            Set(tracker.events.map { $0.properties["row"] }), ["first", "second"],
            "sibling scopes collapsed — each subtree must keep its own"
        )

        window.isHidden = true
    }

    @available(iOS 14.0, *)
    @MainActor
    private func host<V: View>(_ view: V) -> UIWindow {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        return window
    }
}

/// Records once it is on screen, reading the handle exactly as a consuming view would. `onAppear` rather
/// than `body` so it fires once per appearance rather than once per evaluation.
@available(iOS 14.0, *)
private struct Recorder: View {
    let event: String
    var properties: [String: String] = [:]

    @Environment(\.autograph) private var autograph

    var body: some View {
        Color.clear.onAppear {
            autograph.track(event, properties: properties)
        }
    }
}
