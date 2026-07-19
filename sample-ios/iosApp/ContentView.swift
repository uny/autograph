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
            NativeSampleCaptureKt.installNativeSampleCapture { target in
                events.lastEvent = target
            }
        }
    }
}

struct ContentView: View {
    var body: some View {
        if ProcessInfo.processInfo.arguments.contains(nativeSampleLaunchArgument) {
            NativeSampleView()
        } else {
            ComposeView()
        }
    }
}
