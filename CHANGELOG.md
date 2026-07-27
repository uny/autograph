# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. The stability each
artifact commits to before then is spelled out in [ADR 0001](docs/adr/0001-public-api-evolution.md);
the `context.instrumentation` envelope is already semver-stable (see the README).

## [Unreleased]

### Added

- Native (non-Compose) autocapture for hybrid apps: native iOS tap capture through a window-level
  gesture recognizer and the UIKit accessibility-tree resolver ([#62]), screen-view capture on iOS
  (UIKit `viewDidAppear` swizzle) and Android (`Activity`/`Fragment` lifecycle) ([#65]), and the
  explicit SwiftUI `.autographScreen("Name")` API ([#65]).
- Native tap opt-out markers: `registerAutographIgnoredView` / `registerAutographIgnoredBounds`
  (UIKit) and SwiftUI `.autographIgnore()` ([#86]).
- `AutographScope` for screen-scoped event context, mirrored into a framework-agnostic ambient
  scope/screen-context stack (`autograph-context`) that native surfaces can push into too, so a hybrid
  app can share one `ScopeStack` and keep a continuous `previous_screen` chain across Compose and
  native ([#49], [#64], [#76]).
- `TrackedScreen` can name a `section`, so autocaptured taps carry one ([#67]).
- New modules: `autograph-context` (the ambient stack), `autograph-uikit` (the iOS accessibility-tree
  hit-test), and `autograph-android` (native Android screen capture).
- [ADR 0001](docs/adr/0001-public-api-evolution.md): how each public type may evolve after 1.0, with a
  `CONTRIBUTING` section and a README pointer ([#53]).

### Changed

- `Tracker.close()` now drains already-enqueued events (stamp + hand to transport + flush, bounded by
  an internal timeout) instead of cancelling and silently dropping them ([#52]).
- Construction-time disk I/O is deferred off the caller thread: building a tracker no longer reads or
  writes the sequence/session store synchronously, which could trip StrictMode on the main thread
  ([#55]).
- `Envelope` and `SessionInfo` construction is frozen (`@ConsistentCopyVisibility` + `internal
  constructor`) so envelope fields can be added without an ABI break; obtain envelopes via
  `EnvelopeSource.stamp()`, and use `autograph-test` to build one in tests ([#53]).
- The iOS XCFramework is emitted as a single `Autograph` umbrella (renamed from `AutographSegment`)
  ([#91]).
- `event_timestamp` is captured on the caller's thread at call time, and `sdk` carries the real build
  version instead of a hardcoded literal ([#60]).
- Android `compileSdk` raised to 37, required by the `androidx.lifecycle` 2.11.0 bump; consumers of
  `autograph-compose` must now compile against Android SDK 37 or later ([#122]).

### Fixed

- Compose autocapture on iOS resolves taps on a device where nothing has connected to the accessibility
  subsystem ([#135]). The UIKit accessibility-tree walk tested containment against its own *starting*
  node before looking at any child, and in that cold state Compose Multiplatform's `OverlayInputView` —
  the view the Compose resolver starts from — reports an empty `accessibilityFrame`, while every bridged
  element beneath it already carries a correct frame. So the walk gave up immediately and **every**
  Compose tap resolved to nothing, for the life of the process. Containment now gates the descent at
  every node *except* the starting one, which is the caller's choice of where to search rather than a
  candidate to filter on. **Compose taps that this dropped now report an event**, naming the element
  whose handler actually fired; taps dropped for any of the other documented reasons (no `testTag`, no
  button trait, a disabled element, an intermediate container that misses the tap, an exhausted walk
  budget) still drop. No tap that already reported an event changes which element it names.
  Misattribution is guarded on both sides: a starting node that does not contain the tap is never
  reported on its own, and one that is itself clickable keeps its gate entirely, since it would
  otherwise be attributed the tap whenever an inert child contained it. Anything that connects to the
  accessibility subsystem — XCUITest, VoiceOver, the Accessibility Inspector — populates that frame and
  hides the failure completely, which is why the `sample-ios` XCUITest suite passed throughout: its
  runner is itself such a client. The contract is therefore pinned by `autograph-uikit`'s unit tests,
  which drive the walk directly. This does not fix the native (UIKit/SwiftUI) pipeline, whose own
  cold-start failure has a deeper cause — see the known limitation below ([#135]).
- Android autocapture attributes a tap to the element Compose actually routed the pointer to when a
  `clickable` is drawn outside its parent's bounds — an overhanging badge, a `Modifier.offset`
  decoration ([#126]). The hit test no longer refuses to descend past a parent whose bounds miss the
  tap; instead an element must contain the tap *itself* to be reported. Such taps were previously
  dropped, and, where the overhanging element was itself clickable, attributed to whichever element
  it happened to cover — so **some taps that reported nothing now report an event, and a few report
  a different `target` than before.** A non-clickable decoration overhanging a neighbouring row
  still cannot take that row's tap, which is the behaviour the prune existed to protect. iOS keeps its
  per-level prune (only the walk's starting node is exempt, see above), but measurement showed the
  divergence this note predicted does not arise for Compose content: the bridged accessibility tree is
  flat, so an overhanging element is a *sibling* rather than a descendant and no parent frame excludes
  it ([#134]).
- Android autocapture attributes a tap in a small element's expanded touch target to that element
  ([#127]). Compose grows the touch target of anything measured below
  `ViewConfiguration.minimumTouchTargetSize` (48dp by default) past its drawn bounds, so a tap a few
  pixels outside a 16dp icon button fires the icon — while autocapture reported whatever was drawn
  underneath, naming the wrong element in the `target` and in any scope read off it. Small icon
  buttons inside a larger clickable surface (a row's trailing overflow icon, a card's favourite
  toggle) are the common shape, so **taps near such an element now report a different `target` than
  before, and any scope that differs between the two elements changes with it.** The hit test now
  ranks candidates by the rules observed from Compose's own: a hit inside an element's real bounds
  outranks an expanded-target hit in another branch whatever the stacking order, competing expanded
  targets go to the nearest, and a descendant is still reported ahead of its ancestor. An element
  already at the minimum on both axes is never reached outside its own bounds. The [#128] veto covers
  the expanded margin too, so a disabled element still reports nothing out there. Unchanged: an
  element is hit-tested as an axis-aligned rectangle, so a rotated element or a non-rectangular
  `graphicsLayer` clip still takes taps in the corners of its bounding box that Compose routes
  underneath. An element that is both scaled and ancestor-clipped can newly report a tap Compose
  routed nowhere, or drop one it routed to that element; neither was observed to misattribute a tap
  to a different element. iOS needs no counterpart: measurement showed Compose Multiplatform already
  publishes the *expanded* touch target as an element's `accessibilityFrame` (a 16dp icon reports a
  48pt frame centred on its drawn bounds), so a tap in that margin already resolved to the small
  element there ([#134]).
- Android autocapture no longer reports a tap on a disabled element as a click ([#128]).
  `Modifier.clickable(enabled = false)` publishes the click action alongside `Disabled`, so such an
  element was picked as the tap's target and **an event was emitted for a click that never fired** —
  those taps now report nothing. It still takes the hit rather than being skipped, because it really
  does consume the pointer: falling through would name the element underneath, which never received
  the tap either (measured, including that a disabled child blocks its own enabled clickable
  ancestor). A disabled *ancestor* does not suppress an enabled clickable child. One shape this
  costs: a live `Modifier.clickable` whose semantics were hand-marked `disabled()` does fire and is
  now dropped — semantics cannot tell it from a real `clickable(enabled = false)`. iOS is unchanged and
  still reports such a tap: measurement confirmed the bridge does carry `UIAccessibilityTraitNotEnabled`
  on a disabled element, so the same veto is implementable there, and it is tracked as the remaining
  half of ([#134]).
- iOS autocapture resolves taps correctly when the Compose root doesn't fill its window (coordinate
  space) ([#42]), reads `accessibilityIdentifier` off plain UIKit views ([#77]), backs out of
  empty passthrough overlays to reach the clickable beneath ([#82]), bounds the accessibility walk
  against cycles and pathological depth ([#74]), and no longer resolves a tap against a stale
  scroll-begin position ([#83]).

### Documentation

- Named the two boundaries of the sequence-uniqueness guarantee — the single-process assumption and
  the corrupt-file reset ([#54]).
- A per-surface capture matrix (Compose/native × Android/iOS/desktop) and the dual-framework
  fail-open warning ([#102]); the iOS consumption model and the pure-Swift epic ([#94]).
- **iOS native tap capture is documented as best-effort**, because it reports nothing until some
  accessibility client has run in the process ([#135]). UIKit and SwiftUI build the accessibility element
  tree only when asked for it — by VoiceOver, Voice Control, the Accessibility Inspector, an XCUITest
  runner. Measured on a freshly created simulator: the walk finds only plain `UIView`s, every one
  reporting an empty `accessibilityFrame` and no traits, with no button trait anywhere in the tree, so
  **every native tap is dropped silently for the life of the process**; once any client connects, every
  tap resolves. No public API asks UIKit to populate that tree, so this is a property of the mechanism
  rather than a defect this library can fix, and any tap capture built on the accessibility tree inherits
  it. Anything that must not be lost needs explicit instrumentation. The capture matrix now marks the
  surface conditional, and `installAutographNativeTapCapture` / `resolveNativeTapTarget` say so on the
  way in. Compose autocapture on iOS is unaffected: Compose Multiplatform bridges its own semantics into
  accessibility elements unconditionally. This also corrects the note added with the fix above, which had
  attributed the native failure to a valid-framed `UIWindow` — measured false, the window reports
  `CGRectZero` when cold as well.

## [0.2.0] - 2026-07-13

### Added

- Opt-in autocapture of taps on Compose surfaces — Android ([#36]) and iOS via the UIKit
  accessibility bridge ([#37]).
- `autograph-test`: an in-memory transport and `assert*` helpers for unit-testing instrumentation
  ([#47]).
- `autograph-schema`: typed `Tracker.track<EventName>(...)` functions generated from a JSON Schema
  tracking plan ([#48]).
- Runnable Android ([#39]) and iOS ([#40]) sample apps, with an iOS XCUITest suite for the
  autocapture resolver ([#41]).

### Changed

- Committed to semver stability for the envelope fields ahead of the rest of the API ([#46]).

## [0.1.1] - 2026-07-12

### Added

- OSS community-health files, and a refreshed publish status ([#35]).

## [0.1.0] - 2026-07-11

Initial release.

### Added

- `autograph-core`: the `Tracker` facade and envelope stamping — time-ordered `event_id` (UUIDv7),
  per-session / device-lifetime `seq`, timeout-based sessions (`session_id` / `session_start`) that
  survive process restarts, `event_timestamp` captured at call time ([#20]), and a configurable
  `schema_version` ([#19]). Sequence/session state persists to an atomically-written file.
- `autograph-segment`: the Segment adapter (Android `analytics-kotlin` in-pipeline stamping; an iOS
  bridge for `analytics-swift`), plus the `autograph-segment-swift` SwiftPM companion ([#30]).
- `autograph-compose`: `AutographProvider`, screen tracking, and `Modifier.trackImpression` /
  `Modifier.trackClick` ([#25]).
- `Tracker.close()` ([#18]), a `target` parameter on `Tracker.track` for element identification
  ([#22]), pluggable `EventValidator` ([#24]), and `DebugTransport` for on-device event inspection
  ([#27]).
- Maven Central publishing ([#31]).

[Unreleased]: https://github.com/uny/autograph/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/uny/autograph/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/uny/autograph/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/uny/autograph/releases/tag/v0.1.0

[#18]: https://github.com/uny/autograph/issues/18
[#19]: https://github.com/uny/autograph/issues/19
[#20]: https://github.com/uny/autograph/issues/20
[#22]: https://github.com/uny/autograph/issues/22
[#24]: https://github.com/uny/autograph/issues/24
[#25]: https://github.com/uny/autograph/issues/25
[#27]: https://github.com/uny/autograph/issues/27
[#30]: https://github.com/uny/autograph/issues/30
[#31]: https://github.com/uny/autograph/issues/31
[#35]: https://github.com/uny/autograph/issues/35
[#36]: https://github.com/uny/autograph/issues/36
[#37]: https://github.com/uny/autograph/issues/37
[#39]: https://github.com/uny/autograph/issues/39
[#40]: https://github.com/uny/autograph/issues/40
[#41]: https://github.com/uny/autograph/issues/41
[#42]: https://github.com/uny/autograph/issues/42
[#46]: https://github.com/uny/autograph/issues/46
[#47]: https://github.com/uny/autograph/issues/47
[#48]: https://github.com/uny/autograph/issues/48
[#49]: https://github.com/uny/autograph/issues/49
[#52]: https://github.com/uny/autograph/issues/52
[#53]: https://github.com/uny/autograph/issues/53
[#54]: https://github.com/uny/autograph/issues/54
[#55]: https://github.com/uny/autograph/issues/55
[#60]: https://github.com/uny/autograph/issues/60
[#62]: https://github.com/uny/autograph/issues/62
[#64]: https://github.com/uny/autograph/issues/64
[#65]: https://github.com/uny/autograph/issues/65
[#67]: https://github.com/uny/autograph/issues/67
[#74]: https://github.com/uny/autograph/issues/74
[#76]: https://github.com/uny/autograph/issues/76
[#77]: https://github.com/uny/autograph/issues/77
[#82]: https://github.com/uny/autograph/issues/82
[#83]: https://github.com/uny/autograph/issues/83
[#86]: https://github.com/uny/autograph/issues/86
[#91]: https://github.com/uny/autograph/issues/91
[#94]: https://github.com/uny/autograph/issues/94
[#102]: https://github.com/uny/autograph/issues/102
[#122]: https://github.com/uny/autograph/pull/122
[#126]: https://github.com/uny/autograph/issues/126
[#127]: https://github.com/uny/autograph/issues/127
[#128]: https://github.com/uny/autograph/issues/128
[#130]: https://github.com/uny/autograph/issues/130
[#132]: https://github.com/uny/autograph/issues/132
[#134]: https://github.com/uny/autograph/issues/134
[#135]: https://github.com/uny/autograph/issues/135
