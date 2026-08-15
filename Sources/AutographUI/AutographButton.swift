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
/// ## Why this and not ``AutographTracking/track(_:properties:target:)``
///
/// Nothing you call by hand can prove its caller was a real interaction — reached from a timer or a
/// completion block, ``AutographTracking/track(_:properties:target:)`` records a click nobody made, and
/// this SDK has already refused two designs for producing exactly that kind of phantom event. Here the
/// recording lives inside the button's own action, where no other code can reach it, and the order
/// (record, then act) is fixed by the type rather than left to the call site.
///
/// This is not the only path, because it cannot be: a component you cannot edit, or a `Button`
/// initializer this does not mirror, still needs instrumenting. That is what
/// ``AutographTracking/track(_:properties:target:)`` is for. Reach for it second, not first.
///
/// A design-system button of your own is **not** one of those cases: swapping the `Button` inside your
/// component for this one is appearance-neutral, down to a custom `ButtonStyle`'s `isPressed` — measured,
/// not assumed. The cost there is threading an event name through your component's API, not restyling it.
///
/// ## What this deliberately does not mirror
///
/// The initializers below are the whole contract: a free-form label, or a title. `Button`'s other
/// conveniences — `systemImage`, `role:`, `LocalizedStringResource` — are **not** mirrored, and are not
/// going to be: each arrived in a different iOS version, so chasing parity means an availability-gated
/// overload per convenience, growing with every SDK release, for sugar the caller can already express.
/// For those, keep the literal `Button` and record inside its action:
///
/// ```swift
/// Button("Delete", role: .destructive) {
///     autograph.track("delete_tapped")
///     delete()
/// }
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
            // Compose side, and for the same reason: a *tracker* failure is swallowed on the Kotlin side,
            // so there is no event but the button still works, and a missing event beats a broken button.
            // The one exception is deliberate and debug-only: a missing `.autographElementCapture(_:)`
            // traps in `track`, so `action()` below never runs. That is the wiring bug the trap exists to
            // surface, and it cannot reach a release build, where the same path logs and returns.
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
