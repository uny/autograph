import Autograph
import QuartzCore
import SwiftUI
import UIKit

/// SwiftUI opt-out from native tap autocapture — the counterpart of Compose's `Modifier.autographIgnore()`
/// and UIKit's `registerAutographIgnoredView(view:)`.
///
/// Native tap autocapture resolves a tap's target from the accessibility tree. A SwiftUI view is not
/// UIView-backed, so — unlike UIKit, where the excluded thing *is* a view — there is no view to hand the
/// registry. Instead this reports the view's **window-space rectangle** and the tap pipeline vetoes any
/// tap that lands inside it (`AutographIgnoredBounds` / `resolveNativeTapTarget`). The exclusion is purely
/// positional: the marker adds no hittable view, intercepts no touches, and does not sit on the
/// accessibility hit path.
///
/// This is a **privacy** control, and it is fail-safe about *position*: while the wrapped content is on
/// screen its rectangle is tracked every frame (via `CADisplayLink`), so scrolling or relayout can't
/// leave a stale rectangle that either leaks the moved content or deafens whatever slid into its old
/// place. Only *ambient* autocapture is suppressed — an explicit `trackClick` inside the subtree still
/// fires.
///
/// ```swift
/// SensitiveCard()
///     .autographIgnore()
/// ```
///
/// Place it as close to the sensitive content as possible: the excluded region is the rectangle at the
/// point of insertion, so a `.padding`/`.frame`/`.offset` applied *outside* `.autographIgnore()` shifts
/// or grows what is excluded. Rotation or non-rectangular clipping is approximated by the content's
/// axis-aligned bounding box, which can over-exclude.
public extension View {

    /// Excludes this view's on-screen rectangle from native tap autocapture. See ``AutographIgnore``.
    func autographIgnore() -> some View {
        background(AutographIgnoreMarker())
    }
}

/// The `UIViewRepresentable` that reports the wrapped content's window rectangle to the tap pipeline.
/// A `.background` (not `.overlay`): it must sit behind the content so it never covers it, and it carries
/// no interactivity or accessibility presence of its own.
private struct AutographIgnoreMarker: UIViewRepresentable {

    func makeUIView(context: Context) -> AutographIgnoreMarkerView {
        AutographIgnoreMarkerView()
    }

    func updateUIView(_ uiView: AutographIgnoreMarkerView, context: Context) {}

    static func dismantleUIView(_ uiView: AutographIgnoreMarkerView, coordinator: ()) {
        // Belt-and-suspenders: `didMoveToWindow(nil)` already tears down when the view leaves the window,
        // but dismantle is the one guaranteed teardown when SwiftUI drops the representable.
        MainActor.assumeIsolated { uiView.stop() }
    }
}

/// A non-interactive, accessibility-invisible marker view. It exists only to know where the wrapped
/// SwiftUI content is in the window and to keep the tap pipeline's excluded rectangle in sync with it.
@MainActor
final class AutographIgnoreMarkerView: UIView {

    /// The live registry entry, or nil while off-window. A fresh one is made on each (re)entry to a
    /// window — `unregister()` drops the entry from the registry, so a released token cannot be revived.
    private var registration: AutographIgnoredBoundsRegistration?
    private var displayLink: CADisplayLink?
    /// The last window rectangle pushed to the registry, so per-frame ticks only cross into Kotlin on a
    /// real change.
    private var lastReported: CGRect?

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("AutographIgnoreMarkerView is not decodable") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            start()
        } else {
            stop()
        }
    }

    private func start() {
        if registration == nil {
            registration = IgnoredBoundsRegistryKt.registerAutographIgnoredBounds()
        }
        if displayLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
        report()
    }

    /// Idempotent teardown: stop tracking and drop the exclusion.
    func stop() {
        displayLink?.invalidate()
        displayLink = nil
        registration?.unregister()
        registration = nil
        lastReported = nil
    }

    @objc private func tick() {
        report()
    }

    private func report() {
        guard let window, let registration else { return }
        let rect = convert(bounds, to: window) // points, window space

        // A degenerate rectangle (the content collapsed to zero size, or hidden, while still on-window)
        // must exclude nothing — NOT keep the last rect, which would leave a stale region deafening
        // whatever is now there, the very failure the per-frame tracking exists to prevent. `clear()`
        // (not `update`-ing an empty rect, which would sit at a point and deafen it) drops the region
        // while staying registered, so a later non-degenerate frame revives it.
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

        // Sub-point churn isn't worth a cross-language call; only push a genuinely moved/resized rect.
        if let last = lastReported, rectsWithinTolerance(last, rect) { return }
        lastReported = rect

        // The tap pipeline works in window *pixels* (points × screen scale) and resolves against
        // `UIScreen.main.scale` (see the native tap capture); match that space exactly.
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
