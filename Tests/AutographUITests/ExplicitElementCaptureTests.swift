import Autograph
@testable import AutographUI
import SwiftUI
import XCTest

/// Exercises `AutographUI`'s explicit instrumentation — the `@Environment(\.autograph)` handle,
/// `.autographScope`, and `track(_:properties:target:)` — **through the umbrella framework, from Swift**,
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

    /// A recorded event carries its name, its call-site properties and its target.
    func testTheHandleRecordsNamePropertiesAndTarget() {
        let tracker = RecordingTracker()

        var handle = AutographTracking()
        handle.capture = capture(tracker)
        handle.track("save_tapped", properties: ["plan": "pro"], target: "save_button")

        XCTAssertEqual(tracker.events.map(\.name), ["save_tapped"])
        XCTAssertEqual(tracker.events.first?.target, "save_button")
        XCTAssertEqual(tracker.events.first?.properties["plan"], "pro")
    }

    /// The JSON overload forwards each argument to the slot it names.
    ///
    /// Worth its own test because every argument here is a `String` on both sides of the bridge: swapping
    /// `name` and `propertiesJson` — or dropping `scope` — compiles cleanly, and the Kotlin-side coverage
    /// of `clickedJson` cannot see a Swift wrapper that called it wrongly.
    func testTheJsonOverloadForwardsNameScopeAndProperties() {
        let tracker = RecordingTracker()

        var handle = AutographTracking()
        handle.capture = capture(tracker)
        handle.scope = ["article_id": "42"]
        handle.track("save_tapped", propertiesJson: #"{"count":3,"ok":true}"#, target: "save_button")

        XCTAssertEqual(tracker.events.map(\.name), ["save_tapped"])
        XCTAssertEqual(tracker.events.first?.target, "save_button")
        let properties = tracker.events.first?.properties
        XCTAssertEqual(properties?["count"], "3", "a non-string value must survive the JSON path")
        XCTAssertEqual(properties?["ok"], "true")
        XCTAssertEqual(properties?["article_id"], "42", "the ambient scope must reach a JSON-properties event")
    }

    /// A screen on the shared stack reaches an explicit event — the half of attribution that is
    /// deliberately still read dynamically.
    ///
    /// The screen is pushed through `AutographScreenCapture`, not `ScopeStack.push`, because that is
    /// the real path — `.autographScreen` drives it. `push` is no longer *unavailable*: it used to
    /// declare its scope as `JsonObject`, so passing a dictionary crashed the process at runtime, and
    /// #193 widened it to `Map<String, JsonElement>` (see `ScopeStackSwiftBridgeTests`). What remains
    /// is that Swift cannot construct a `JsonElement`, so only an empty scope is expressible — the
    /// same root cause as the properties bridge this feature exists to provide (see
    /// `AutographElementCapture`'s kdoc).
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
