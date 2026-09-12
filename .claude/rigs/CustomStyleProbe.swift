import SwiftUI

// Does a caller's OWN design-system component keep its appearance when its inner `Button` is swapped
// for the wrapper `AutographButton` ships?
//
// The earlier appearance probe only covered Apple's built-in styles on a bare button. A real app's
// component is different in two ways this rig targets:
//
//   1. It applies a **custom `ButtonStyle`** — a caller-written struct that reads
//      `configuration.isPressed` and `configuration.label`. Built-in styles travelling through the
//      wrapper says nothing about whether a custom one does.
//   2. Its look includes a **press state**, which a static screenshot cannot see. A style that never
//      observes `isPressed` through the wrapper would look right at rest and feel dead under a finger —
//      exactly the kind of regression that ships unnoticed.
//
// So `isPressed` is not judged by eye: `PressCountingStyle` bumps a counter every time it observes a
// press transition, and the counters for the literal and wrapped components must move together. The
// static look is still compared side by side in the same screenshot.

@main
struct CustomStyleProbeApp: App {
    var body: some Scene { WindowGroup { CustomStyleProbeView() } }
}

/// The shape `AutographButton` ships, minus the tracking call.
struct WrapperButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View { Button(action: action, label: label) }
}

/// A caller-written style: rounded fill, scale and dim on press. Representative of what a design system
/// actually does, and it depends on `configuration.isPressed` for the press half.
struct PressCountingStyle: ButtonStyle {
    let onPressChange: (Bool) -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(Color.indigo)
            .cornerRadius(10)
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            // Reporting from inside the style is the point: this is the value the style *actually*
            // received, not an inference from how the button looked.
            .onChange(of: configuration.isPressed) { onPressChange($0) }
    }
}

/// The caller's component, built on a literal `Button` — the "before" of a migration.
struct PrimaryButtonLiteral: View {
    let title: String
    let onPressChange: (Bool) -> Void
    let action: () -> Void

    var body: some View {
        Button(title, action: action)
            .buttonStyle(PressCountingStyle(onPressChange: onPressChange))
    }
}

/// The same component after swapping only its inner button — the "after". Everything else is identical,
/// so any difference is caused by the swap and nothing else.
struct PrimaryButtonWrapped: View {
    let title: String
    let onPressChange: (Bool) -> Void
    let action: () -> Void

    var body: some View {
        WrapperButton(action: action) { Text(title) }
            .buttonStyle(PressCountingStyle(onPressChange: onPressChange))
    }
}

struct CustomStyleProbeView: View {
    @State private var literalPresses = 0
    @State private var wrappedPresses = 0
    @State private var literalActions = 0
    @State private var wrappedActions = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("a caller's own component, custom ButtonStyle")
                .font(.headline)
            Text("press each once — the two rows must agree")
                .font(.caption)

            VStack(alignment: .leading, spacing: 8) {
                Text("literal Button inside the component").font(.caption2).foregroundColor(.secondary)
                PrimaryButtonLiteral(
                    title: "Save",
                    onPressChange: { if $0 { literalPresses += 1 } },
                    action: { literalActions += 1 }
                )
                .accessibilityIdentifier("literal_component")
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("wrapper inside the component").font(.caption2).foregroundColor(.secondary)
                PrimaryButtonWrapped(
                    title: "Save",
                    onPressChange: { if $0 { wrappedPresses += 1 } },
                    action: { wrappedActions += 1 }
                )
                .accessibilityIdentifier("wrapped_component")
            }

            Divider()

            Text("isPressed  literal=\(literalPresses)  wrapper=\(wrappedPresses)")
                .font(.system(size: 15, design: .monospaced))
                .foregroundColor(literalPresses == wrappedPresses ? .primary : .red)
                .accessibilityIdentifier("press_counts")

            Text("action     literal=\(literalActions)  wrapper=\(wrappedActions)")
                .font(.system(size: 15, design: .monospaced))
                .foregroundColor(literalActions == wrappedActions ? .primary : .red)
                .accessibilityIdentifier("action_counts")

            Text("both rows equal, and the two buttons look alike above = the swap is invisible")
                .font(.caption2)
                .foregroundColor(.secondary)

            Spacer()
        }
        .padding()
    }
}
