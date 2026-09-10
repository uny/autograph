import Autograph
import XCTest

/// Calls the Kotlin ambient-context surface **from Swift**, which is the only place the defect in
/// #193 was reachable.
///
/// Every assertion here is secondary. What these tests actually check is that the process is still
/// alive afterwards: before the fix, `ScopeStack.push` declared its `scope` parameter as `JsonObject`
/// — a *subtype* of `Map<String, JsonElement>` — and Kotlin/Native handed a Swift dictionary into it
/// as an `NSDictionaryAsKMap` with no check, so reading it through the declared type died with
/// SIGSEGV. The failure mode is a **process death, not a test failure**: the whole host goes down,
/// XCTest reports "Restarting after unexpected exit", and no Kotlin exception, log line, or crash
/// report is produced. So a regression here looks like a vanished test run rather than a red
/// assertion — that is expected, and it is still a reliable signal.
///
/// A Kotlin unit test cannot cover this: the bug lives entirely in the Objective-C bridge, and a
/// Kotlin caller passes a real `JsonObject` and never sees it.
final class ScopeStackSwiftBridgeTests: XCTestCase {

    func testPushAcceptsASwiftDictionary() {
        let stack = ScopeStack()
        _ = stack.push(scope: [:], screen: "Checkout", section: nil, parent: nil)
        XCTAssertEqual(stack.current().screen, "Checkout")
    }

    /// The scope handed back out of Kotlin is converted to a *copy* on the way, so it returns as a
    /// plain `NSDictionary` and is no safer to pass back in than a Swift literal. Measured: this
    /// crashed before the fix too.
    func testPushAcceptsAScopeThatCameBackFromKotlin() {
        let stack = ScopeStack()
        _ = stack.push(scope: stack.current().scope, screen: "Cart", section: nil, parent: nil)
        XCTAssertEqual(stack.current().screen, "Cart")
    }

    func testUpdateAcceptsASwiftDictionary() {
        let stack = ScopeStack()
        let handle = stack.push(scope: [:], screen: "Cart", section: nil, parent: nil)
        stack.update(handle: handle, scope: [:], screen: "Cart", section: "Summary", parent: nil)
        XCTAssertEqual(stack.current().section, "Summary")
    }

    func testRemoveAndSectionOnlyFrameResolveThroughTheBridge() {
        let stack = ScopeStack()
        let screen = stack.push(scope: [:], screen: "Cart", section: nil, parent: nil)
        let section = stack.push(scope: [:], screen: nil, section: "Summary", parent: screen)
        XCTAssertEqual(stack.current().section, "Summary")
        stack.remove(handle: section)
        XCTAssertNil(stack.current().section)
    }

    /// `enrich` survived the defect only by accident — it never read its parameter through the
    /// declared type. It is covered here so that stays true by test rather than by luck.
    func testEnrichAcceptsASwiftDictionary() {
        let stack = ScopeStack()
        _ = stack.push(scope: [:], screen: "Cart", section: nil, parent: nil)
        let enriched = stack.current().enrich(properties: [:])
        XCTAssertEqual(enriched.count, 1, "expected the reserved screen key to be written")
    }

    // MARK: - Frame switches

    /// The two switches added alongside the batched form, called from Swift at all — the bridge is
    /// the only place their exported shape is observable, and a Kotlin test cannot see it.
    func testMaskAndSingleSetActiveCrossTheBridge() {
        let stack = ScopeStack()
        let feed = stack.push(scope: [:], screen: "Feed", section: nil, parent: nil)
        let mask = stack.push(scope: [:], screen: nil, section: nil, parent: nil)

        stack.maskScreen(handle: mask)
        XCTAssertNil(stack.current().screen)
        XCTAssertTrue(stack.current().screenMasked)

        stack.setActive(handle: mask, active: false)
        XCTAssertEqual(stack.current().screen, "Feed")
        XCTAssertFalse(stack.current().screenMasked)

        stack.setActive(handle: feed, active: false)
        XCTAssertNil(stack.current().screen)
    }

    /// The batched overload takes `List<ScopeHandle>`, and the reason is only visible from here:
    /// Kotlin/Native maps `List`/`Set`/`Map` to Objective-C collections and nothing else, so the
    /// `Collection<ScopeHandle>` this shipped as first exported as an untyped `id` — `Any` in Swift.
    /// A Swift array satisfied it by luck, and `stack.setActive(handles: "oops", active: false)`
    /// compiled just as happily. Note what this test does and does not buy: a `[ScopeHandle]`
    /// satisfies `Any` too, so re-widening the parameter would leave this compiling and green. The
    /// guard against that is the `api/` dumps, which record the parameter type. What this covers is
    /// the bridge actually working — that a Swift array survives the crossing and the batch lands.
    func testBatchedSetActiveTakesATypedSwiftArray() {
        let stack = ScopeStack()
        _ = stack.push(scope: [:], screen: "Feed", section: nil, parent: nil)
        let screen = stack.push(scope: [:], screen: "Page2", section: nil, parent: nil)
        let mask = stack.push(scope: [:], screen: nil, section: nil, parent: nil)
        stack.maskScreen(handle: mask)

        let owned: [ScopeHandle] = [screen, mask]
        stack.setActive(handles: owned, active: false)
        XCTAssertEqual(stack.current().screen, "Feed", "the whole surface left in one publish")

        stack.setActive(handles: owned, active: true)
        XCTAssertNil(stack.current().screen, "and came back masked, in one publish")
        XCTAssertTrue(stack.current().screenMasked)
    }

    // MARK: - Tracker / Transport

    /// Records what the Kotlin core hands a transport, so the Swift-entered dictionary can be
    /// followed all the way through. Implementing `Transport` in Swift is itself half the point:
    /// it is the direction `@HiddenFromObjC` would have broken.
    private final class RecordingTransport: Transport {
        var calls: [String] = []
        // Kotlin's default bodies do not survive into Objective-C — every member is `@required`
        // there — so a Swift implementer restates them.
        var stampsInPipeline: Bool { true }
        func connect(envelopes: EnvelopeSource) {}
        func flush() {}
        func reset() {}
        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {
            calls.append("track:\(name):\(properties.count)")
        }
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {
            calls.append("screen:\(name):\(properties.count)")
        }
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {
            calls.append("identify:\(userId):\(traits.count)")
        }
    }

    /// `Tracker.track`/`screen`/`identify` take the same widened parameter as `ScopeStack.push` and
    /// carry the same warning in their kdoc, but nothing called them *from Swift* — so re-narrowing
    /// any of them to `JsonObject` would compile, pass every Kotlin test (Kotlin callers always pass
    /// a real `JsonObject`) and reintroduce the crash. Same process-death signal as above.
    func testTrackerAcceptsSwiftDictionariesOnEveryEntryPoint() {
        let transport = RecordingTransport()
        let tracker = AutographKt.Autograph { config in config.transport(transport: transport) }
        defer { tracker.close() }

        tracker.track(name: "Upgraded", properties: [:], target: "cta")
        tracker.screen(name: "Checkout", properties: [:])
        tracker.identify(userId: "u1", traits: [:])

        // stampsInPipeline = true, so delivery is synchronous on this thread.
        XCTAssertEqual(transport.calls, ["track:Upgraded:1", "screen:Checkout:0", "identify:u1:0"])
    }

    /// The `Transport` half, entered directly rather than through the core — `DebugTransport` is a
    /// public Kotlin implementation Swift can construct, so it exercises the widened parameter
    /// without the core narrowing the dictionary first.
    func testTransportAcceptsASwiftDictionary() {
        let delegate = RecordingTransport()
        let debug = DebugTransport(delegate: delegate, log: { _ in })

        debug.track(name: "Upgraded", properties: [:], envelope: nil)
        debug.screen(name: "Checkout", properties: [:], envelope: nil)
        debug.identify(userId: "u1", traits: [:], envelope: nil)

        XCTAssertEqual(delegate.calls, ["track:Upgraded:0", "screen:Checkout:0", "identify:u1:0"])
    }
}
