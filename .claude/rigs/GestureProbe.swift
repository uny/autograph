import SwiftUI

// Does `.simultaneousGesture(TapGesture())` observe the same events a SwiftUI `Button`'s action does?
//
// The oracle is a COUNT COMPARISON, not a boolean: every fixture keeps two counters, one incremented
// from the Button's own action and one from the gesture. They must agree for every interaction that
// should be reported, and BOTH must stay at zero for one that should not. A design that fires the
// gesture without the action invents events; one that fires the action without the gesture loses them.
//
// Three premises are under test, each its own row:
//   1. `.disabled(true)` — the action must not run, and the gesture must not either. If the gesture
//      still fires, an Autograph modifier built on it would report clicks on disabled controls, which
//      is exactly the invariant #128/#134 established for Compose and iOS.
//   2. press-then-drag-off-then-release — the Button cancels; the gesture must cancel too.
//   3. a tap inside a scrolling container — the gesture must not steal or duplicate.
//
// Counters are rendered on screen with accessibility identifiers so an XCUITest (or a human) can read
// them. Driven here by hand through the simulator, because a gesture is what is under test and a
// synthetic driver that bypasses the gesture system would measure nothing.

@main
struct GestureProbeApp: App {
    var body: some Scene { WindowGroup { GestureProbeView() } }
}

/// One fixture: a Button whose action and whose observing gesture each keep their own count.
struct CountedButton: View {
    let title: String
    let id: String
    var disabled: Bool = false

    @State private var actionCount = 0
    @State private var gestureCount = 0

    var body: some View {
        HStack {
            Button(title) { actionCount += 1 }
                .disabled(disabled)
                // The candidate mechanism, exactly as `.autographElement` would attach it.
                .simultaneousGesture(TapGesture().onEnded { gestureCount += 1 })
                .accessibilityIdentifier(id)

            Spacer()

            Text("action=\(actionCount) gesture=\(gestureCount)")
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(actionCount == gestureCount ? Color.primary : Color.red)
                .accessibilityIdentifier("\(id)_counts")
        }
        .padding(.horizontal)
    }
}

/// The candidate fix: a `ViewModifier` that reports a tap, gated on the SwiftUI environment's
/// `isEnabled` rather than on the gesture firing. This is what `.autographTap` would be.
///
/// The open question is **order**: `@Environment` is resolved where the modifier sits in the chain, so
/// `.disabled(true)` applied OUTSIDE it may or may not be visible from inside. Both orders are on
/// screen below; if they disagree, the gate is order-dependent and cannot be shipped as-is.
struct GatedReport: ViewModifier {
    @Environment(\.isEnabled) private var isEnabled
    let onTap: () -> Void

    func body(content: Content) -> some View {
        content.simultaneousGesture(TapGesture().onEnded {
            guard isEnabled else { return }
            onTap()
        })
    }
}

/// A Button whose action count is compared against a GATED reporter's count.
struct GatedCountedButton: View {
    let title: String
    let id: String
    /// true = `.disabled` sits OUTSIDE the gate (applied after it in the chain).
    let disableOutside: Bool

    @State private var actionCount = 0
    @State private var reportCount = 0

    var body: some View {
        HStack {
            gated
            Spacer()
            Text("action=\(actionCount) report=\(reportCount)")
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(actionCount == reportCount ? Color.primary : Color.red)
                .accessibilityIdentifier("\(id)_counts")
        }
        .padding(.horizontal)
    }

    @ViewBuilder private var gated: some View {
        if disableOutside {
            Button(title) { actionCount += 1 }
                .modifier(GatedReport { reportCount += 1 })
                .disabled(true)
        } else {
            Button(title) { actionCount += 1 }
                .disabled(true)
                .modifier(GatedReport { reportCount += 1 })
        }
    }
}

struct GestureProbeView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("action vs simultaneousGesture")
                .font(.headline)
            Text("red = they disagree")
                .font(.caption)

            Group {
                Text("1. plain — tap it; both must reach 1").font(.caption)
                CountedButton(title: "Plain", id: "plain")

                Text("2. disabled — tap it; BOTH must stay 0").font(.caption)
                CountedButton(title: "Disabled", id: "disabled", disabled: true)

                Text("3. drag off — press, drag away, release; both must stay 0").font(.caption)
                CountedButton(title: "DragOff", id: "dragoff")
            }

            Group {
                Text("5. GATED, .disabled INSIDE the gate — both must stay 0").font(.caption)
                GatedCountedButton(title: "GateInside", id: "gate_inside", disableOutside: false)

                Text("6. GATED, .disabled OUTSIDE the gate — both must stay 0").font(.caption)
                GatedCountedButton(title: "GateOutside", id: "gate_outside", disableOutside: true)
            }

            Text("4. in a scroll view — tap a row, then scroll").font(.caption)
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(0 ..< 12, id: \.self) { index in
                        CountedButton(title: "Row \(index)", id: "row_\(index)")
                    }
                }
            }
            .frame(height: 220)
        }
        .padding(.vertical)
    }
}
