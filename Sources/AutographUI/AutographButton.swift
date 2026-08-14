import SwiftUI

/// A `Button` that records a click when it fires — the **recommended** way to instrument a tap in
/// SwiftUI.
///
/// ```swift
/// AutographButton("Save", event: "save_tapped", target: "save_button") {
///     save()
/// }
/// ```
///
/// Requires ``SwiftUICore/View/autographElementCapture(_:)`` above it, like every other entry point in
/// this product. Scope, screen and section attach exactly as they do for
/// ``AutographTracking/track(_:properties:target:)`` — see ``AutographTracking``.
///
/// ## Why this and not just the decorator
///
/// ``AutographTracking/tracked(_:properties:target:_:)`` hands back a plain closure, and a closure
/// cannot prove who called it: reuse the same handler from a deep link or a completion block and the
/// event is recorded for a tap nobody made. This SDK has already refused two designs for producing
/// exactly that kind of phantom event, so the shape that cannot produce one is the shape to reach for
/// first. Here the tracking lives inside the button's own action and is unreachable from anywhere else.
///
/// The decorator remains for the cases this type cannot reach — most importantly a design-system button
/// of your own that takes an action, which no wrapper of `Button` can instrument from the outside.
///
/// ## What this deliberately does not mirror
///
/// The initializers below are the whole contract: a free-form label, or a title. `Button`'s other
/// conveniences — `systemImage`, `role:`, `LocalizedStringResource` — are **not** mirrored, and are not
/// going to be: each arrived in a different iOS version, so chasing parity means an availability-gated
/// overload per convenience, growing with every SDK release, for sugar the caller can already express.
/// For those, wrap a literal `Button` with the decorator instead:
///
/// ```swift
/// Button("Delete", role: .destructive, action: autograph.tracked("delete_tapped") { delete() })
/// ```
///
/// Modifiers applied from outside reach the underlying `Button` normally — `.disabled`, `.buttonStyle`,
/// `.controlSize` and the rest travel by environment — and this composes inside `.alert`,
/// `.confirmationDialog`, `Menu`, `.contextMenu`, `.swipeActions` and `.toolbar` just as a literal
/// `Button` does. That was measured, not assumed.
public struct AutographButton<Label: View>: View {

    private let event: String
    private let properties: [String: String]
    private let target: String?
    private let action: () -> Void
    private let label: Label

    @Environment(\.autograph) private var autograph

    /// Creates a button with a free-form label.
    public init(
        event: String,
        properties: [String: String] = [:],
        target: String? = nil,
        action: @escaping () -> Void,
        @ViewBuilder label: () -> Label
    ) {
        self.event = event
        self.properties = properties
        self.target = target
        self.action = action
        self.label = label()
    }

    public var body: some View {
        Button {
            // Record first, then run the caller's action — the order `Modifier.trackClick` uses on the
            // Compose side, and for the same reason: if recording fails there is no event but the button
            // still works, and a missing event beats a broken button.
            autograph.track(event, properties: properties, target: target)
            action()
        } label: {
            label
        }
    }
}

public extension AutographButton where Label == Text {

    /// Creates a button that displays a localized title.
    init(
        _ titleKey: LocalizedStringKey,
        event: String,
        properties: [String: String] = [:],
        target: String? = nil,
        action: @escaping () -> Void
    ) {
        self.init(event: event, properties: properties, target: target, action: action) {
            Text(titleKey)
        }
    }

    /// Creates a button that displays a title from a string.
    ///
    /// Paired with the `LocalizedStringKey` initializer above, this is the same overload shape `Button`
    /// itself uses; a string *literal* still resolves to the localized one, matching `Button`'s behaviour.
    init<S: StringProtocol>(
        _ title: S,
        event: String,
        properties: [String: String] = [:],
        target: String? = nil,
        action: @escaping () -> Void
    ) {
        self.init(event: event, properties: properties, target: target, action: action) {
            Text(title)
        }
    }
}
