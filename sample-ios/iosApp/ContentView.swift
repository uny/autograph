import SwiftUI
import UIKit
import sample_shared

/// Hosts `sample-shared`'s `MainViewController()` — the Compose UI (see `App.kt`) that exercises
/// every README snippet against a `LoggingTracker` printing via `NSLog`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Launch argument that swaps the Compose sample for the SwiftUI-only one.
///
/// A launch argument rather than in-app navigation so the two screens stay independent: the Compose
/// XCUITests launch with no arguments and are unaffected by anything the native screen does, and the
/// native screen is reached without a tap that the native capture would itself observe.
let nativeSampleLaunchArgument = "-autograph-native-sample"

/// Holds what the native capture last reported, so the SwiftUI screen can display it.
///
/// A class rather than `@State` on the view: the callback handed to Kotlin escapes, and a struct's
/// `@State` cannot be written from an escaping closure that captured a copy of the struct.
final class NativeSampleEvents: ObservableObject {
    @Published var lastEvent = "(none yet)"
    /// The full properties JSON of the last reported tap, so a UI test can observe the `screen` /
    /// `section` a native tap was enriched with — not just its target.
    @Published var lastProps = "(none yet)"
}

/// A SwiftUI-only screen — no Compose anywhere in it — exercising `autograph-uikit`'s native tap
/// capture end to end, through real synthetic touches.
///
/// The content is chosen to cover the shapes that have actually broken:
/// - a `List`, whose full-screen `_UITouchPassthroughView` overlay swallowed every tap until #82
/// - enough rows to scroll, because a scroll leaves a touch-begin position behind and the *next* tap
///   used to be resolved against it (#83)
/// - a button carrying no `.accessibilityIdentifier`, which must be dropped rather than reported
///   under some fallback name
struct NativeSampleView: View {
    @StateObject private var events = NativeSampleEvents()

    var body: some View {
        VStack(spacing: 12) {
            Text("Last event target: \(events.lastEvent)")
                .accessibilityIdentifier("native_last_event_label")
            Text("Last event props: \(events.lastProps)")
                .accessibilityIdentifier("native_last_event_props_label")

            Button("Plain") {}
                .accessibilityIdentifier("native_plain_button")

            // Deliberately no .accessibilityIdentifier: there is no stable name to report this
            // under, and identification must not fall back to the displayed text.
            Button("Unidentified") {}

            List(0..<40, id: \.self) { index in
                Button("Row \(index)") {}
                    .accessibilityIdentifier("native_row_\(index)")
            }
            .accessibilityIdentifier("native_list")
        }
        .onAppear {
            NativeSampleCaptureKt.installNativeSampleCapture { target, props in
                events.lastEvent = target
                events.lastProps = props
            }
        }
    }
}

/// Launch argument for the hybrid screen: SwiftUI content and a Compose host in one window, with the
/// native capture running and Compose autocapture off.
let hybridSampleLaunchArgument = "-autograph-hybrid-sample"

/// Hosts `HybridViewController()` — Compose with autocapture *off*, embedded in the native screen.
struct ComposeHybridView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        HybridSampleKt.HybridViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// A hybrid screen: native SwiftUI content and Compose content in the same window, with the native
/// capture installed.
///
/// The boundary this exercises is a privacy invariant, not a de-duplication nicety. Content under a
/// Compose host belongs to the Compose pipeline *exclusively* — not "content the Compose pipeline
/// reported". Here Compose reports nothing at all, so if the native side is not held off the Compose
/// subtree it will happily walk in and report elements whose exclusions it cannot see.
struct HybridSampleView: View {
    @StateObject private var events = NativeSampleEvents()

    var body: some View {
        VStack(spacing: 12) {
            Text("Last event target: \(events.lastEvent)")
                .accessibilityIdentifier("native_last_event_label")

            Button("Native") {}
                .accessibilityIdentifier("native_button_in_hybrid")

            ComposeHybridView()
        }
        .onAppear {
            NativeSampleCaptureKt.installNativeSampleCapture { target, props in
                events.lastEvent = target
                events.lastProps = props
            }
        }
    }
}

struct ContentView: View {
    var body: some View {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains(nativeSampleLaunchArgument) {
            NativeSampleView()
        } else if arguments.contains(hybridSampleLaunchArgument) {
            HybridSampleView()
        } else {
            ComposeView()
        }
    }
}
