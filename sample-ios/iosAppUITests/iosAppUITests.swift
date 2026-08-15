import XCTest

/// Permanent on-device regression coverage for `ElementResolver.ios.kt`'s hit-testing —
/// `compose.uiTest`'s iOS scene doesn't render into `LocalUIView`, so unit tests alone can't
/// exercise a live resolve. `XCUIApplication` taps drive real synthetic touches through the OS,
/// exactly like a user; the app under test surfaces what got resolved via a plain Compose `Text`
/// (`last_event_label`, see `App.kt`) since a UI test can't read Kotlin state directly.
final class iosAppUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["last_event_label"].label
    }

    /// The full properties JSON an event was attributed with — screen, section, and scope keys — which
    /// the `target`-only label cannot show. See `App.kt`'s `last_event_props_label`.
    private func lastEventProps(_ app: XCUIApplication) -> String {
        app.staticTexts["last_event_props_label"].label
    }

    /// Blocks until `last_event_label` names [target], then asserts it.
    ///
    /// A tap's event reaches the label through a Compose recomposition, which `XCUIElement.tap()`'s
    /// own idle wait does not necessarily cover — so reading the label eagerly asserts on whatever
    /// happens to be there, and on a slow enough machine that is the value from *before* the tap.
    /// Measured on CI, which runs this suite roughly twice as slowly as a developer machine:
    /// `testDisabledElementIsNotCaptured`'s known-good control read the launch impression's target
    /// after tapping `plain_button`, and reported autocapture as dead when it was merely late.
    ///
    /// Safe here in a way it is deliberately NOT for the ordered `track_log_label` assertions: those
    /// exist to catch an event that should never have fired, and waiting for the expected string
    /// would pass on a duplicate simply because it had not been appended yet. This waits for a
    /// *positive* claim — that the tap produced this target — where arriving late and arriving are
    /// the same thing, and where the failure mode is a timeout carrying the actual value.
    private func waitForLastEventTarget(
        _ app: XCUIApplication,
        _ target: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let expected = "Last event target: \(target)"
        // XCTWaiter, not waitForExpectations: the latter records its own failure, and with
        // continueAfterFailure = false that aborts the test before any handler of ours runs — so the
        // report reads "<unknown>:0, expected <string>" with neither the call site nor the value
        // actually seen. XCTWaiter returns a result instead and leaves the reporting to us.
        let seen = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: app.staticTexts["last_event_label"]
        )
        if XCTWaiter().wait(for: [seen], timeout: 15) != .completed {
            XCTFail("expected \(expected), last saw \(lastEventLabel(app))", file: file, line: line)
        }
    }

    /// Launches and blocks until both launch-time impressions have landed.
    ///
    /// Every assertion in this class reads `last_event_label` or the ordered `track_log_label`, and an
    /// impression that fires *after* the tap corrupts both — `trackImpression`'s 500 ms dwell
    /// (`minDurationMs`) outlives `app.launch()`, which does not wait for it. Tapping before the
    /// baseline is established therefore races the impression, and the label ends up naming the
    /// impression instead of the tap.
    ///
    /// This lives in the shared launch path rather than in the tests that happened to fail: the race
    /// was latent from the moment the sample had one launch-time impression, and only widened when it
    /// gained a second (#153's `impression_inner`). A fast machine wins it every time, which is why it
    /// surfaced on CI and never locally — so "the test passes here" is not evidence any individual
    /// test is safe without the wait.
    private func launchSettled() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        waitForImpressionBaseline(app)
        return app
    }

    func testPlainButtonAttribution() {
        let app = launchSettled()
        app.buttons["plain_button"].tap()
        waitForLastEventTarget(app, "plain_button")
    }

    /// A tap on the inner element of a clickable-inside-clickable pair must attribute to the
    /// inner element, not the outer ancestor it's nested in.
    func testNestedClickableAttributesToInnerElement() {
        let app = launchSettled()
        app.buttons["inner_button"].tap()
        waitForLastEventTarget(app, "inner_button")
    }

    /// A tap on the outer container, outside the inner element's bounds, must attribute to the
    /// outer element, not always default to whichever is nested deepest.
    func testOuterContainerAttributesToOuterElement() {
        let app = launchSettled()
        // Coordinate well outside inner_button's bounds but inside outer_container's.
        let outer = app.buttons["outer_container"]
        outer.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        waitForLastEventTarget(app, "outer_container")
    }

    /// The ordered `name:target` log of every `track` call. See `App.kt`'s `track_log_label`.
    ///
    /// `lastEventLabel` cannot state "fired exactly once" for explicit instrumentation: an
    /// autocaptured duplicate carries the *same* target, so the two are indistinguishable by target
    /// alone — which is precisely how #151 went unnoticed. The event name separates them.
    private func trackLog(_ app: XCUIApplication) -> String {
        app.staticTexts["track_log_label"].label
    }

    /// The log every test below starts from: both of the sample's launch-time impressions, in
    /// composition order. Written out once so the ordered-log assertions state only their own tap.
    ///
    /// Every assertion against this log is preceded by [waitForLastEventTarget]. The two are not
    /// redundant and neither replaces the other: the wait establishes that the tap's event *arrived*
    /// (a positive claim, where late and never are the same failure), and the eager read that
    /// follows is what states nothing arrived *besides* it. Waiting on the log string itself would
    /// destroy the second half — it would pass on a duplicate that simply had not been appended yet.
    /// Measured on CI, which runs this suite roughly twice as slowly as a developer machine:
    /// `testClickableHostingAnImpressionElementIsAutocapturedNearItsEdgeToo` read the log before its
    /// tap's entry landed and reported the tap as dropped when it was merely late.
    private static let impressionBaseline =
        "Tracks: Recipe Viewed:recipe_card|Recipe Viewed:impression_inner"

    /// Blocks until the launch-time `Recipe Viewed` impressions are in the log.
    ///
    /// `trackImpression` only fires after its 500 ms dwell (`minDurationMs`), which `app.launch()`
    /// does not wait for — so a test that asserts the WHOLE ordered log has to establish that
    /// baseline before it taps, or the tap's own entry can land first and the assertion fails on
    /// ordering rather than on the behaviour under test.
    ///
    /// The two impressions share a dwell and become visible together, so their relative order is
    /// Compose's composition order rather than anything this test controls. That is stable in
    /// practice; if it ever stopped being, this wait times out loudly rather than mis-asserting.
    private func waitForImpressionBaseline(_ app: XCUIApplication) {
        expectation(
            for: NSPredicate(format: "label == %@", Self.impressionBaseline),
            evaluatedWith: app.staticTexts["track_log_label"]
        )
        // Generous, and costs nothing when the impressions are prompt — the wait returns as soon as
        // the predicate holds. CI's simulator is roughly 3x slower than a developer machine here, and
        // the failure this guards against is the whole reason for the wait, so a timeout that is
        // merely "usually enough" would trade one flake for another.
        waitForExpectations(timeout: 15)
    }

    /// Modifier.trackClick fires its own explicit event; autocapture must not also report it.
    func testExplicitTrackClickFiresExactlyOnce() {
        let app = launchSettled()
        app.buttons["explicit_tracked_button"].tap()
        waitForLastEventTarget(app, "explicit_tracked_button")
        // The tap must add exactly one more entry, and it must be the explicit event rather than an
        // autocaptured Element Clicked. Read eagerly, not awaited: a wait for this exact string would
        // pass on a duplicate that had not been appended yet.
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Recipe Saved:explicit_tracked_button"
        )
    }

    /// The same, on an element SHORTER than the minimum touch target — #151, and now #179.
    ///
    /// Compose expands such an element's touch target, and the accessibility frame it publishes with
    /// it, while the rect `trackClick` used to register stayed the unexpanded layout bounds; iOS
    /// matched the two by rect equality, so the suppression silently stopped applying and the element
    /// was reported twice. The 56.dp box above is above the threshold and never showed it.
    ///
    /// It covers #179 only because the fixture was moved into a `Column` with no arrangement spacing.
    /// With the 12.dp gap it used to sit in, Compose applied the minimum at *layout* time and the
    /// element was already 48.dp — measured, the published frame was then identical to its bounds and
    /// no expansion was involved at all, so this test passed without exercising what it names. Butted
    /// against the clickable below it, Compose clamps the expansion to one side and the frame is
    /// neither the element's bounds nor those bounds expanded symmetrically. That is the case no
    /// derived rectangle could match, and why the veto is now decided by execution instead.
    func testExplicitTrackClickOnASmallElementFiresExactlyOnce() {
        let app = launchSettled()
        app.buttons["explicit_tracked_small"].tap()
        waitForLastEventTarget(app, "explicit_tracked_small")
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Recipe Saved:explicit_tracked_small"
        )
    }

    /// A `trackImpression` element must not suppress the clickable enclosing it — #153, #158.
    ///
    /// The 48dp host is NOT instrumented, so autocapture owns its taps. The `trackImpression` `Text`
    /// filling it is, and iOS cannot read that marker off the ancestry: it consulted a registered
    /// rect instead, which for a coincident inner element is the host's frame exactly, so the host
    /// was read as already-instrumented and its tap vanished with nothing reporting it.
    /// `trackImpression` now registers no rect at all, since it reports a visibility event and never
    /// a click — there was never a click of its own for autocapture to duplicate.
    ///
    /// On-device rather than only in `ElementResolverIosTest`, which hand-builds its `UIView` tree:
    /// what the old veto turned on was Compose Multiplatform's own bridging of these two elements,
    /// and a test that constructs the tree itself assumes exactly the bridge behaviour in question
    /// (the same reason #134/#135 are pinned here).
    func testClickableHostingAnImpressionElementIsStillAutocaptured() {
        let app = launchSettled()
        app.buttons["impression_inner_host"].tap()
        waitForLastEventTarget(app, "impression_inner_host")
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Element Clicked:impression_inner_host"
        )
    }

    /// The same host tapped near its top edge rather than its centre. Nothing about the suppression
    /// ever depended on where the tap landed, so this must behave identically — it is a separate test
    /// because an implementation that consulted only the hit path would pass the centre tap above
    /// while still dropping a tap that resolves through a different branch.
    func testClickableHostingAnImpressionElementIsAutocapturedNearItsEdgeToo() {
        let app = launchSettled()
        let host = app.buttons["impression_inner_host"]
        host.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        waitForLastEventTarget(app, "impression_inner_host")
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Element Clicked:impression_inner_host"
        )
    }

    /// A `trackClick` element at the top of a taller uninstrumented clickable, tapped on the strip of
    /// the host it leaves exposed — #179/#180, and the case that decided the mechanism.
    ///
    /// The host's own tap belongs to autocapture: no explicit event is produced for it, so failing to
    /// report it loses the interaction outright rather than duplicating it. Measured, the two publish
    /// frames of the same size that both contain the inner element's bounds, differing only in where
    /// Compose's touch-target clamp landed — so a veto decided by geometry either left the inner
    /// double-reported or matched the host's frame as well and dropped this tap with no event at all.
    /// Deciding it by execution needs no comparison: tapping here runs no `trackClick`.
    ///
    /// On-device rather than in `ElementResolverIosTest`: what is at stake is the pair of frames
    /// Compose Multiplatform publishes for these two elements, and a test that builds the tree itself
    /// would be assuming the very thing in question.
    func testExposedStripOfAClickableHostingATrackClickIsAutocaptured() {
        let app = launchSettled()
        let host = app.buttons["clamped_inner_host"]
        // Low in the host, below the inner element's own expanded frame. 0.9 rather than 1.0 so the
        // tap stays clear of the boundary itself.
        host.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9)).tap()
        waitForLastEventTarget(app, "clamped_inner_host")
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Element Clicked:clamped_inner_host"
        )
    }

    /// The other half of the same fixture: the inner element still reports exactly once.
    ///
    /// Without this, a suppression that had simply stopped working would pass the test above.
    func testTrackClickInsideAClickableHostFiresExactlyOnce() {
        let app = launchSettled()
        app.buttons["clamped_inner"].tap()
        waitForLastEventTarget(app, "clamped_inner")
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Recipe Saved:clamped_inner"
        )
    }

    /// The same explicit instrumentation as `explicit_tracked_small`, under a scale transform — #159,
    /// and now a guard against #179's fix regressing.
    ///
    /// Compose qualifies the touch target on the element's MEASURED size and draws the result through
    /// the transform, so the published accessibility frame is the minimum *scaled*. Under the
    /// geometric veto the registered rect had to carry that scale to match it, and the element
    /// reported twice until it did. #179 deleted that reconciliation with the rest of the geometry,
    /// which is what this case now covers: suppression decided by execution must be indifferent to
    /// the transform, and a veto that quietly reacquired a geometric dependency would fail here
    /// while `explicit_tracked_small` still passed.
    func testScaledTrackClickFiresExactlyOnce() {
        let app = launchSettled()
        app.buttons["scaled_tracked_small"].tap()
        waitForLastEventTarget(app, "scaled_tracked_small")
        // Read eagerly after the wait, exactly as testExplicitTrackClickFiresExactlyOnce does: the
        // wait keeps CI's slower simulator from reading the log before the tap lands, while the
        // eager read still fails on a duplicate rather than waiting one out.
        XCTAssertEqual(
            trackLog(app),
            Self.impressionBaseline + "|Recipe Saved:scaled_tracked_small"
        )
    }

    /// Modifier.autographIgnore excludes a subtree from autocapture entirely — the label (last set
    /// by the launch-time impressions, which [launchSettled] has already waited out) must stay
    /// unchanged.
    func testIgnoredElementIsNotCaptured() {
        let app = launchSettled()
        let beforeTap = lastEventLabel(app)
        app.buttons["ignored_button"].tap()
        XCTAssertEqual(lastEventLabel(app), beforeTap)
    }

    /// A disabled clickable swallows the tap and fires nothing, so reporting a click would invent an
    /// event (#134, the iOS counterpart of #128).
    ///
    /// Only an on-device test can pin this half. It rests on Compose Multiplatform actually bridging
    /// `clickable(enabled = false)` as `UIAccessibilityTraitNotEnabled` — a property of the bridge,
    /// which a unit test over a hand-built `UIView` tree cannot establish, because such a test sets
    /// the trait itself.
    func testDisabledElementIsNotCaptured() {
        let app = launchSettled()
        let beforeTap = lastEventLabel(app)
        let disabled = app.buttons["disabled_button"]

        // This test asserts an ABSENCE, and its tap is the one on this screen that cannot fail
        // loudly: `coordinate(...).tap()` does not require hittability (see below), so a fixture
        // pushed off the bottom of this non-scrolling screen would be tapped at a coordinate that
        // is simply nowhere, and "no new event" would pass for the wrong reason. Every other
        // fixture here is reached with `.tap()`, which errors when the element is not hittable and
        // so polices its own visibility. `App.kt`'s DemoScreen comment records the height budget
        // this guards; #179 spent some of it on `clamped_inner_host`.
        XCTAssertTrue(
            app.windows.firstMatch.frame.contains(disabled.frame),
            "disabled_button must be fully on screen or this test passes vacuously — "
                + "its frame \(disabled.frame) is outside \(app.windows.firstMatch.frame)"
        )

        // A coordinate tap, not `.tap()`: XCUITest considers a disabled element non-hittable, while
        // the element does still receive the touch — which is the whole state under test.
        disabled
            .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .tap()

        XCTAssertEqual(lastEventLabel(app), beforeTap)

        // Without this the assertion above also passes on an app where autocapture never ran at all.
        app.buttons["plain_button"].tap()
        waitForLastEventTarget(app, "plain_button")
    }

    /// An autocaptured tap must carry the screen, section, and scope it happened under — not just its
    /// target. The sample wraps its content in `AutographScope("article_id" to "42")` +
    /// `TrackedScreen("Sample", section = "Main")`, all of which mirror into the ambient stack the
    /// autocapture observer reads. Before the harness widening, this context was surfaced nowhere and
    /// the swizzle work (#65) would have had no way to verify its output on-device.
    func testAutocapturedTapCarriesScreenSectionAndScope() {
        let app = launchSettled()
        app.buttons["plain_button"].tap()
        // Pin the props to the TAP's own event. The launch-time impressions are themselves enriched
        // with the same screen/section/scope, so `last_event_props_label` already satisfies the
        // assertions below before any tap — asserting the last event was the plain_button tap
        // (target label, whose value only the tap produces) is what makes this observe the tap and
        // not the stale impression. Without it, a tap that fired no event at all would pass green.
        waitForLastEventTarget(app, "plain_button")
        let props = lastEventProps(app)
        XCTAssertTrue(props.contains("\"screen\":\"Sample\""), "screen missing from props: \(props)")
        XCTAssertTrue(props.contains("\"section\":\"Main\""), "section missing from props: \(props)")
        XCTAssertTrue(props.contains("\"article_id\":\"42\""), "scope missing from props: \(props)")
    }

    /// The Screen Viewed channel the native screen capture (#65) reports through, proven on the Compose
    /// side first: `TrackedScreen` fires exactly one Screen Viewed on entry, with no `previous_screen`
    /// (it is the first screen). The ordered log — not a last-value label — is what will later let a
    /// test see a screen was not double-emitted.
    func testScreenViewIsObservableAndFiresOnce() {
        let app = launchSettled()
        // TrackedScreen fires its Screen Viewed from a composition effect, so the label recomposes
        // from "(none yet)" a beat after launch. Wait for the value rather than reading it eagerly.
        let label = app.staticTexts["screen_view_log_label"]
        let expected = "Screen views: Sample:(none)"
        expectation(for: NSPredicate(format: "label == %@", expected), evaluatedWith: label)
        waitForExpectations(timeout: 5)
    }
}

/// Coverage for the *native* (UIKit/SwiftUI) capture in `autograph-uikit` — a separate pipeline from
/// the Compose one above, sharing only the accessibility-tree walk.
///
/// This suite exists because that walk's defects have consistently been invisible to unit tests: a
/// hand-built `UIView` tree has none of the structure a real SwiftUI hierarchy produces. #77 (no
/// identifier off UIKit views), #82 (a full-screen passthrough overlay swallowing every tap) and #83
/// (a scroll leaving its touch-begin position behind for the next tap) all shipped green unit suites.
/// Every test below is aimed at one of those shapes.
final class NativeSampleUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchNativeSample() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-native-sample"]
        app.launch()
        return app
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["native_last_event_label"].label
    }

    /// Scrolls the list with a real touch stream.
    ///
    /// **Deliberately not `swipeUp()`.** Measured on the simulator: XCUITest's swipe synthesis is
    /// reported as a *tap* by a window-level `UITapGestureRecognizer` — at `.slow`, `.default` and
    /// `.fast` alike — while an explicit press-and-drag is not, at durations both shorter (0.05s) and
    /// longer (0.2s) than the swipe. So the variable is XCUITest's synthesis, not gesture speed, and a
    /// `swipeUp` does not exercise what a user scrolling actually delivers.
    ///
    /// This matters beyond one assertion: a "scroll" that is really a tap fires the action that clears
    /// the recorded touch-begin position, so `testNativeTapAfterScrollAttributesToTheTappedRow` would
    /// pass without ever reproducing the #83 bug it exists to pin. It was written with `swipeUp` first
    /// and did exactly that.
    private func scrollList(_ app: XCUIApplication) {
        let list = app.collectionViews.firstMatch
        list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
            .press(
                forDuration: 0.2,
                thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
            )
    }

    func testNativeButtonAttribution() {
        let app = launchNativeSample()
        app.buttons["native_plain_button"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: native_plain_button")
    }

    /// The #82 case, end to end: on a real SwiftUI `List` a full-screen `_UITouchPassthroughView`
    /// sits on top of the cells. The walk used to commit to it and never reach them, so every tap
    /// resolved to nothing. Still worth pinning after #189 moved resolution onto `hitTest`, since the
    /// row is reached out of a scroll-view-backed container either way.
    func testNativeListRowAttribution() {
        let app = launchNativeSample()
        app.buttons["native_row_2"].tap()
        XCTAssertEqual(lastEventLabel(app), "Last event target: native_row_2")
    }

    /// The #83 case, end to end, and the one no unit test could have caught: it needs two gestures in
    /// sequence. A scroll never becomes a tap, so it never reaches the action that clears the recorded
    /// touch-begin position — and the next real tap was then resolved against wherever the scroll went
    /// down, silently attributing to the wrong element.
    func testNativeTapAfterScrollAttributesToTheTappedRow() {
        let app = launchNativeSample()

        scrollList(app)

        let row = app.buttons
            .matching(NSPredicate(format: "identifier BEGINSWITH 'native_row_'"))
            .allElementsBoundByIndex
            .first { $0.isHittable }
        guard let row, !row.identifier.isEmpty else {
            return XCTFail("no row was hittable after scrolling")
        }
        let identifier = row.identifier
        row.tap()

        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: \(identifier)",
            "the tap was attributed to something other than the row it landed on — a begin position left behind by the scroll"
        )
    }

    /// Asserts that capture is still live, so a preceding "nothing was reported" assertion means the
    /// gesture was declined rather than that the pipeline was dead the whole time.
    ///
    /// Without this, both negative tests below pass on an app where `installNativeSampleCapture`
    /// never ran: the label simply stays at its initial value and every `XCTAssertEqual(label, before)`
    /// holds. Their Compose counterpart (`testIgnoredElementIsNotCaptured`) doesn't need the check
    /// because its baseline is already a value the pipeline produced.
    private func assertCaptureIsStillLive(_ app: XCUIApplication) {
        app.buttons["native_plain_button"].tap()
        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: native_plain_button",
            "capture reported nothing for a known-good tap either — the preceding assertion proved nothing"
        )
    }

    /// A scroll is not an interaction worth reporting. Measured on-device, a scroll delivers
    /// `touchesEnded` rather than `touchesCancelled`, so nothing but `UITapGestureRecognizer`
    /// declining to fire keeps it from being reported as a tap.
    func testNativeScrollAloneReportsNothing() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        scrollList(app)

        XCTAssertEqual(lastEventLabel(app), before)
        assertCaptureIsStillLive(app)
    }

    /// An element with no `accessibilityIdentifier` has no stable name to report. Identification must
    /// never fall back to `accessibilityLabel`, which is user-facing display text — that fallback is
    /// where the "never capture displayed text" guarantee would break.
    func testNativeButtonWithoutAnIdentifierIsNotReported() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        app.buttons["Unidentified"].tap()

        XCTAssertEqual(lastEventLabel(app), before)
        assertCaptureIsStillLive(app)
    }

    /// A disabled control runs no action, so reporting a click would invent an event (#134, the iOS
    /// counterpart of #128).
    ///
    /// The mechanism changed under this test with #189 and is worth stating, because the assertion
    /// looks identical either way: `hitTest` declines a disabled `UIControl` outright — measured, along
    /// with its whole subtree — so it never reaches the resolver, where before a trait veto dropped it.
    /// The touch passes through to whatever is drawn behind, which here is inert, so nothing is
    /// reported. That is the same observable outcome by a different route.
    func testNativeDisabledButtonIsNotReported() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        // A coordinate tap, not `.tap()`: XCUITest considers a disabled control non-hittable, while
        // UIKit still delivers the touch to it — which is the whole state under test.
        app.buttons["native_disabled_button"]
            .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .tap()

        XCTAssertEqual(lastEventLabel(app), before)
        assertCaptureIsStillLive(app)
    }

    /// **#191, end to end.** A SwiftUI `Button` carrying an `.accessibilityIdentifier` reports nothing.
    ///
    /// This is the one assertion in the suite that could only ever be made *here*. XCUITest is itself
    /// an accessibility client, so the runner warms the very tree the removed resolver needed — which
    /// is exactly why the old behaviour looked reliable in CI while dropping every SwiftUI tap on a
    /// user's device. Running warm is therefore not a weakness of this test, it is the point: warm is
    /// the condition under which the removed fallback *would* have fired, so a green assertion here is
    /// evidence the fallback is genuinely gone rather than merely dormant.
    func testNativeSwiftUIButtonIsNotReported() {
        let app = launchNativeSample()
        let before = lastEventLabel(app)

        app.buttons["native_swiftui_button"].tap()

        XCTAssertEqual(
            lastEventLabel(app),
            before,
            "a SwiftUI element has no per-element backing view, so native capture must name nothing"
        )
        assertCaptureIsStillLive(app)
    }
}

/// The SwiftUI native tap opt-out (`.autographIgnore()`), on-device, under real synthetic touches.
///
/// `AutographUITests` proves the shipped marker registers the right window rectangle and follows it
/// (in-process, fault-injected). This suite adds what only a real touch pipeline shows: an excluded
/// button's tap is dropped *and its own action still fires*, and the exclusion is real rather than a
/// never-capturable button (the baseline the first PR-B attempt lacked). Capture and registration both
/// route through `sample_shared`, so one `AutographIgnoredBounds` registry backs both.
final class IgnoreSampleUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(baseline: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-ignore-sample"] + (baseline ? ["-autograph-ignore-baseline"] : [])
        app.launch()
        return app
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["native_last_event_label"].label
    }

    private func ignoredTapCount(_ app: XCUIApplication) -> String {
        app.staticTexts["ignore_ignored_tap_count"].label
    }

    /// The shown button is never excluded, so it doubles as the live-capture control: a preceding
    /// "nothing was reported" only means something if a known-good tap right after *is* reported.
    private func assertCaptureIsStillLive(_ app: XCUIApplication) {
        app.buttons["ignore_shown_button"].tap()
        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: ignore_shown_button",
            "capture reported nothing for a known-good tap either — the preceding assertion proved nothing"
        )
    }

    /// The core claim: a tap on an `.autographIgnore()`'d button is not autocaptured, yet the button's
    /// own action still runs. The marker sits behind the content and declines touches, so it excludes the
    /// tap from capture without stealing it from the button.
    func testIgnoredButtonIsExcludedFromCaptureButStillFires() {
        let app = launch()
        let before = lastEventLabel(app)
        XCTAssertEqual(ignoredTapCount(app), "Ignored taps: 0")

        app.buttons["ignore_ignored_button"].tap()

        XCTAssertEqual(lastEventLabel(app), before, "an .autographIgnore()'d tap must not be autocaptured")
        XCTAssertEqual(ignoredTapCount(app), "Ignored taps: 1", "the wrapped button must still receive its own tap")
        assertCaptureIsStillLive(app)
    }

    /// Non-vacuity: the very same button, with no modifier, IS captured — so the exclusion above is the
    /// modifier's doing and not a button that was never reportable.
    func testWithoutTheModifierTheSameButtonIsCaptured() {
        let app = launch(baseline: true)

        app.buttons["ignore_ignored_button"].tap()

        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: ignore_ignored_button",
            "the button is capturable without .autographIgnore() — so the exclusion is real, not a never-capturable button"
        )
        XCTAssertEqual(ignoredTapCount(app), "Ignored taps: 1")
    }

    /// The exclusion is one button's rectangle, not the screen's: the adjacent, un-ignored button keeps
    /// reporting with the ignored region present.
    func testTheNeighbourKeepsReportingWithTheExclusionPresent() {
        let app = launch()

        app.buttons["ignore_shown_button"].tap()

        XCTAssertEqual(lastEventLabel(app), "Last event target: ignore_shown_button")
    }
}

/// The Compose/native boundary, on-device.
///
/// Both pipelines hit-test the same accessibility tree, so a tap on Compose content is visible to
/// both. `AutographComposeHosts` is what keeps the native side off it — and the registration that
/// populates it has to happen whether or not Compose autocapture is on, because the invariant is
/// *content under a Compose host belongs to the Compose pipeline exclusively*, not *content the
/// Compose pipeline reported*.
final class HybridBoundaryUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchHybridSample() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-hybrid-sample"]
        app.launch()
        return app
    }

    private func lastEventLabel(_ app: XCUIApplication) -> String {
        app.staticTexts["native_last_event_label"].label
    }

    /// The privacy case. Compose runs here with autocapture *off*, so it reports nothing; if the
    /// native pipeline is not held off the Compose subtree, it reports the tap instead — capturing
    /// content whose `autographIgnore()` exclusions live in Compose state it cannot see.
    ///
    /// The Compose button carries a `testTag`, so it reaches the bridged tree with an identifier and a
    /// button trait: it is something the native side genuinely *could* name. Without that the
    /// assertion would hold for the wrong reason.
    func testNativeCaptureDoesNotReportTapsOnComposeContent() {
        let app = launchHybridSample()
        let before = lastEventLabel(app)

        app.buttons["compose_button_in_hybrid"].tap()

        XCTAssertEqual(
            lastEventLabel(app),
            before,
            "the native pipeline reported a tap on Compose-owned content — the host boundary is not holding"
        )

        // Proves the native capture was alive for the assertion above, rather than never installed.
        app.buttons["native_button_in_hybrid"].tap()
        XCTAssertEqual(
            lastEventLabel(app),
            "Last event target: native_button_in_hybrid",
            "native capture reported nothing for a known-good tap either — the assertion above proved nothing"
        )
    }
}

/// Coverage for #65's **explicit** SwiftUI screen API (`.autographScreen`) on a real `NavigationStack`
/// (`SwiftUIScreensView` in `ContentView.swift`). The UIKit swizzle can't see SwiftUI screens, so they
/// name themselves; this drives the same Kotlin facade the shipped `AutographUI` modifier drives (via a
/// shape-identical sample modifier — see `AutographSampleScreen`) through real synthetic navigation.
///
/// SwiftUI delivers `onAppear`/`onDisappear` in an order unit tests can't see (measured: on push the
/// destination appears *before* the source disappears; on pop the parent re-appears), so this reads the
/// cumulative `name:previous_screen` log after real pushes and pops. The log is exact, so a screen that
/// wrongly named itself its own `previous_screen`, or a missed re-appearance, fails the test.
final class SwiftUIScreensUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-swiftui-screens"]
        app.launch()
        return app
    }

    private func waitForScreenLog(_ app: XCUIApplication, _ expected: String) {
        expectation(
            for: NSPredicate(format: "label == %@", "Screen views: \(expected)"),
            evaluatedWith: app.staticTexts["swiftui_screen_view_log_label"]
        )
        waitForExpectations(timeout: 5)
    }

    /// The first SwiftUI screen fires exactly one Screen Viewed on entry, with no previous_screen.
    func testFirstScreenFiresOnEntry() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
    }

    /// Pushing a destination reports it, carrying the screen it replaced as previous_screen — even
    /// though SwiftUI fires the destination's onAppear before the source's onDisappear.
    func testPushReportsSecondWithPrevious() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
        app.buttons["swiftui_go_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst")
    }

    /// Popping re-shows First as a fresh screen view whose previous_screen is Second.
    func testPopReReportsFirst() {
        let app = launch()
        app.buttons["swiftui_go_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst")
        app.buttons["swiftui_back_second"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUISecond:SwiftUIFirst|SwiftUIFirst:SwiftUISecond")
    }

    /// Returning to First from an *untracked* screen (one with no `.autographScreen`) must not name
    /// First its own previous_screen: nothing capturable was recorded in between, so `record` sees
    /// First as the last screen and the self-previous guard drops the previous to none. This is the
    /// SwiftUI mirror of `testReturnFromExcludedScreenHasNoSelfPrevious`.
    func testReturnFromUntrackedHasNoSelfPrevious() {
        let app = launch()
        waitForScreenLog(app, "SwiftUIFirst:(none)")
        app.buttons["swiftui_go_untracked"].tap()
        // The untracked screen reports nothing; returning brings First back with previous = none.
        app.buttons["swiftui_back_untracked"].tap()
        waitForScreenLog(app, "SwiftUIFirst:(none)|SwiftUIFirst:(none)")
    }
}

/// Coverage for **explicit SwiftUI instrumentation** — `AutographButton` and `autograph.track(_:)` —
/// driven through real synthetic touches (`SwiftUIExplicitSampleView` in `SwiftUIExplicitSample.swift`).
///
/// Unlike every other suite here, this one exercises the shipped `AutographUI` product **itself**: the
/// sample app links `Autograph.xcframework` through the local SwiftPM package for this screen. That is
/// the whole reason the suite exists. Three of its claims cannot be reached any other way:
///
/// - **A `.disabled` `AutographButton` records nothing.** SwiftUI runs no action for a disabled button
///   and the recording lives inside that action — but "the action did not run" is only observable by
///   actually touching a disabled button. Both designs this API replaced (`.simultaneousGesture`, an
///   `isEnabled` environment gate) recorded a phantom event here and looked fine in unit tests.
/// - **Recording precedes the caller's action.** `AutographButton` fixes the order by construction, and
///   the ordered log below is what shows it happening in that order under a real tap.
/// - **A touch actually fires it.** A unit test can only invoke the action directly; activating a
///   button through accessibility is unavailable headless (measured — such a test always skips, and a
///   skipping test grants false confidence, so none was written).
///
/// Scope attribution is asserted here too, though it is not exclusive to this level: the sibling-row
/// case is the shape that made the scope channel lexical rather than stack-resolved.
final class SwiftUIExplicitUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-swiftui-explicit"]
        app.launch()
        return app
    }

    private func trackLog(_ app: XCUIApplication) -> String {
        app.staticTexts["swiftui_explicit_track_log_label"].label
    }

    private func lastProps(_ app: XCUIApplication) -> String {
        app.staticTexts["swiftui_explicit_props_label"].label
    }

    /// Blocks until the last-event label names [target].
    ///
    /// The same split the Compose suite above uses, for the same reason: this waits on a *positive*
    /// claim (the tap produced this target), where arriving late and arriving are the same thing, and
    /// the ordered-log assertion that follows is read eagerly so a duplicate fails instead of being
    /// waited out.
    private func waitForLastEventTarget(
        _ app: XCUIApplication,
        _ target: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let expected = "Last event target: \(target)"
        let seen = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: app.staticTexts["swiftui_explicit_last_event_label"]
        )
        if XCTWaiter().wait(for: [seen], timeout: 15) != .completed {
            XCTFail(
                "expected \(expected), last saw \(app.staticTexts["swiftui_explicit_last_event_label"].label)",
                file: file,
                line: line
            )
        }
    }

    /// The recommended path records exactly one event, carrying its target.
    ///
    /// The log starts empty here — this screen fires no impressions — so the assertion states the whole
    /// history, and a second event from the same tap would fail it.
    func testAutographButtonRecordsExactlyOnce() {
        let app = launch()
        app.buttons["swiftui_plain_button"].tap()
        waitForLastEventTarget(app, "swiftui_plain_button")
        XCTAssertEqual(trackLog(app), "Tracks: Recipe Saved:swiftui_plain_button")
        // The button's own `properties:` argument, pinned nowhere else: this is the only
        // `AutographButton` in the app that fires carrying one, so dropping the argument inside
        // `AutographButton.body` is a regression that only this assertion can see.
        XCTAssertTrue(
            lastProps(app).contains("\"surface\":\"list\""),
            "AutographButton's call-site properties did not reach the event: \(lastProps(app))"
        )
    }

    /// **The invariant this API exists for.** A disabled `AutographButton` records nothing, because the
    /// recording is inside an action SwiftUI never runs.
    ///
    /// The assertion is the *exact* ordered log after a known-good tap on either side of the disabled
    /// one. A phantom event would land between them and break the match — which a bare "the label did
    /// not change" could miss, since it cannot distinguish an event that has not been appended yet.
    func testDisabledAutographButtonRecordsNothing() {
        let app = launch()

        // A known-good tap first, so the baseline is a value this wiring produced rather than the
        // initial label — otherwise a screen where nothing was wired up at all would pass.
        app.buttons["swiftui_plain_button"].tap()
        waitForLastEventTarget(app, "swiftui_plain_button")

        let disabled = app.buttons["swiftui_disabled_button"]
        // This test asserts an ABSENCE and its tap is a coordinate tap, which does not require
        // hittability — so a fixture pushed off screen would be tapped at a coordinate that is nowhere
        // and the absence would hold for the wrong reason.
        XCTAssertTrue(
            app.windows.firstMatch.frame.contains(disabled.frame),
            "swiftui_disabled_button must be fully on screen or this test passes vacuously — "
                + "its frame \(disabled.frame) is outside \(app.windows.firstMatch.frame)"
        )
        // A coordinate tap, not `.tap()`: XCUITest considers a disabled button non-hittable, while the
        // touch is still delivered — which is the whole state under test.
        disabled.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        // A second known-good tap. Besides proving the wiring is still live, it gives any phantom event
        // from the disabled tap time to land — and it would land *before* this entry, so the exact log
        // below fails on it.
        app.buttons["swiftui_plain_button"].tap()
        let expected = "Tracks: Recipe Saved:swiftui_plain_button|Recipe Saved:swiftui_plain_button"
        let settled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: app.staticTexts["swiftui_explicit_track_log_label"]
        )
        if XCTWaiter().wait(for: [settled], timeout: 15) != .completed {
            XCTFail("a disabled AutographButton recorded an event: \(trackLog(app))")
        }
    }

    /// The recording happens **before** the caller's action, which `AutographButton` fixes by
    /// construction. The button's action appends to the same ordered log the tracker writes to, so the
    /// order is two entries rather than a claim in a doc.
    func testRecordingPrecedesTheAction() {
        let app = launch()
        app.buttons["swiftui_order_button"].tap()
        waitForLastEventTarget(app, "swiftui_order_button")
        XCTAssertEqual(
            trackLog(app),
            "Tracks: Recipe Saved:swiftui_order_button|action:swiftui_order_button",
            "the action ran before the recording — a failure to record would then also lose the event"
        )
    }

    /// The escape hatch, on the `role:` initializer `AutographButton` deliberately does not mirror:
    /// `autograph.track` called from inside the button's own action, carrying call-site properties.
    func testInlineTrackRecordsFromTheButtonAction() {
        let app = launch()
        app.buttons["swiftui_inline_button"].tap()
        waitForLastEventTarget(app, "swiftui_inline_button")
        XCTAssertEqual(trackLog(app), "Tracks: Recipe Deleted:swiftui_inline_button")
        XCTAssertTrue(
            lastProps(app).contains("\"plan\":\"pro\""),
            "call-site properties missing: \(lastProps(app))"
        )
    }

    /// Sibling rows each carrying their own `.autographScope` attribute exactly — the shape that made
    /// the scope channel lexical. Resolved from the shared `ScopeStack` instead, both rows' scopes would
    /// be dropped: `resolveScope()` discards frames that are neither's ancestor, because an autocaptured
    /// tap carries no evidence of which sibling it hit. An explicit call site does.
    func testSiblingScopesAttributeToTheirOwnRow() {
        let app = launch()

        app.buttons["swiftui_row_1_button"].tap()
        waitForLastEventTarget(app, "swiftui_row_1_button")
        XCTAssertTrue(
            lastProps(app).contains("\"row_id\":\"row_1\""),
            "row 1's scope did not reach its event: \(lastProps(app))"
        )

        app.buttons["swiftui_row_2_button"].tap()
        waitForLastEventTarget(app, "swiftui_row_2_button")
        XCTAssertTrue(
            lastProps(app).contains("\"row_id\":\"row_2\""),
            "row 2 was attributed to the wrong sibling's scope: \(lastProps(app))"
        )
    }

    /// Nested scopes merge outer-then-inner: the inner wins the key they share, and the outer's other
    /// keys survive.
    func testNestedScopesMergeInnerOverOuter() {
        let app = launch()
        app.buttons["swiftui_nested_button"].tap()
        waitForLastEventTarget(app, "swiftui_nested_button")
        let props = lastProps(app)
        XCTAssertTrue(props.contains("\"row_id\":\"inner\""), "the inner scope should win row_id: \(props)")
        XCTAssertTrue(props.contains("\"extra\":\"kept\""), "the inner scope's other key was lost: \(props)")
        XCTAssertTrue(props.contains("\"route\":\"feed\""), "the outer scope's other key was lost: \(props)")
    }

    /// An explicit event carries the screen `.autographScreen` pushed onto the shared `ScopeStack` — the
    /// half of attribution that is still read dynamically, and the reason the element capture and the
    /// screen capture must be built on the *same* stack.
    func testExplicitEventsCarryTheScreen() {
        let app = launch()
        app.buttons["swiftui_plain_button"].tap()
        // Pin the props to this tap's own event before reading them.
        waitForLastEventTarget(app, "swiftui_plain_button")
        XCTAssertTrue(
            lastProps(app).contains("\"screen\":\"SwiftUIExplicit\""),
            "screen missing from props: \(lastProps(app))"
        )
    }
}

/// Coverage for #65's native **screen** capture — the `viewDidAppear:` swizzle — on a real UIKit
/// `UIViewController` hierarchy (`NativeScreensRootView` in `ContentView.swift`).
///
/// This is the surface that class of work has to reach: screen capture is lifecycle-driven, and every
/// lifecycle defect this library has shipped (a scroll's stale begin position, a passthrough overlay,
/// a screen frame that leaks) was invisible to unit tests and only surfaced under real synthetic
/// touches. Each test drives real pushes, presents and tab switches and reads the cumulative
/// `Screen Viewed` log the sample surfaces (a UI test cannot read Kotlin state).
///
/// The log is `name:previous_screen` entries joined by `|`. Because every assertion pins the *exact*
/// log, a container controller that wrongly reported itself (a `UINavigationController`, a
/// `UITabBarController`) would show up as an extra entry and fail the test — the exclusion of those is
/// checked here, not just asserted in a comment.
final class NativeScreensUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-autograph-native-screens"]
        app.launch()
        return app
    }

    /// Waits for the cumulative screen-view log to reach [expected]. The log updates from a
    /// `viewDidAppear:`-driven callback a beat after each transition, so this waits rather than reading
    /// eagerly — and, being an exact match, fails if a container controller added a spurious entry.
    ///
    /// `.firstMatch`, not a bare subscript: a modal presented `.overFullScreen` leaves its presenter in
    /// the accessibility tree, so two labels carry this identifier at once. Both show the same value
    /// (the sample updates every screen's labels on every event), so taking the first is correct — and
    /// a bare `app.staticTexts["id"]` would be an ambiguous query that never resolves in the predicate.
    private func screenLog(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(identifier: "native_screen_view_log_label").firstMatch
    }

    private func waitForScreenLog(_ app: XCUIApplication, _ expected: String) {
        expectation(
            for: NSPredicate(format: "label == %@", "Screen views: \(expected)"),
            evaluatedWith: screenLog(app)
        )
        waitForExpectations(timeout: 5)
    }

    private func lastTarget(_ app: XCUIApplication) -> String {
        app.staticTexts.matching(identifier: "native_last_event_label").firstMatch.label
    }

    private func lastProps(_ app: XCUIApplication) -> String {
        app.staticTexts.matching(identifier: "native_last_event_props_label").firstMatch.label
    }

    /// The first UIKit screen fires exactly one Screen Viewed on entry, with no previous_screen — and
    /// neither the hosting controller nor the navigation controller reports one alongside it.
    func testFirstScreenFiresOnEntry() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
    }

    /// Pushing a controller reports the new screen, carrying the one it replaced as previous_screen.
    /// One push produces exactly one new entry — the navigation container is not itself a screen.
    func testPushReportsTheNewScreenWithPrevious() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_push_second"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen")
    }

    /// Popping re-shows First as a fresh screen view whose previous_screen is Second. A re-appearance
    /// is a screen view; the dedup rule only skips a re-fire while the *same* controller's frame is
    /// still live (a cancelled interactive pop), which a completed pop is not.
    func testPopReReportsFirst() {
        let app = launch()
        app.buttons["native_push_second"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen")
        app.buttons["native_pop"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SecondScreen:FirstScreen|FirstScreen:SecondScreen")
    }

    /// A native tap on a UIKit screen carries the `screen` the swizzle pushed onto the shared stack —
    /// and, deliberately, no `section` (a `UIViewController` has no section; section is Compose-only).
    /// This is the assertion the removed `testNativeTapCarriesScreenAndSection` used to make against a
    /// hand-pushed fixture frame; it now rides a real swizzle-produced frame. The target is pinned
    /// first so the props are read off the tap's own event, not a stale one.
    func testNativeTapCarriesScreenAndNoSection() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_first_button"].tap()
        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")
        let props = lastProps(app)
        XCTAssertTrue(props.contains("\"screen\":\"FirstScreen\""), "screen missing from props: \(props)")
        XCTAssertFalse(props.contains("\"section\""), "a native screen must carry no section: \(props)")
    }

    /// A disabled **UIKit** control reports nothing (#134). The SwiftUI and Compose halves are pinned
    /// by the other two suites; this is the one that exercises `UIButton.isEnabled = false` itself.
    ///
    /// It has to live in an XCUITest rather than in `autograph-uikit`'s unit tests: measured, a real
    /// `UIButton` in the headless unit-test process reports `accessibilityTraits = 0` — no button
    /// trait, no `NotEnabled` — because UIKit populates them only once an accessibility client asks.
    /// An XCUITest runner is such a client, so the trait the veto reads exists only here (#135).
    func testNativeDisabledUIKitButtonIsNotReported() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        // A known-good tap first, so the baseline is a value this pipeline produced rather than the
        // initial label — otherwise a dead pipeline would satisfy the assertion below.
        app.buttons["native_first_button"].tap()
        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")

        // A coordinate tap, not `.tap()`: XCUITest considers a disabled control non-hittable, while
        // UIKit still delivers the touch to it — which is the whole state under test.
        app.buttons["native_first_disabled_button"]
            .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .tap()

        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")
    }

    /// A modal presented *over* its presenter (`.overFullScreen`) stacks on top and restores the
    /// presenter on dismiss. The presenter (`FirstScreen`) gets no `viewDidDisappear:` on present nor
    /// `viewDidAppear:` on dismiss, so its frame is never removed and never re-added: the screen-view
    /// log gains `SheetScreen:FirstScreen` but *not* a second `FirstScreen` on dismiss, and a tap after
    /// dismiss carries `FirstScreen` again because its frame was underneath the whole time. A single
    /// "current screen" slot could not do this — it would drop to nothing once the sheet closed.
    func testModalOverPresenterStacksAndRestoresIt() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")

        app.buttons["native_present_sheet"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|SheetScreen:FirstScreen")
        app.buttons["native_sheet_button"].tap()
        XCTAssertTrue(
            lastProps(app).contains("\"screen\":\"SheetScreen\""),
            "a tap on the sheet should carry screen=SheetScreen: \(lastProps(app))"
        )

        app.buttons["native_dismiss_sheet"].tap()
        // No new screen view on dismiss (the presenter got no viewDidAppear:); the log is unchanged.
        // FirstScreen is proven restored by a tap carrying its screen again.
        waitForScreenLog(app, "FirstScreen:(none)|SheetScreen:FirstScreen")
        app.buttons["native_first_button"].tap()
        XCTAssertEqual(lastTarget(app), "Last event target: native_first_button")
        XCTAssertTrue(
            lastProps(app).contains("\"screen\":\"FirstScreen\""),
            "after dismiss the presenter's frame should be current again: \(lastProps(app))"
        )
    }

    /// Returning to a screen from an *excluded* one (here a SwiftUI `UIHostingController` modal, which
    /// the capture skips) must not name the screen as its own `previous_screen`. FirstScreen disappears
    /// and reappears around the excluded modal with nothing capturable recorded in between, so
    /// `record` sees FirstScreen as the last screen — the re-entry emits FirstScreen again but with no
    /// previous, not `FirstScreen:FirstScreen`.
    func testReturnFromExcludedScreenHasNoSelfPrevious() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_present_excluded"].tap()
        // The excluded modal reports no screen view; the dismiss brings FirstScreen back as a fresh
        // view whose previous is unknown (none), never itself.
        app.buttons["native_dismiss_excluded"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|FirstScreen:(none)")
    }

    /// Switching tabs reports the newly selected content controller. The `UITabBarController` itself is
    /// a container and reports nothing; only `TabA` (shown first) and then `TabB` do.
    func testTabSwitchReportsTheSelectedTab() {
        let app = launch()
        waitForScreenLog(app, "FirstScreen:(none)")
        app.buttons["native_present_tabs"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|TabAScreen:FirstScreen")
        app.tabBars.buttons["TabB"].tap()
        waitForScreenLog(app, "FirstScreen:(none)|TabAScreen:FirstScreen|TabBScreen:TabAScreen")
    }
}
