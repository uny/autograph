import Autograph
import SwiftUI
import os

/// SwiftUI screen tracking for Autograph.
///
/// The UIKit `viewDidAppear:` swizzle (`installAutographNativeScreenCapture`) cannot see SwiftUI
/// screens: every SwiftUI screen is one system-bundle `UIHostingController`, and `NavigationStack`
/// swaps its destinations inside that single host with no per-destination `viewDidAppear:`. So SwiftUI
/// screens name themselves, one line each:
///
/// ```swift
/// // Once, at the root — hand it the SAME AutographScreenCapture (built on the same ScopeStack you
/// // gave AutographProvider / the native capture, in a hybrid app):
/// ContentView().autographScreenCapture(capture)
///
/// // On each screen:
/// RecipeDetail().autographScreen("RecipeDetail")
/// ```
public extension View {

    /// Provides the ``AutographScreenCapture`` that ``SwiftUICore/View/autographScreen(_:)`` reads.
    /// Set it once, above every `.autographScreen` in the tree.
    func autographScreenCapture(_ capture: AutographScreenCapture) -> some View {
        environment(\.autographScreenCapture, capture)
    }

    /// Reports this view as a screen named [name] while it is on screen: a `Screen Viewed` on appear
    /// (carrying the previous screen), a screen frame pushed onto the shared stack so taps under it
    /// carry the screen, and the frame removed on disappear.
    ///
    /// Requires ``SwiftUICore/View/autographScreenCapture(_:)`` above it; without it, this warns (and
    /// traps in debug) rather than silently doing nothing.
    @available(iOS 14.0, *)
    func autographScreen(_ name: String) -> some View {
        modifier(AutographScreenModifier(name: name))
    }
}

// MARK: - Environment plumbing

private struct AutographScreenCaptureKey: EnvironmentKey {
    // Deliberately nil, not a no-op capture: a forgotten `.autographScreenCapture(_:)` must be
    // distinguishable from a working one, so `.autographScreen` can warn instead of silently dropping
    // every screen view.
    static let defaultValue: AutographScreenCapture? = nil
}

extension EnvironmentValues {
    var autographScreenCapture: AutographScreenCapture? {
        get { self[AutographScreenCaptureKey.self] }
        set { self[AutographScreenCaptureKey.self] = newValue }
    }
}

// MARK: - The modifier

@available(iOS 14.0, *)
struct AutographScreenModifier: ViewModifier {
    let name: String

    @Environment(\.autographScreenCapture) private var capture
    @State private var view: AutographScreenView?

    func body(content: Content) -> some View {
        content
            .onAppear { activate() }
            .onDisappear { deactivate() }
            // The name or the capture can change while this view keeps its identity (a reused row, a
            // provider swapped on logout). SwiftUI does not re-run onAppear for that, so reconcile
            // here: retire the old frame and report the new one. Keyed on both, folded into one value.
            .onChange(of: ScreenBinding(name: name, capture: capture)) { _ in
                deactivate()
                activate()
            }
    }

    private func activate() {
        // Idempotent: a second onAppear with no onDisappear in between (SwiftUI can do this) must not
        // push a second frame.
        guard view == nil else { return }
        guard let capture else {
            AutographScreenDiagnostics.missingCapture(name)
            return
        }
        view = capture.appeared(name: name)
    }

    private func deactivate() {
        view?.disappeared()
        view = nil
    }
}

/// Equatable key so `onChange` fires when either the name or the capture *instance* changes.
@available(iOS 14.0, *)
private struct ScreenBinding: Equatable {
    let name: String
    let captureID: ObjectIdentifier?

    init(name: String, capture: AutographScreenCapture?) {
        self.name = name
        self.captureID = capture.map(ObjectIdentifier.init)
    }
}

@available(iOS 14.0, *)
private enum AutographScreenDiagnostics {
    private static let log = Logger(subsystem: "dev.ynagai.autograph", category: "AutographUI")

    /// Loud on a missing capture: trap in debug (a wiring bug the developer should see immediately),
    /// and log at fault level in release so the silence of "no screen views" is at least traceable.
    static func missingCapture(_ name: String) {
        assertionFailure(
            "`.autographScreen(\"\(name)\")` has no AutographScreenCapture in its environment — call "
                + "`.autographScreenCapture(_:)` above it. No screen view was reported."
        )
        log.fault(
            "`.autographScreen(\"\(name, privacy: .public)\")` has no capture in its environment; screen view dropped."
        )
    }
}
