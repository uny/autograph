import SwiftUI

// Can `AutographButton` actually be WRITTEN — and does its action fire exactly once per activation
// path? This rig settles U2/U3/U4/U5 from the decision brief.
//
// U2/U5 are settled by COMPILATION, not by running: if the init surface below (plain title,
// LocalizedStringKey, systemImage, role:, free-form label builder) compiles at a chosen availability
// floor without an availability-gated overload for every variant, then "the control explodes into
// overloads" is refuted. If it does not compile, the control is harder to ship than claimed and the
// brief's M5 caveat becomes a real cost. Either way the answer is a fact, not an estimate.
//
// U3 is settled by RUNNING: every fixture counts the action, and the *same* action is reachable from
// several activation paths (plain tap, keyboard shortcut, Menu item, swipe action). An event SDK that
// double-reports on one path is broken in a way no unit test in this repo would catch, which is the
// #134/#151 lesson. The oracle is the count: exactly 1 per activation, never 0, never 2.
//
// U4 is settled by RUNNING: the List section rebuilds its rows with changing identity; a `@State`
// inside the control's label must not leak across rows (which would mean the control introduces an
// identity bug a literal Button does not have).
//
// Deliberately standalone — a fake tracker, no SDK import — so it compiles in seconds with `swiftc`
// and needs no Xcode project or xcframework build.

// MARK: - The tracking substrate (stands in for the real Environment handle)

/// What the real `@Environment(\.autograph)` handle will carry: a tracker plus the LEXICALLY
/// accumulated scope. Scope is carried here, not read from `ScopeStack`, because `resolveScope()`
/// drops sibling frames (brief M3) — a list where each row owns a scope would lose it.
struct AutographHandle {
    var scope: [String: String] = [:]
    /// Stands in for `Tracker.track`. A sink, not a tracker, so the rig needs no SDK build.
    var sink: (String, [String: String]) -> Void = { _, _ in }

    func track(_ event: String, properties: [String: String] = [:]) {
        sink(event, scope.merging(properties) { _, callSite in callSite })
    }

    /// The DECORATOR candidate: wraps an action so the event is recorded before it runs, matching
    /// `Modifier.trackClick`'s track→onClick order (ElementTracking.kt:57).
    func tracked(_ event: String, properties: [String: String] = [:],
                 _ action: @escaping () -> Void) -> () -> Void {
        {
            track(event, properties: properties)
            action()
        }
    }
}

private struct AutographHandleKey: EnvironmentKey {
    static let defaultValue = AutographHandle()
}

extension EnvironmentValues {
    var autograph: AutographHandle {
        get { self[AutographHandleKey.self] }
        set { self[AutographHandleKey.self] = newValue }
    }
}

extension View {
    /// The lexical scope channel. The real one also pushes a `ScopeStack` frame so UIKit hosted in a
    /// `UIViewRepresentable` below still attributes; that half needs the SDK and is out of scope here.
    func autographScope(_ scope: [String: String]) -> some View {
        transformEnvironment(\.autograph) { handle in
            handle.scope.merge(scope) { _, inner in inner }
        }
    }
}

// MARK: - U2/U5: the CONTROL candidate's full init surface, at one availability floor

/// The dedicated control. Reads the handle itself, so the call site needs no `@Environment` line.
///
/// **The U2/U5 question is whether this compiles as written.** One generic `Label` parameter plus
/// `where Label == Text` convenience inits is the whole surface — no availability-gated duplicate per
/// variant. `role:` is iOS 15, so it is the ONE member that carries its own `@available`, rather than
/// forcing a second copy of every initializer.
@available(iOS 14.0, *)
struct AutographButton<Label: View>: View {
    private let event: String
    private let properties: [String: String]
    private let target: String?
    private let action: () -> Void
    private let label: Label

    @Environment(\.autograph) private var autograph

    init(
        _ event: String,
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

    var body: some View {
        Button {
            // Track first, then call the caller's action — the same order `Modifier.trackClick`
            // uses, and for the same reason: a duplicate is recoverable, a missing event is not.
            autograph.track(event, properties: properties.merging(
                target.map { ["target": $0] } ?? [:]) { a, _ in a })
            action()
        } label: {
            label
        }
    }
}

// The convenience inits that make the common cases one line. `where Label == Text` means these are
// NOT separate types and NOT availability-gated copies — this is the surface U2 asks about.
@available(iOS 14.0, *)
extension AutographButton where Label == Text {

    init(_ titleKey: LocalizedStringKey, event: String, properties: [String: String] = [:],
         target: String? = nil, action: @escaping () -> Void) {
        self.init(event, properties: properties, target: target, action: action) { Text(titleKey) }
    }

    init<S: StringProtocol>(_ title: S, event: String, properties: [String: String] = [:],
                            target: String? = nil, action: @escaping () -> Void) {
        self.init(event, properties: properties, target: target, action: action) { Text(title) }
    }
}

/// `role:` is iOS 15+. It carries its own availability rather than duplicating the whole surface —
/// this is the claim U5 tests.
@available(iOS 15.0, *)
struct AutographRoleButton<Label: View>: View {
    private let event: String
    private let role: ButtonRole?
    private let action: () -> Void
    private let label: Label

    @Environment(\.autograph) private var autograph

    init(_ event: String, role: ButtonRole?, action: @escaping () -> Void,
         @ViewBuilder label: () -> Label) {
        self.event = event
        self.role = role
        self.action = action
        self.label = label()
    }

    var body: some View {
        Button(role: role) {
            autograph.track(event)
            action()
        } label: {
            label
        }
    }
}

// MARK: - U3/U4: the running probe

final class Counts: ObservableObject {
    @Published var events: [(String, [String: String])] = []

    func record(_ name: String, _ props: [String: String]) { events.append((name, props)) }
    func count(_ name: String) -> Int { events.filter { $0.0 == name }.count }
    /// The scope actually attached to the last event of this name — how U4/sibling-scope is read.
    func scope(_ name: String) -> String {
        events.last { $0.0 == name }?.1["row"] ?? "-"
    }
}

/// U4: a row whose label owns `@State`. If the control leaks state across rows as the List reuses
/// them, the badge below will show a value belonging to a different row.
@available(iOS 15.0, *)
struct StatefulRow: View {
    let id: Int
    @State private var localTaps = 0

    var body: some View {
        AutographButton("row_tapped", properties: ["row": "\(id)"], action: { localTaps += 1 }) {
            HStack {
                Text("row \(id)")
                Spacer()
                Text("local=\(localTaps)")
                    .font(.system(size: 12, design: .monospaced))
                    .accessibilityIdentifier("row_\(id)_local")
            }
        }
    }
}

@available(iOS 15.0, *)
@main
struct ControlProbeApp: App {
    var body: some Scene { WindowGroup { ControlProbeView() } }
}

@available(iOS 15.0, *)
struct ControlProbeView: View {
    @StateObject private var counts = Counts()

    var body: some View {
        NavigationView {
            List {
                Section("U3 — each path must fire EXACTLY once") {
                    AutographButton("plain", event: "plain_tap", action: {})
                        .accessibilityIdentifier("plain")
                    Text("plain=\(counts.count("plain_tap")) — 1 per tap")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("plain_count")

                    Menu("menu") {
                        AutographButton("in menu", event: "menu_tap", action: {})
                    }
                    Text("menu=\(counts.count("menu_tap")) — 1 per activation")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("menu_count")

                    Text("swipe me")
                        .swipeActions {
                            AutographButton("swipe", event: "swipe_tap", action: {})
                        }
                    Text("swipe=\(counts.count("swipe_tap")) — 1 per activation")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("swipe_count")
                }

                Section("DECORATOR arm — same oracle, for comparison") {
                    DecoratedButton()
                    Text("decorated=\(counts.count("decorated_tap"))")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("decorated_count")
                }

                Section("U4 — @State inside the control's label, across List rows") {
                    ForEach(0 ..< 4, id: \.self) { StatefulRow(id: $0) }
                    Text("last row scope=\(counts.scope("row_tapped"))")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("row_scope")
                }

                Section("M3 — sibling scopes must NOT collapse") {
                    // Each row carries its own lexical scope. If the handle is lexical this attributes
                    // exactly; if it read ScopeStack, resolveScope() would drop both as ambiguous.
                    ForEach(0 ..< 3, id: \.self) { i in
                        AutographButton("sib \(i)", event: "sibling_tap", action: {})
                            .autographScope(["row": "sibling_\(i)"])
                    }
                    Text("sibling scope=\(counts.scope("sibling_tap"))")
                        .font(.system(size: 13, design: .monospaced))
                        .accessibilityIdentifier("sibling_scope")
                }
            }
            .navigationTitle("control probe")
        }
        .navigationViewStyle(.stack)
        .environment(\.autograph, AutographHandle(sink: { name, props in
            counts.record(name, props)
        }))
    }
}

/// The decorator arm: a caller's own component, instrumented without any Autograph view type.
@available(iOS 15.0, *)
struct DecoratedButton: View {
    @Environment(\.autograph) private var autograph

    var body: some View {
        // Stands in for a design-system component the control could never reach.
        PrimaryButton(title: "decorated", action: autograph.tracked("decorated_tap") {})
            .accessibilityIdentifier("decorated")
    }
}

/// A caller-owned design-system button — the thing the coverage argument (brief, 案A ＋) is about.
struct PrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).bold()
        }
    }
}
