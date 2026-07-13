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

struct ContentView: View {
    var body: some View {
        ComposeView()
    }
}
