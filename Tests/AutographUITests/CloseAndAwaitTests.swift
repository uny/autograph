import Autograph
import XCTest

/// Drives `closeAndAwait` (#261) **from Swift, through the umbrella framework** — the shape an app sees.
///
/// Kotlin/Native exports a `suspend` function as a completion-handler method, which Swift 5.5+ imports as
/// `async throws`; the helper is a `Tracker` extension, so it surfaces as a static on `AwaitableCloseKt`
/// taking the tracker as its first argument. These tests pin that spelling (a rename or a changed
/// signature fails to compile here) and the two behaviours a Swift caller depends on: a tracker that does
/// not own the root is reported unsupported without being closed, and the real tracker reports a result.
final class CloseAndAwaitTests: XCTestCase {

    /// A Swift `Tracker` conformer that does not adopt `AwaitableCloseTracker` — it could only do so by
    /// delegating to a real tracker, since `CloseResult` has no public constructor — so it stands for any
    /// view or fake that does not own a root tracker.
    final class NonOwningTracker: Tracker {
        var closeCalls = 0

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {}
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() { closeCalls += 1 }
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}
    }

    /// A pipeline-stamping transport that only counts, so no envelope or sequence store is involved.
    final class CountingTransport: NSObject, Transport {
        var tracked = 0
        var flushes = 0
        var stampsInPipeline: Bool { true }

        func connect(envelopes: EnvelopeSource) {}
        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) { tracked += 1 }
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {}
        func flush() { flushes += 1 }
        func reset() {}
    }

    func testATrackerThatDoesNotOwnTheRootIsUnsupportedAndIsNotClosed() async throws {
        let tracker = NonOwningTracker()

        let result = try await AwaitableCloseKt.closeAndAwait(tracker)
        let explicit = try await AwaitableCloseKt.closeAndAwait(tracker, timeoutMillis: 1_000)

        XCTAssertTrue(result.isUnsupported)
        XCTAssertTrue(explicit.isUnsupported)
        XCTAssertFalse(result.cutoffReached)
        XCTAssertEqual(tracker.closeCalls, 0, "the helper must never fall back to close()")
    }

    func testTheRealTrackerClosesAndReportsWhatHappened() async throws {
        let transport = CountingTransport()
        let tracker = AutographKt.Autograph { config in
            config.transport(transport: transport)
            config.store = InMemorySeqStore()
        }
        tracker.track(name: "before", properties: [:], target: nil)

        XCTAssertTrue(tracker is AwaitableCloseTracker, "the capability is probed with `is`/`as?`, not added to Tracker")

        let result = try await AwaitableCloseKt.closeAndAwait(tracker)
        let again = try await AwaitableCloseKt.closeAndAwait(tracker, timeoutMillis: 0)

        XCTAssertTrue(result.cutoffReached)
        XCTAssertTrue(result.waitCompleted)
        XCTAssertFalse(result.timedOut)
        XCTAssertEqual(result.failedHandOffs, 0)
        XCTAssertTrue(result.flushRequested)
        XCTAssertFalse(result.flushFailed)
        XCTAssertEqual(transport.tracked, 1)
        XCTAssertEqual(transport.flushes, 1)
        // A repeated call waits on the same, already finished, shutdown.
        XCTAssertTrue(again.waitCompleted)
        XCTAssertEqual(transport.flushes, 1)
    }

    /// A negative timeout must not become a Kotlin exception escaping a suspend function that carries no
    /// `@Throws`: Kotlin/Native would abort the process instead of delivering an `NSError`.
    func testANegativeTimeoutIsTreatedAsNoWaitRatherThanCrashing() async throws {
        let tracker = AutographKt.Autograph { config in
            config.transport(transport: CountingTransport())
            config.store = InMemorySeqStore()
        }

        let result = try await AwaitableCloseKt.closeAndAwait(tracker, timeoutMillis: -1)

        XCTAssertTrue(result.cutoffReached)
    }

    func testTheShutdownBoundIsAPublicSettableConfigProperty() {
        var seen: [Int64] = []
        _ = AutographKt.Autograph { config in
            config.transport(transport: CountingTransport())
            config.store = InMemorySeqStore()
            seen.append(config.closeDrainTimeoutMillis)
            config.closeDrainTimeoutMillis = 1_234
            seen.append(config.closeDrainTimeoutMillis)
        }

        XCTAssertEqual(seen, [5_000, 1_234], "the default, then the value set")
    }

    /// A Swift class conforming to `AwaitableCloseTracker`, which has one `suspend` member. It can only
    /// forward to a real tracker's capability (`CloseResult` has no public constructor).
    final class ForwardingAwaitable: NSObject, AwaitableCloseTracker {
        let real: AwaitableCloseTracker
        var calls = 0
        init(_ real: AwaitableCloseTracker) { self.real = real }

        func closeAndAwait(timeoutMillis: Int64) async throws -> CloseResult {
            calls += 1
            return try await real.closeAndAwait(timeoutMillis: timeoutMillis)
        }
    }

    func testASwiftClassCanConformToAwaitableCloseTrackerByForwarding() async throws {
        let tracker = AutographKt.Autograph { config in
            config.transport(transport: CountingTransport())
            config.store = InMemorySeqStore()
        }
        let forwarding = ForwardingAwaitable(tracker as! AwaitableCloseTracker)

        let result = try await forwarding.closeAndAwait(timeoutMillis: 5_000)

        XCTAssertEqual(forwarding.calls, 1)
        XCTAssertTrue(result.waitCompleted)
    }

    /// A Swift class that is both a `Tracker` and an `AwaitableCloseTracker`, so the Kotlin helper
    /// (`is`-probe, then a Kotlin-to-Swift call of the suspend member) reaches the Swift implementation.
    final class ForwardingTracker: NSObject, Tracker, AwaitableCloseTracker {
        let real: Tracker
        var awaitCalls = 0
        init(_ real: Tracker) { self.real = real }

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) { real.track(name: name, properties: properties, target: target) }
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() { real.close() }
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}

        func closeAndAwait(timeoutMillis: Int64) async throws -> CloseResult {
            awaitCalls += 1
            return try await AwaitableCloseKt.closeAndAwait(real, timeoutMillis: timeoutMillis)
        }
    }

    func testTheKotlinHelperCallsASwiftConformersSuspendMember() async throws {
        let real = AutographKt.Autograph { config in
            config.transport(transport: CountingTransport())
            config.store = InMemorySeqStore()
        }
        let tracker = ForwardingTracker(real)

        let result = try await AwaitableCloseKt.closeAndAwait(tracker)

        XCTAssertEqual(tracker.awaitCalls, 1)
        XCTAssertTrue(result.waitCompleted)
    }
}
