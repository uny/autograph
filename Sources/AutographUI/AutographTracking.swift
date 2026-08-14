import Autograph
import SwiftUI
import os

/// Explicit click instrumentation for SwiftUI.
///
/// SwiftUI elements are **not** autocaptured (#191/#192): a SwiftUI view is not UIView-backed, and every
/// channel that could have carried its name to the tap pipeline was measured and ruled out. Explicit
/// instrumentation is therefore not a fallback here — it is the whole story, and this is its entry point.
///
/// ```swift
/// // Once, at the root — hand it the SAME ScopeStack you gave AutographProvider and the native
/// // capture, exactly as with `.autographScreenCapture`:
/// ContentView()
///     .autographScreenCapture(screenCapture)
///     .autographElementCapture(AutographElementCapture(tracker: tracker, scopeStack: stack))
///
/// // For a button you can edit, reach for ``AutographButton`` — it records for you:
/// AutographButton("Save", event: "save_tapped", target: "save_button") { save() }
///
/// // Everywhere else, record from inside the action:
/// struct DeleteButton: View {
///     @Environment(\.autograph) private var autograph
///
///     var body: some View {
///         Button("Delete", role: .destructive) {
///             autograph.track("delete_tapped", target: "delete_button")
///             delete()
///         }
///     }
/// }
/// ```
///
/// ## What gets attached, and from where
///
/// `screen` and `section` come from the shared `ScopeStack` — at most one screen is current, so there is
/// nothing to disambiguate, and for a SwiftUI screen the stack is the only place its name lives (see
/// ``AutographScreen``). Scope comes from ``SwiftUICore/View/autographScope(_:)`` **lexically**, through
/// the environment, because the stack deliberately drops sibling scopes it cannot choose between — a list
/// whose rows each own a scope is exactly that shape, and an explicit call site knows precisely which row
/// it is. See `AutographElementCapture`'s kdoc for the full precedence.
public struct AutographTracking {

    /// Nil when no ``SwiftUICore/View/autographElementCapture(_:)`` is above this view. Deliberately
    /// nil rather than a no-op capture, so a forgotten call is distinguishable from a working one and
    /// can be reported instead of silently dropping every event.
    internal var capture: AutographElementCapture?

    /// The lexically accumulated scope — see the type's doc for why this does not come from the stack.
    internal var scope: [String: String] = [:]

    /// Records a click named [event].
    ///
    /// This is the general escape hatch: call it from inside whatever already handles the interaction —
    /// a `Button` whose initializer ``AutographButton`` does not mirror (`role:`, `systemImage`), a
    /// component you cannot edit, an `.onTapGesture`. For a button you *can* edit, ``AutographButton``
    /// is shorter and cannot be wired up wrongly.
    ///
    /// - Important: **Call this before the work the interaction triggers**, as ``AutographButton`` does
    ///   internally. If recording throws there is then no event but the action still happens, and a
    ///   missing event beats a broken button. Recording afterwards inverts that.
    ///
    /// Nothing here can tell whether its caller is a real interaction: invoked from a timer or a
    /// completion handler it records a click nobody performed. Keep the call inside the handler that
    /// the user's touch actually reaches.
    public func track(
        _ event: String,
        properties: [String: String] = [:],
        target: String? = nil
    ) {
        guard let capture else {
            AutographTrackingDiagnostics.missingCapture(event)
            return
        }
        capture.clicked(name: event, properties: properties, scope: scope, target: target)
    }

    /// ``track(_:properties:target:)`` for properties that are not all strings, as a JSON object literal.
    ///
    /// A `[String: String]` cannot carry numbers, booleans or nested objects, and Swift cannot construct
    /// the Kotlin `JsonObject` the tracker takes, so this is the escape hatch. Text that does not parse to
    /// a JSON *object* loses the properties but still records the event.
    public func track(
        _ event: String,
        propertiesJson: String,
        target: String? = nil
    ) {
        guard let capture else {
            AutographTrackingDiagnostics.missingCapture(event)
            return
        }
        capture.clickedJson(name: event, propertiesJson: propertiesJson, scope: scope, target: target)
    }

}

// MARK: - Environment plumbing

private struct AutographTrackingKey: EnvironmentKey {
    static let defaultValue = AutographTracking()
}

public extension EnvironmentValues {

    /// The explicit instrumentation handle. Reads the ``AutographElementCapture`` and the scope that
    /// ``SwiftUICore/View/autographScope(_:)`` accumulated above this view.
    var autograph: AutographTracking {
        get { self[AutographTrackingKey.self] }
        set { self[AutographTrackingKey.self] = newValue }
    }
}

public extension View {

    /// Provides the `AutographElementCapture` that ``SwiftUICore/EnvironmentValues/autograph`` records
    /// through. Set it once, above every instrumented control in the tree.
    func autographElementCapture(_ capture: AutographElementCapture) -> some View {
        transformEnvironment(\.autograph) { $0.capture = capture }
    }

    /// Attaches [scope] to every explicit event recorded inside this subtree — the SwiftUI counterpart of
    /// Compose's `AutographScope`.
    ///
    /// Nesting merges outer-then-inner, so an inner key wins; a call site's own `properties` win over
    /// both. Scope travels **lexically** (through the environment), which is what lets sibling subtrees —
    /// a list's rows, split panes — each carry their own scope without becoming ambiguous.
    ///
    /// - Note: This affects **explicit** instrumentation only. It does not reach autocapture of UIKit
    ///   hosted below it in a `UIViewRepresentable`, which resolves its scope from the shared
    ///   `ScopeStack`; nothing is pushed there. Pure SwiftUI content is unaffected either way, since
    ///   SwiftUI elements are not autocaptured at all (#191).
    func autographScope(_ scope: [String: String]) -> some View {
        transformEnvironment(\.autograph) { handle in
            handle.scope.merge(scope) { _, inner in inner }
        }
    }
}

// MARK: - Diagnostics

private enum AutographTrackingDiagnostics {

    /// Loud on a missing capture, matching ``AutographScreen``'s handling: trap in debug, where it is a
    /// wiring bug the developer should see at once, and log at fault level in release so the silence of
    /// "no events" is at least traceable.
    ///
    /// The availability check is around the *logging only*, deliberately: `Logger` needs iOS 14, but
    /// nothing else in this file does, and gating the whole explicit-instrumentation API on 14 to reach a
    /// diagnostic would raise the floor of a working feature for the sake of a log line. Below 14 the
    /// debug trap still fires, which is where a missing capture actually gets noticed.
    static func missingCapture(_ event: String) {
        assertionFailure(
            "`autograph.track(\"\(event)\")` has no AutographElementCapture in its environment — call "
                + "`.autographElementCapture(_:)` above it. No event was recorded."
        )
        if #available(iOS 14.0, *) {
            Logger(subsystem: "dev.ynagai.autograph", category: "AutographUI").fault(
                "`autograph.track(\"\(event, privacy: .public)\")` has no capture in its environment; event dropped."
            )
        }
    }
}
