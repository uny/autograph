import SwiftUI

// Does a custom `View` struct that internally composes `Button` behave identically to a LITERAL
// `Button` everywhere a Button is accepted?
//
// This decides `AutographButton` (a dedicated control) vs a closure decorator. A decorator leaves the
// caller writing a literal `Button` and only replaces its action, so it is immune by construction; a
// dedicated control substitutes a wrapper struct for the literal, and SwiftUI is known to inspect the
// *structure* of some containers' content rather than just rendering it. If the wrapper vanishes or
// misrenders in any of these, shipping `AutographButton` means shipping a silent hole — the exact
// failure mode this project rejects elsewhere (#128/#134's "no phantom, no silent drop" rule).
//
// The oracle is a SIDE-BY-SIDE COMPARISON, never a solo observation: every fixture puts a literal
// Button and the wrapper in the SAME container, labelled "literal" and "wrapper". A container that
// shows both, with both taps counted, is a pass. A container that shows only "literal" is a
// CONFIRMED hole. A solo wrapper that happens to render proves nothing about the containers that
// filter, which is why nothing here is measured alone.
//
// Containers under test, each its own row:
//   1. `.alert` — documented to accept only Button/TextField in its actions builder
//   2. `.confirmationDialog` — same builder family
//   3. `Menu` — inspects content to build menu items
//   4. `.contextMenu` — same
//   5. `.swipeActions` — takes role/tint off the button
//   6. `.toolbar` — ToolbarItem content
//   7. plain VStack — the control arm; if the wrapper fails HERE the rig itself is broken
//
// Row 7 is what makes a negative result non-vacuous: it must always pass. Row 8 separately checks
// that modifiers applied from OUTSIDE the wrapper (`.buttonStyle`, `.disabled`, `.controlSize`)
// still reach the inner Button — they travel by environment, so they are expected to, but "expected
// to" is what this rig exists to stop trusting.
//
// Counters are rendered with accessibility identifiers so XCUITest or mobile-mcp can read them.

@main
struct WrapperProbeApp: App {
    var body: some Scene { WindowGroup { WrapperProbeView() } }
}

// MARK: - The wrapper under test

/// Exactly the shape `AutographButton` would have: a struct that owns the event name and composes a
/// real `Button`, wrapping the caller's action so the event is recorded before the action runs (the
/// SwiftUI analogue of `Modifier.trackClick` owning its own `clickable{}`).
struct WrapperButton<Label: View>: View {
    let event: String
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    /// Stands in for `tracker.track(event)`. A counter, not a tracker, so the rig needs no SDK build.
    let onTrack: (String) -> Void

    var body: some View {
        Button {
            onTrack(event)
            action()
        } label: {
            label()
        }
    }
}

/// A shared, observable tally so a fixture inside an alert (which lives outside the fixture's own
/// view identity) can still report back.
final class Tally: ObservableObject {
    @Published var counts: [String: Int] = [:]

    func bump(_ key: String) { counts[key, default: 0] += 1 }
    func count(_ key: String) -> Int { counts[key] ?? 0 }
}

/// Renders one row's verdict. `both` is the pass condition: the literal and the wrapper each got a
/// tap. Red until both are non-zero, so an unrendered wrapper stays visibly red forever.
struct Verdict: View {
    @ObservedObject var tally: Tally
    let id: String

    var body: some View {
        let literal = tally.count("\(id)_literal")
        let wrapper = tally.count("\(id)_wrapper")
        Text("literal=\(literal) wrapper=\(wrapper)")
            .font(.system(size: 13, design: .monospaced))
            .foregroundColor(literal > 0 && wrapper > 0 ? Color.primary : Color.red)
            .accessibilityIdentifier("\(id)_counts")
    }
}

// MARK: - The probe

struct WrapperProbeView: View {
    @StateObject private var tally = Tally()

    @State private var showAlert = false
    @State private var showDialog = false

    var body: some View {
        NavigationView {
            List {
                Section("7. control arm — MUST pass or the rig is broken") {
                    HStack {
                        Button("literal") { tally.bump("plain_literal") }
                            .accessibilityIdentifier("plain_literal")
                        Spacer()
                        WrapperButton(event: "plain_wrapper", action: {}, label: { Text("wrapper") },
                                      onTrack: { tally.bump($0) })
                            .accessibilityIdentifier("plain_wrapper")
                    }
                    .buttonStyle(.borderless)
                    Verdict(tally: tally, id: "plain")
                }

                Section("1. .alert") {
                    Button("open alert") { showAlert = true }
                        .accessibilityIdentifier("open_alert")
                    Verdict(tally: tally, id: "alert")
                }

                Section("2. .confirmationDialog") {
                    Button("open dialog") { showDialog = true }
                        .accessibilityIdentifier("open_dialog")
                    Verdict(tally: tally, id: "dialog")
                }

                Section("3. Menu") {
                    Menu("open menu") {
                        Button("literal") { tally.bump("menu_literal") }
                        WrapperButton(event: "menu_wrapper", action: {}, label: { Text("wrapper") },
                                      onTrack: { tally.bump($0) })
                    }
                    .accessibilityIdentifier("open_menu")
                    Verdict(tally: tally, id: "menu")
                }

                Section("4. .contextMenu — long-press the row") {
                    Text("long-press me")
                        .accessibilityIdentifier("context_target")
                        .contextMenu {
                            Button("literal") { tally.bump("context_literal") }
                            WrapperButton(event: "context_wrapper", action: {}, label: { Text("wrapper") },
                                          onTrack: { tally.bump($0) })
                        }
                    Verdict(tally: tally, id: "context")
                }

                Section("5. .swipeActions — swipe the row left") {
                    Text("swipe me")
                        .accessibilityIdentifier("swipe_target")
                        .swipeActions {
                            Button("literal") { tally.bump("swipe_literal") }
                            WrapperButton(event: "swipe_wrapper", action: {}, label: { Text("wrapper") },
                                          onTrack: { tally.bump($0) })
                        }
                    Verdict(tally: tally, id: "swipe")
                }

                Section("8. modifiers applied from OUTSIDE the wrapper") {
                    // `.disabled(true)` from outside must reach the inner Button: tapping this must
                    // leave the counter at 0. If it increments, a dedicated control would report
                    // clicks on disabled elements — the invariant #128/#134 fixed for Compose/iOS.
                    WrapperButton(event: "outside_disabled", action: {}, label: { Text("disabled wrapper") },
                                  onTrack: { tally.bump($0) })
                        .disabled(true)
                        .accessibilityIdentifier("outside_disabled")
                    Text("disabled_wrapper=\(tally.count("outside_disabled")) — MUST stay 0")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundColor(tally.count("outside_disabled") == 0 ? Color.primary : Color.red)
                        .accessibilityIdentifier("outside_disabled_counts")

                    // Purely visual: does the wrapper pick up a style set from outside? Compared by eye
                    // against the literal next to it in row 7.
                    WrapperButton(event: "outside_styled", action: {}, label: { Text("styled wrapper") },
                                  onTrack: { tally.bump($0) })
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("outside_styled")
                }
            }
            .navigationTitle("wrapper vs literal")
            .toolbar {
                // 6. .toolbar — both must appear in the navigation bar.
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("lit") { tally.bump("toolbar_literal") }
                        .accessibilityIdentifier("toolbar_literal")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    WrapperButton(event: "toolbar_wrapper", action: {}, label: { Text("wrap") },
                                  onTrack: { tally.bump($0) })
                        .accessibilityIdentifier("toolbar_wrapper")
                }
            }
            .alert("alert", isPresented: $showAlert) {
                Button("literal") { tally.bump("alert_literal") }
                WrapperButton(event: "alert_wrapper", action: {}, label: { Text("wrapper") },
                              onTrack: { tally.bump($0) })
                Button("close", role: .cancel) {}
            }
            .confirmationDialog("dialog", isPresented: $showDialog) {
                Button("literal") { tally.bump("dialog_literal") }
                WrapperButton(event: "dialog_wrapper", action: {}, label: { Text("wrapper") },
                              onTrack: { tally.bump($0) })
                Button("close", role: .cancel) {}
            }
        }
        .navigationViewStyle(.stack)
    }
}
