import SwiftUI

// Is swapping a literal `Button` for a wrapper that composes one a VISUAL change?
//
// This decides how the README should position `AutographButton` for this SDK's actual audience: a
// Compose Multiplatform app with SwiftUI screens surviving a transition. If the swap is pixel-identical
// the migration is "rename the type, add an event string"; if it is not, every migrated call site needs
// its appearance re-tuned, which is a materially heavier ask on screens that are being retired anyway.
//
// The oracle is a SIDE-BY-SIDE with identical label content and identical outer modifiers: the literal
// on the left, the wrapper on the right, in one HStack per style so any difference in metrics, padding,
// tint, or type is visible in the same screenshot rather than across two runs. A row that looks like one
// button repeated is a pass.
//
// `WrapperButton` is the exact shape `AutographButton` ships (`Button { track(); action() } label: { label }`),
// so this needs no framework link — the appearance question depends only on the wrapping.

@main
struct AppearanceProbeApp: App {
    var body: some Scene { WindowGroup { AppearanceProbeView() } }
}

/// The shipped shape, minus the tracking call: appearance cannot depend on it.
struct WrapperButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action, label: label)
    }
}

extension WrapperButton where Label == Text {
    init(_ titleKey: LocalizedStringKey, action: @escaping () -> Void) {
        self.init(action: action) { Text(titleKey) }
    }
}

/// One comparison: the same content and the same modifiers, literal vs wrapper.
struct Pair<L: View, W: View>: View {
    let title: String
    @ViewBuilder let literal: () -> L
    @ViewBuilder let wrapper: () -> W

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundColor(.secondary)
            HStack(spacing: 12) {
                literal()
                wrapper()
                Spacer(minLength: 0)
            }
        }
    }
}

struct AppearanceProbeView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("literal | wrapper — each pair must look like one button twice")
                    .font(.footnote)

                Pair(title: "default") {
                    Button("Save") {}
                } wrapper: {
                    WrapperButton("Save") {}
                }

                Pair(title: ".bordered") {
                    Button("Save") {}.buttonStyle(.bordered)
                } wrapper: {
                    WrapperButton("Save") {}.buttonStyle(.bordered)
                }

                Pair(title: ".borderedProminent + .tint(.pink)") {
                    Button("Save") {}.buttonStyle(.borderedProminent).tint(.pink)
                } wrapper: {
                    WrapperButton("Save") {}.buttonStyle(.borderedProminent).tint(.pink)
                }

                Pair(title: ".plain") {
                    Button("Save") {}.buttonStyle(.plain)
                } wrapper: {
                    WrapperButton("Save") {}.buttonStyle(.plain)
                }

                Pair(title: ".controlSize(.large) + .bordered") {
                    Button("Save") {}.buttonStyle(.bordered).controlSize(.large)
                } wrapper: {
                    WrapperButton("Save") {}.buttonStyle(.bordered).controlSize(.large)
                }

                Pair(title: ".disabled(true)") {
                    Button("Save") {}.buttonStyle(.bordered).disabled(true)
                } wrapper: {
                    WrapperButton("Save") {}.buttonStyle(.bordered).disabled(true)
                }

                Pair(title: "free-form label (icon + text)") {
                    Button {} label: {
                        HStack { Image(systemName: "square.and.arrow.up"); Text("Share") }
                    }
                    .buttonStyle(.bordered)
                } wrapper: {
                    WrapperButton {} label: {
                        HStack { Image(systemName: "square.and.arrow.up"); Text("Share") }
                    }
                    .buttonStyle(.bordered)
                }

                Pair(title: "frame + background (a hand-rolled design-system look)") {
                    Button("Save") {}
                        .frame(maxWidth: 120)
                        .padding(.vertical, 10)
                        .background(Color.blue.opacity(0.15))
                        .cornerRadius(8)
                } wrapper: {
                    WrapperButton("Save") {}
                        .frame(maxWidth: 120)
                        .padding(.vertical, 10)
                        .background(Color.blue.opacity(0.15))
                        .cornerRadius(8)
                }

                // `role:` is the one initializer AutographButton deliberately does not mirror. The
                // wrapper column shows what a caller gets if they swap without it — if the destructive
                // tint is lost, that is a REAL appearance regression, and the documented answer
                // (keep the literal Button, wrap the action) is the right one.
                Pair(title: "role: .destructive — wrapper has NO role (expected difference)") {
                    Button("Delete", role: .destructive) {}.buttonStyle(.bordered)
                } wrapper: {
                    WrapperButton("Delete") {}.buttonStyle(.bordered)
                }

                Text("in a List row").font(.footnote)
                List {
                    HStack {
                        Button("literal") {}
                        Spacer()
                        WrapperButton("wrapper") {}
                    }
                    .buttonStyle(.borderless)
                }
                .frame(height: 90)
            }
            .padding()
        }
    }
}
