import Autograph
import AutographUI
import SwiftUI

/// Launch argument for the SwiftUI **explicit instrumentation** sample — `AutographButton` and
/// `autograph.track(_:)`, the whole public surface of that feature.
let swiftUIExplicitLaunchArgument = "-autograph-swiftui-explicit"

/// The wiring *and* the observable event log for the explicit-instrumentation screen: one `ScopeStack`
/// shared by the element capture and the screen capture, exactly as the documentation tells an app to
/// do it, plus the ordered log a UI test reads (it cannot read Kotlin — or Swift — state directly).
///
/// This is the one screen in the sample app that links the shipped `AutographUI` product directly,
/// rather than driving a shape-identical copy through a `sample_shared` facade the way
/// `AutographSampleScreen` does. That difference is deliberate and load-bearing: for `.autographScreen`
/// the shipped Swift modifier is glue over a Kotlin facade, so exercising the facade exercises what
/// ships — but `AutographButton` has no Kotlin half. That a `.disabled` button records nothing, and that
/// the recording precedes the action, are properties of the *Swift* code, and a copy of it here would
/// verify only the copy. So the app links `Autograph.xcframework` (through the local SwiftPM package)
/// alongside `sample_shared.framework`, and this screen wires the real types up as a consuming app does.
///
/// Nothing is shared with the Compose sample: its own `Tracker`, its own `ScopeStack`, its own capture.
/// Kotlin/Native prefixes every exported class with its framework's name, so the two frameworks in this
/// process expose distinct Objective-C types for the same Kotlin classes and could not share state even
/// if that were wanted.
final class SwiftUIExplicitWiring: ObservableObject {

    /// `name:target` per entry, joined by `|`, in the order the tracker received them — interleaved with
    /// `action:…` entries appended by a button's own action. The order *between* those two kinds is the
    /// point: it shows recording happens before the action, which nothing off-device can observe.
    @Published var log = noEntries

    /// The target of the last recorded event on its own. The ordered log cannot serve as the thing a
    /// test *waits* on — waiting for an expected log would pass on a duplicate that had simply not been
    /// appended yet — so assertions wait for this positive signal and then read the log eagerly.
    @Published var lastTarget = noEntries

    /// The full properties of the last recorded event, so scope and screen attribution are observable
    /// rather than just the target.
    @Published var lastProps = noEntries

    static let noEntries = "(none yet)"

    private(set) var elementCapture: AutographElementCapture!
    private(set) var screenCapture: AutographScreenCapture!

    init() {
        let tracker = SwiftUIExplicitTracker(wiring: self)
        // A bare `ScopeStack()`: #193 — `ScopeStack.push` kills the process when called from Swift, so
        // the screen name reaches the stack only through `AutographScreenCapture.appeared`, which
        // `.autographScreen` drives.
        let stack = ScopeStack()
        elementCapture = AutographElementCapture(tracker: tracker, scopeStack: stack)
        screenCapture = AutographScreenCapture(tracker: tracker, scopeStack: stack)
    }

    func recordTrack(_ name: String, _ target: String?, _ properties: String) {
        append("\(name):\(target ?? "(none)")")
        lastTarget = target ?? "(none)"
        lastProps = properties
    }

    /// Appended by a button's own action, so it lands in the same ordered log as the tracker's entry.
    func recordAction(_ target: String) {
        append("action:\(target)")
    }

    private func append(_ entry: String) {
        log = log == Self.noEntries ? entry : "\(log)|\(entry)"
    }
}

/// A `Tracker` written in Swift against the umbrella framework's exported protocol — what an app not
/// using the Segment transport would write.
///
/// Properties are flattened with `String(describing:)`: `JsonElement` has no Swift-constructible form
/// and no accessor, so its description is the only thing Swift can read out of it. Enough here, where
/// the assertions are about which keys and values arrived.
private final class SwiftUIExplicitTracker: Tracker {
    /// Weak: the wiring owns the captures, which own this tracker. Strong here would be a cycle.
    private weak var wiring: SwiftUIExplicitWiring?

    init(wiring: SwiftUIExplicitWiring) {
        self.wiring = wiring
    }

    func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {
        let rendered = properties
            .map { "\"\($0.key)\":\(String(describing: $0.value))" }
            .sorted()
            .joined(separator: ",")
        wiring?.recordTrack(name, target, "{\(rendered)}")
    }

    // A screen view is reported by `.autographScreen` below, and is not what this screen asserts on, so
    // it is accepted and dropped. The screen still reaches click events through the shared ScopeStack,
    // which is what the props assertions read.
    func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
    func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
    func close() {}
    func flush() {}
    func reset() {}
    func notifyForeground() {}
    func notifyBackground() {}
}

struct SwiftUIExplicitSampleView: View {
    @StateObject private var wiring = SwiftUIExplicitWiring()

    var body: some View {
        // The captures are provided *here* and consumed by the child, not set and read in one chain:
        // a modifier reads the environment its parent supplies, so `.autographScreen` applied after
        // `.autographScreenCapture` on the same view would still see no capture (and say so loudly).
        // Which is the arrangement the README's snippet shows — root, then screen — for this reason.
        SwiftUIExplicitContent(wiring: wiring)
            .autographElementCapture(wiring.elementCapture)
            .autographScreenCapture(wiring.screenCapture)
    }
}

private struct SwiftUIExplicitContent: View {
    @ObservedObject var wiring: SwiftUIExplicitWiring

    var body: some View {
        VStack(spacing: 8) {
            Text("Tracks: \(wiring.log)")
                .accessibilityIdentifier("swiftui_explicit_track_log_label")
            Text("Last event target: \(wiring.lastTarget)")
                .accessibilityIdentifier("swiftui_explicit_last_event_label")
            Text("Last event props: \(wiring.lastProps)")
                .accessibilityIdentifier("swiftui_explicit_props_label")

            // Carries a call-site property deliberately: this is the ONLY `AutographButton` in the app
            // that fires with one, and without it `AutographButton.body` could drop `properties:` on the
            // floor with every test still green — the button's own parameter would be pinned nowhere.
            AutographButton(
                "Plain",
                event: "Recipe Saved",
                properties: ["surface": "list"],
                target: "swiftui_plain_button"
            ) {}
                .accessibilityIdentifier("swiftui_plain_button")

            // The invariant `AutographButton` exists to guarantee: SwiftUI runs no action for a disabled
            // button, and since the recording lives *inside* that action, no event is recorded either.
            // Nothing off-device can assert this — both designs this replaced (`.simultaneousGesture`,
            // an `isEnabled` environment gate) recorded a phantom event exactly here.
            AutographButton("Disabled", event: "Recipe Saved", target: "swiftui_disabled_button") {}
                .accessibilityIdentifier("swiftui_disabled_button")
                .disabled(true)

            // The order contract, made observable: the action appends to the same log the tracker writes
            // to, so "record, then act" is a sequence of two entries rather than a claim in a doc.
            AutographButton("Ordered", event: "Recipe Saved", target: "swiftui_order_button") {
                wiring.recordAction("swiftui_order_button")
            }
            .accessibilityIdentifier("swiftui_order_button")

            // The escape hatch, on an initializer `AutographButton` deliberately does not mirror.
            InlineTrackedDeleteButton()

            // Sibling scopes — the shape that made the scope channel lexical rather than stack-resolved.
            // `ScopeStack.resolveScope()` drops frames that are neither's ancestor, so a stack-driven
            // scope would lose both rows' identity; through the environment each button carries its own.
            ForEach(["row_1", "row_2"], id: \.self) { row in
                AutographButton("Row \(row)", event: "Recipe Saved", target: "swiftui_\(row)_button") {}
                    .accessibilityIdentifier("swiftui_\(row)_button")
                    .autographScope(["row_id": row])
            }

            // Nesting: the inner scope wins the key it shares, and the outer one survives alongside it.
            // The outer scope carries `route` — a key of its OWN — because that is the half a shared key
            // cannot pin: with `row_id` alone on the outside, replacing the merge with a plain assignment
            // would still produce row_id=inner plus the inner's other keys, and pass. Not `section`: that
            // is reserved and written from the stack, so it would tie this to the screen carrying none.
            AutographButton("Nested", event: "Recipe Saved", target: "swiftui_nested_button") {}
                .accessibilityIdentifier("swiftui_nested_button")
                .autographScope(["row_id": "inner", "extra": "kept"])
                .autographScope(["row_id": "outer", "route": "feed"])
        }
        // Only the screen name here — both captures come from the parent, which is what lets this
        // modifier see one at all.
        .autographScreen("SwiftUIExplicit")
    }
}

/// A `Button` whose initializer `AutographButton` does not mirror (`role:`), instrumented inline — the
/// documented second path, and the one with no structural guarantee behind it.
private struct InlineTrackedDeleteButton: View {
    @Environment(\.autograph) private var autograph

    var body: some View {
        Button("Delete", role: .destructive) {
            autograph.track("Recipe Deleted", properties: ["plan": "pro"], target: "swiftui_inline_button")
        }
        .accessibilityIdentifier("swiftui_inline_button")
    }
}
