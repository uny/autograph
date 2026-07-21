import QuartzCore
import SwiftUI
import UIKit
import sample_shared

/// Launch argument for the SwiftUI screen that exercises `.autographIgnore()`'s window-region opt-out.
let ignoreSampleLaunchArgument = "-autograph-ignore-sample"

/// Launch argument for the *baseline* variant: the same button with **no** `.autographSampleIgnore()`, so
/// a UI test can prove the button is capturable to begin with — the check the first PR-B attempt lacked,
/// which let a never-capturable button read as "successfully excluded".
let ignoreBaselineLaunchArgument = "-autograph-ignore-baseline"

/// A SwiftUI-only screen exercising the native tap opt-out end to end, through real synthetic touches.
///
/// Both the tap capture and the region registration go through `sample_shared` (see `NativeSampleIgnore`
/// for why that single-framework routing is load-bearing), so the veto actually sees what the marker
/// registers. The shipped `AutographUI.autographIgnore()` is verified separately in `AutographUITests`;
/// this drives the identical marker under a real touch pipeline, and adds what an in-process test can't:
/// the wrapped button's action still fires (its tap counter increments) even though the tap is not
/// autocaptured.
struct IgnoreSampleView: View {
    @StateObject private var events = NativeSampleEvents()
    @State private var shownTaps = 0
    @State private var ignoredTaps = 0

    /// When true, the button under test carries no opt-out modifier (the capturability baseline).
    let baseline: Bool

    var body: some View {
        VStack(spacing: 12) {
            Text("Last event target: \(events.lastEvent)")
                .accessibilityIdentifier("native_last_event_label")
            Text("Shown taps: \(shownTaps)")
                .accessibilityIdentifier("ignore_shown_tap_count")
            Text("Ignored taps: \(ignoredTaps)")
                .accessibilityIdentifier("ignore_ignored_tap_count")

            // A control that is never excluded: proves capture is live and that an un-ignored button is
            // reported. Also the neighbour that must keep reporting — the exclusion is one button's, not
            // the screen's.
            Button("Shown") { shownTaps += 1 }
                .accessibilityIdentifier("ignore_shown_button")

            ignoredButton
        }
        .onAppear {
            NativeSampleCaptureKt.installNativeSampleCapture { target, props in
                events.lastEvent = target
                events.lastProps = props
            }
        }
    }

    @ViewBuilder private var ignoredButton: some View {
        let button = Button("Ignored") { ignoredTaps += 1 }
            .accessibilityIdentifier("ignore_ignored_button")
        if baseline {
            button
        } else {
            button.autographSampleIgnore()
        }
    }
}

// MARK: - The sample's stand-in for AutographUI's `.autographIgnore()`

/// Intentionally identical in shape to the shipped `AutographUI` marker (`AutographIgnore.swift`) — a
/// non-interactive, accessibility-invisible `.background` `UIViewRepresentable` that reports its window
/// rectangle every frame via `CADisplayLink`. The only difference is where it gets its registration: this
/// drives `sample_shared`'s `registerSampleIgnoredBounds` so both it and the sample's tap capture share
/// one `AutographIgnoredBounds` registry (see `NativeSampleIgnore`). Mirrors `AutographSampleScreen`'s
/// relationship to the shipped `.autographScreen`.
extension View {
    func autographSampleIgnore() -> some View {
        background(SampleIgnoreMarker())
    }
}

private struct SampleIgnoreMarker: UIViewRepresentable {
    func makeUIView(context: Context) -> SampleIgnoreMarkerView { SampleIgnoreMarkerView() }
    func updateUIView(_ uiView: SampleIgnoreMarkerView, context: Context) {}
    static func dismantleUIView(_ uiView: SampleIgnoreMarkerView, coordinator: ()) {
        MainActor.assumeIsolated { uiView.stop() }
    }
}

@MainActor
final class SampleIgnoreMarkerView: UIView {
    private var registration: SampleIgnoredBounds?
    private var displayLink: CADisplayLink?
    private var lastReported: CGRect?

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("SampleIgnoreMarkerView is not decodable") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { start() } else { stop() }
    }

    private func start() {
        if registration == nil {
            registration = NativeSampleIgnoreKt.registerSampleIgnoredBounds()
        }
        if displayLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
        report()
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
        registration?.unregister()
        registration = nil
        lastReported = nil
    }

    @objc private func tick() { report() }

    private func report() {
        guard let window, let registration else { return }
        let rect = convert(bounds, to: window)
        guard rect.width > 0, rect.height > 0,
              rect.origin.x.isFinite, rect.origin.y.isFinite,
              rect.size.width.isFinite, rect.size.height.isFinite
        else {
            if lastReported != nil {
                lastReported = nil
                registration.clear()
            }
            return
        }
        if let last = lastReported, rectsWithinTolerance(last, rect) { return }
        lastReported = rect
        let scale = Float(UIScreen.main.scale)
        registration.update(
            left: Float(rect.minX) * scale,
            top: Float(rect.minY) * scale,
            right: Float(rect.maxX) * scale,
            bottom: Float(rect.maxY) * scale
        )
    }

    private func rectsWithinTolerance(_ a: CGRect, _ b: CGRect) -> Bool {
        let tolerance: CGFloat = 0.5
        return abs(a.minX - b.minX) < tolerance
            && abs(a.minY - b.minY) < tolerance
            && abs(a.maxX - b.maxX) < tolerance
            && abs(a.maxY - b.maxY) < tolerance
    }
}
