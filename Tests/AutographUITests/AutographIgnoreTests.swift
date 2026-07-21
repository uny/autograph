import Autograph
@testable import AutographUI
import SwiftUI
import UIKit
import XCTest

/// Drives the **real** `.autographIgnore()` modifier through the **real** umbrella tap resolver, both
/// backed by the one `AutographIgnoredBounds` registry inside the umbrella framework.
///
/// The thing under test is the SwiftUI *plumbing*: does the marker compute the wrapped content's window
/// rectangle correctly (`convert(bounds:)` × `UIScreen.main.scale`) and keep it registered as the content
/// moves? We cannot read that rectangle back (the registry is internal), so we observe it the only way the
/// pipeline exposes it — the positional veto `resolveNativeTapTarget` applies **before** it walks anything.
///
/// Why a hand-built `UIView` probe rather than the hosted SwiftUI button: SwiftUI's accessibility tree
/// does not materialise in-process (no accessibility client), so the resolver finds nothing in a hosted
/// SwiftUI view and every "not reported" would be vacuous. A plain `UIView` added to the same real window,
/// with an `accessibilityFrame`, IS walkable in-process (the Kotlin resolver tests rely on this), so it
/// gives a real positive: a point outside the ignored region resolves to it. Both the marker and the probe
/// live in one real key window, so their rectangles share the window-pixel space the veto compares in.
///
/// **Fault-injection (manual, per repo discipline):** revert the `AutographIgnoredBounds.contains(...)`
/// veto at the top of `resolveNativeTapTarget`; the "inside is vetoed" assertions must go red (the inside
/// point starts resolving to the probe). If they stay green with the veto gone, they measure nothing.
@available(iOS 14.0, *)
final class AutographIgnoreTests: XCTestCase {

    private var window: UIWindow!
    private var host: UIHostingController<AnyView>!
    private var probe: UIView?

    /// The ignored content's fixed size in points; its window rectangle is `(origin, size)`.
    private let size = CGSize(width: 200, height: 80)
    private var scale: CGFloat { UIScreen.main.scale }

    override func setUp() {
        super.setUp()
        window = UIWindow(frame: UIScreen.main.bounds)
        window.makeKeyAndVisible()
    }

    override func tearDown() {
        probe?.removeFromSuperview()
        host?.view.removeFromSuperview() // fires didMoveToWindow(nil) → the marker unregisters
        RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        window?.isHidden = true
        window = nil
        host = nil
        probe = nil
        super.tearDown()
    }

    /// Hosts a clear, `.autographIgnore()`'d rectangle of `size` at `origin` in the key window and lets
    /// the marker enter the window and report (its first report is synchronous on window-entry; the
    /// run-loop spin also drives the `CADisplayLink` for the move test).
    private func hostIgnoredRegion(at origin: CGPoint) {
        let content = Color.clear.frame(width: size.width, height: size.height).autographIgnore()
        let root = AnyView(content)
        if host == nil {
            host = UIHostingController(rootView: root)
            window.addSubview(host.view)
        } else {
            host.rootView = root
        }
        host.view.frame = CGRect(origin: origin, size: size)
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.15))
    }

    private func px(_ point: CGPoint) -> AxPoint {
        AxPoint(x: Float(point.x * scale), y: Float(point.y * scale))
    }

    /// Resolves the tap at `windowPoint`, having placed a walkable button-role probe covering
    /// `probeFrame` (window points) in the same window. The resolver root is a container whose
    /// `accessibilityFrame` spans the screen (a `UIWindow`'s own is zero — it isn't an accessibility
    /// element — so it can't be the root); the probe is its child, in the real window, so its window-pixel
    /// rectangle is in the same space as the marker's. Window origin == screen origin for a standard
    /// full-screen window, so `accessibilityFrame` (screen space) equals the window frame here.
    private func resolveWithProbe(at windowPoint: CGPoint, probeCovering probeFrame: CGRect, id: String = "probe") -> String? {
        probe?.removeFromSuperview()
        let container = UIView(frame: window.bounds)
        container.accessibilityFrame = window.bounds
        let button = UIView(frame: probeFrame)
        button.isAccessibilityElement = true
        button.accessibilityTraits = .button
        button.accessibilityIdentifier = id
        button.accessibilityFrame = probeFrame
        container.addSubview(button)
        window.addSubview(container)
        probe = container
        return NativeTapResolutionKt.resolveNativeTapTarget(root: container, positionInWindowPx: px(windowPoint), scale: Float(scale))
    }

    // MARK: - Non-vacuity: the probe resolves where it should, so a "not resolved" means something

    func testTheProbeResolvesWithNoIgnoredRegion() throws {
        XCTAssertEqual(
            resolveWithProbe(at: CGPoint(x: 50, y: 50), probeCovering: CGRect(x: 40, y: 40, width: 20, height: 20)),
            "probe"
        )
    }

    // MARK: - The exclusion is real and correctly placed

    func testAPointInsideTheIgnoredRegionIsVetoedAndOutsideResolves() throws {
        hostIgnoredRegion(at: .zero) // window rect (0, 0, 200, 80) points

        XCTAssertNil(
            resolveWithProbe(at: CGPoint(x: 100, y: 40), probeCovering: CGRect(x: 90, y: 30, width: 20, height: 20)),
            "a point inside the ignored region must be vetoed"
        )
        XCTAssertEqual(
            resolveWithProbe(at: CGPoint(x: 100, y: 300), probeCovering: CGRect(x: 90, y: 290, width: 20, height: 20)),
            "probe",
            "a point outside the region still resolves — the region is the marker's, not the whole window"
        )
    }

    // MARK: - The marker must not intercept touches (interactivity is preserved)

    /// The failure mode that sank the first PR-B attempt was a marker that sat on the touch path and
    /// swallowed the wrapped control's taps. This marker is `userInteractionEnabled = false` and sits
    /// behind the content (`.background`), so it must decline hit-testing entirely: a touch at its centre
    /// resolves to something that is *not* the marker, and the marker's own `hitTest` returns nil.
    func testTheMarkerDeclinesHitTestingSoTheContentStaysInteractive() throws {
        let content = Button("b") {}.accessibilityIdentifier("b")
        host = UIHostingController(rootView: AnyView(content.frame(width: size.width, height: size.height)))
        host.view.frame = CGRect(origin: .zero, size: size)
        window.addSubview(host.view)
        // Re-wrap through .autographIgnore() by hosting the modified view.
        host.rootView = AnyView(content.frame(width: size.width, height: size.height).autographIgnore())
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.15))

        let marker = firstMarker(in: host.view)
        XCTAssertNotNil(marker, "the .autographIgnore() marker should be in the hierarchy")
        XCTAssertNil(
            marker?.hitTest(CGPoint(x: size.width / 2, y: size.height / 2), with: nil),
            "a userInteractionEnabled=false marker must decline the touch"
        )
        let hit = window.hitTest(CGPoint(x: size.width / 2, y: size.height / 2), with: nil)
        XCTAssertFalse(hit === marker, "the marker must never be the hit-test target — touches pass to the content")
    }

    private func firstMarker(in view: UIView) -> AutographIgnoreMarkerView? {
        if let marker = view as? AutographIgnoreMarkerView { return marker }
        for subview in view.subviews {
            if let found = firstMarker(in: subview) { return found }
        }
        return nil
    }

    // MARK: - Dynamic tracking: the veto follows the content when it moves

    /// The reason for the `CADisplayLink`: when the wrapped content moves, the excluded rectangle must
    /// move with it — no stale rectangle leaking the moved content nor deafening its old place.
    func testTheVetoFollowsTheContentWhenItMoves() throws {
        hostIgnoredRegion(at: .zero)
        XCTAssertNil(
            resolveWithProbe(at: CGPoint(x: 100, y: 40), probeCovering: CGRect(x: 90, y: 30, width: 20, height: 20)),
            "excluded at the original position"
        )

        hostIgnoredRegion(at: CGPoint(x: 0, y: 200)) // window rect now (0, 200, 200, 80)

        XCTAssertNil(
            resolveWithProbe(at: CGPoint(x: 100, y: 240), probeCovering: CGRect(x: 90, y: 230, width: 20, height: 20)),
            "excluded at the new position — the rectangle followed the content"
        )
        XCTAssertEqual(
            resolveWithProbe(at: CGPoint(x: 100, y: 40), probeCovering: CGRect(x: 90, y: 30, width: 20, height: 20)),
            "probe",
            "the old position is no longer excluded — no stale rectangle left behind"
        )
    }

    /// Content that collapses to zero size while still on-window (a collapsed section, a hidden view)
    /// must stop excluding its old rectangle — otherwise the region deafens whatever is now there. The
    /// marker never leaves the window, so only `report()` clearing the rect on a degenerate frame prevents
    /// the stale exclusion.
    func testCollapsingTheContentToZeroSizeClearsTheExclusion() throws {
        hostIgnoredRegion(at: .zero)
        XCTAssertNil(
            resolveWithProbe(at: CGPoint(x: 100, y: 40), probeCovering: CGRect(x: 90, y: 30, width: 20, height: 20)),
            "excluded while the content has size"
        )

        // Collapse the content to zero height, still mounted in the window.
        host.rootView = AnyView(Color.clear.frame(width: size.width, height: 0).autographIgnore())
        host.view.frame = CGRect(x: 0, y: 0, width: size.width, height: 0)
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.15))

        XCTAssertEqual(
            resolveWithProbe(at: CGPoint(x: 100, y: 40), probeCovering: CGRect(x: 90, y: 30, width: 20, height: 20)),
            "probe",
            "a zero-size (collapsed) ignored view must stop excluding its old rectangle"
        )
    }
}
