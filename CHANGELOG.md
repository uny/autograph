# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. The stability each
artifact commits to before then is spelled out in [ADR 0001](docs/adr/0001-public-api-evolution.md);
the `context.instrumentation` envelope is already semver-stable (see the README).

## [Unreleased]

### Fixed

- Autocapture on iOS no longer double-reports a `Modifier.trackClick` / `Modifier.trackImpression`
  element that is **smaller than the minimum touch target** ([#151]). The explicit event fired and an
  `Element Clicked` fired alongside it, contradicting the README's "never double-reported" — Compose
  expands such an element's touch target, and the accessibility frame it publishes with it, to 48dp
  centred on the element, while the claim the modifier registers stays the unexpanded layout bounds,
  so the rect-equality match iOS uses in place of a readable semantics key stopped applying. Measured
  on device: a natural-height `Text` registered a 72px-tall claim and published a 144px-tall frame.
  Android was never affected (it reads the marker off the semantics ancestry, with no geometry
  involved). Nothing caught it because the sample's only explicitly instrumented element was a
  56dp box, above the threshold — the sample now carries both shapes, and the XCUITest suite asserts
  the ordered event-name log rather than the last target, which cannot tell a double report from a
  single one since both entries carry the same target. Known residual: a **scaled** element still
  double-reports, because Compose qualifies the touch target on the measured size while the claim
  carries the drawn rect (measured: `scale(0.5f)` publishes the expanded measured rect halved, which
  the drawn rect's own expansion does not match) ([#159]).

- Autocapture on iOS no longer drops a tap on an **uninstrumented clickable** that merely contains a
  small `Modifier.trackImpression` element ([#153]). Expanding a claim to the minimum touch target is
  not injective, so a sub-minimum instrumented element centred in a clickable exactly at the minimum
  expands onto that clickable's accessibility frame precisely — and the fix above then read the
  clickable as already-instrumented and suppressed an event nothing else reported. Measured on device:
  an inner frame of `(16, 636, 370x24)` expanding onto a host at `(16, 624, 370x48)`, to the point.
  `trackImpression` is what makes this reachable where a `trackClick` inner element cannot — it adds
  no `clickable`, so Compose leaves the tap with the enclosing element instead of routing it inward,
  which is the premise the previous entry's reassurance rested on. Claims now record which modifier
  registered them: a `trackClick` claim's element is clickable by construction and its expansion match
  is taken at face value, while a `trackImpression` claim's expansion match is honoured only when no
  accessibility descendant of the resolved clickable publishes that claim unexpanded — how Compose
  Multiplatform publishes such an element. The distinction is load-bearing in both directions, since
  Compose Multiplatform publishes child `Text`s as their own accessibility descendants even inside a
  merged clickable: applying the descendant check to click claims too would stop suppressing a
  sub-minimum `trackClick` container whose child exactly fills it, reopening the defect above. Android
  was never affected, on either count. Introduced by the fix above and never released. Known residual,
  unmeasured and older than either fix: a `trackImpression` element *coincident* with the clickable
  enclosing it matches without any expansion, so the descendant check never runs and that clickable's
  tap is still dropped ([#158]).

- `Package.swift` on `main` no longer names a stale binary target. CD's self-correcting checksum
  commit is pushed to `refs/tags/<tag>` only, so it landed on no branch and `main` still described
  v0.1.0's xcframework after three releases; CD now replays the same rewrite onto `main`, and this
  release backfills the v0.3.0 values by hand. Tagged consumers (`from: "…"`) were never affected —
  they resolve the manifest at the tag, where the values have always been correct.

### Documentation

- `Package.swift`'s comment above `releaseVersion`/`releaseChecksum` no longer describes a
  mechanism that was removed in [#34]. It told maintainers to bump both by hand before tagging and
  claimed CD "fails the release if it doesn't match" — that pre-verify design was replaced with the
  self-correcting one precisely because Kotlin/Native checksums can't be pre-computed, and #34
  updated `cd.yml`, `ci.yml` and the README but not this comment.

- [ADR 0001](docs/adr/0001-public-api-evolution.md) §4 now rests on a measurement instead of an
  admitted guess ([#104]). The mixed-version klib diamond — an app resolving a newer
  `autograph-core` than the `autograph-segment` klib in its graph was compiled against — was
  reproduced for all three change kinds the ADR permits, and all three link and run on
  Kotlin/Native, unchanged across a 2.2.20 → 2.4.10 compiler skew. So the §2 rules are trusted on
  Kotlin/Native as well as the JVM, and lockstep release is no longer load-bearing. **No Gradle
  version constraint is shipped**: the direction that does fail is a *downgrade*, which Gradle's
  highest-wins conflict resolution never produces on its own. Worth knowing for reading a crash
  report: version skew here surfaces as a runtime `IrLinkageError` at the first call, not as a
  build failure. Fixture and full results in `fixtures/klib-diamond/`, re-runnable in about a
  minute when Kotlin is bumped.

## [0.3.0] - 2026-08-02

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
- `Modifier.autocaptureScope(...)` attaches per-element properties to the autocaptured taps under one
  element — the case `AutographScope` cannot serve, where sibling scopes are mounted at once (a
  list's rows each carrying their own id). Read back off the tapped element's own ancestry, so it
  describes the element actually hit. **Android only**, and not a gap awaiting work on this side:
  Compose Multiplatform's iOS accessibility bridge carries no custom semantics, and nothing it does
  carry identifies the tapped element's scope soundly, so an iOS tap carries none. Instrument such
  elements explicitly with `Modifier.trackClick` there — see the modifier's kdoc for the full account
  and the tradeoffs ([#68]).
- `AutographLogger`: a caller-implemented sink for the library's own operational diagnostics — a
  delivery that failed, an event dropped for failing validation — installed via
  `AutographConfig.logger`, so a host can route them into Logcat / `os_log` / Timber or silence them
  entirely. These previously went to `println`, unsilenceable and invisible in production; the default
  logger still prints, so behaviour is unchanged unless one is set. A single `log(message)` line by
  design (ADR 0001 §2c): anything richer — a severity, a structured payload — arrives as a separate
  optional interface the core probes for, never as a new method here ([#56]).
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
  per-level prune (only the walk's starting node is exempt, see above), and measurement showed the
  *prune* cannot bite there: the bridged accessibility tree is flat, so an overhanging element is a
  *sibling* rather than a descendant and no parent frame excludes it ([#134]). The divergence itself
  does survive on iOS, by a different mechanism — the bridge does not emit overlapping siblings in
  z-order, so where the overlap is not one the bridge trims away, an overhanging element can lose the
  tap to the neighbour it covers. Measured for a corner overhang straight up ([#140]).
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
  now dropped — semantics cannot tell it from a real `clickable(enabled = false)`. iOS now behaves the
  same way — see the next entry.
- iOS autocapture no longer reports a tap on a disabled element as a click ([#134]), matching the
  Android behaviour above. Compose Multiplatform bridges `Modifier.clickable(enabled = false)` as
  `Button|NotEnabled`, and UIKit and SwiftUI mark a disabled control the same way, so such an element
  passed Autograph's clickability predicate and **an event was emitted for a click that never fired**
  — those taps now report nothing, on both the Compose and the native (UIKit/SwiftUI) pipeline. As on
  Android, the disabled element still *takes* the hit rather than being skipped during the walk,
  because it really does swallow the touch: measured on-device, tapping a disabled clickable nested in
  an enabled clickable parent fires nothing at all, so falling through would name an ancestor that
  never received the tap either — trading a phantom event for a misattributed one. The veto applies
  only to the element a tap resolves to, so a disabled *ancestor* still does not suppress an enabled
  clickable descendant. It costs the same shape the Android entry above describes, for the same
  reason: the trait is the element's own claim about itself, so a live handler behind a hand-published
  `NotEnabled` — or an app's own tap handler on a container *around* a disabled control, which a
  gesture recognizer does still see — is now dropped.
  This closes the third of the three divergences from Android that [#134] tracked, and with it that
  issue. The other two do **not** reproduce on the accessibility tree as measured (Compose
  Multiplatform 1.11.1, iOS 26.2): [#126]'s parent-bounds prune cannot arise, because the bridged
  tree is flat and an overhanging element is a *sibling* rather than a descendant, and [#127] needs
  no port, because the bridge already publishes the *expanded* minimum touch target as an element's
  `accessibilityFrame` (measured: a 16dp icon reports a 48pt frame, and so does a 40dp one).
  Measuring this one turned up a fourth divergence, distinct from all three and now tracked by
  [#140]: among *overlapping siblings* the walk resolves in reverse emitted order, which is not
  z-order — so a tap in the overhanging part of a clickable drawn over a neighbour can still be
  attributed to the neighbour.
- A blank autocapture identifier is treated as absent instead of being reported as an empty `target`
  ([#81]). `Modifier.testTag("")` short-circuited the identity chain, so the element reported
  `target = ""` — a blank name presented as if it were deliberate — and the role/label fallback never
  ran. A blank tag usually arrives from a template or a nil-coalesced binding (`testTag(id ?: "")`)
  rather than a real choice. **On Android, an element with a blank tag and a usable role now reports
  that role instead of an empty string, and one whose only identity is blank now emits no event at
  all.** Non-blank values still pass through byte for byte — rejected when blank, never trimmed. The
  check lives in `commonMain`, so it also removes the divergence created by dropping blank
  `accessibilityIdentifier`s on the iOS side only.
- iOS autocapture resolves taps correctly when the Compose root doesn't fill its window (coordinate
  space) ([#42]), reads `accessibilityIdentifier` off plain UIKit views ([#77]), backs out of
  empty passthrough overlays to reach the clickable beneath ([#82]), bounds the accessibility walk
  against cycles and pathological depth ([#74]), and no longer resolves a tap against a stale
  scroll-begin position ([#83]).

### Documentation

- **The iOS overlapping-sibling gap is documented per platform, at the precision measurement
  supports** ([#140]). Two earlier descriptions of it were wrong and are corrected here and in the
  kdoc. The bridged tree *does* carry a z-order signal: Compose Multiplatform trims a covered
  sibling's `accessibilityFrame` down to the part its neighbour does not cover, when what remains is
  still a rectangle — measured in three fixtures, one per trimmed edge — so the ambiguity is already
  gone before the tie-break for a full-width overlay and a horizontal overlap. Misattribution needs
  *both* an overlap the trim cannot express (measured for corner overhangs) *and* the element on top
  sorting earlier in the emitted order — which fit `(left, top)`, x-primary, across nine fixtures, and
  is measurably neither declaration order nor reading order. A badge overhanging to the top-right is
  spared by the second condition rather than the trim: its corner overlap is untrimmable, but the badge
  sits further right and so sorts later. On **native** — measured on SwiftUI only, no UIKit hierarchy
  run — no trim was observed in the one geometry Compose does trim, so that protection is absent there;
  reproduced end to end through the native resolver.
  Documented rather than fixed: there is no view hierarchy to recover draw order from (bridged
  elements are not view-backed, and the view subtree under the Compose host is four zero-framed
  containers over one Metal surface), and each ranking tried — last-emitted, smallest-area,
  first-emitted — was measured wrong against the oracle, the one in use included.
- An aggregated Dokka API reference across the published modules is built and deployed to GitHub
  Pages on every push to `main`: <https://uny.github.io/autograph/> ([#58]).
- The supported target matrix is stated outright, with the reasoning behind each omission ([#57]).
  iOS ships `iosArm64` and `iosSimulatorArm64`; the Intel-Mac simulator target `iosX64` is
  deliberately not published, because Apple-silicon simulators cover current development and every
  added target costs a Kotlin/Native link on each CI run — open an issue if you need it. Compose Web
  / `wasmJs` is out of scope by design rather than oversight: a browser target has no filesystem for
  the sequence/session store and needs a different transport story, which makes it a design task, not
  a target flag to flip.
- Named the two boundaries of the sequence-uniqueness guarantee — the single-process assumption and
  the corrupt-file reset ([#54]).
- A per-surface capture matrix (Compose/native × Android/iOS/desktop) and the dual-framework
  fail-open warning ([#102]); the iOS consumption model and the pure-Swift epic ([#94]).
- **iOS native tap capture is documented as best-effort**, because it reports nothing until some
  accessibility client has run in the process ([#135]). UIKit and SwiftUI build the accessibility element
  tree only when asked for it — by VoiceOver, Voice Control, the Accessibility Inspector, an XCUITest
  runner. Measured on a freshly created simulator: the walk finds only plain `UIView`s, every one
  reporting an empty `accessibilityFrame` and no traits, with no button trait anywhere in the tree, so
  **every native tap is dropped silently for the life of the process**; once any client connects, the
  targets this pipeline otherwise supports resolve again (its pre-existing gaps still drop either way). No public API asks UIKit to populate that tree, so this is a property of the mechanism
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

[Unreleased]: https://github.com/uny/autograph/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/uny/autograph/compare/v0.2.0...v0.3.0
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
[#34]: https://github.com/uny/autograph/issues/34
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
[#56]: https://github.com/uny/autograph/issues/56
[#57]: https://github.com/uny/autograph/issues/57
[#58]: https://github.com/uny/autograph/issues/58
[#60]: https://github.com/uny/autograph/issues/60
[#62]: https://github.com/uny/autograph/issues/62
[#64]: https://github.com/uny/autograph/issues/64
[#65]: https://github.com/uny/autograph/issues/65
[#67]: https://github.com/uny/autograph/issues/67
[#68]: https://github.com/uny/autograph/issues/68
[#74]: https://github.com/uny/autograph/issues/74
[#76]: https://github.com/uny/autograph/issues/76
[#77]: https://github.com/uny/autograph/issues/77
[#81]: https://github.com/uny/autograph/issues/81
[#82]: https://github.com/uny/autograph/issues/82
[#83]: https://github.com/uny/autograph/issues/83
[#86]: https://github.com/uny/autograph/issues/86
[#91]: https://github.com/uny/autograph/issues/91
[#94]: https://github.com/uny/autograph/issues/94
[#102]: https://github.com/uny/autograph/issues/102
[#104]: https://github.com/uny/autograph/issues/104
[#122]: https://github.com/uny/autograph/pull/122
[#126]: https://github.com/uny/autograph/issues/126
[#127]: https://github.com/uny/autograph/issues/127
[#128]: https://github.com/uny/autograph/issues/128
[#134]: https://github.com/uny/autograph/issues/134
[#135]: https://github.com/uny/autograph/issues/135
[#140]: https://github.com/uny/autograph/issues/140
[#151]: https://github.com/uny/autograph/issues/151
[#153]: https://github.com/uny/autograph/issues/153
[#158]: https://github.com/uny/autograph/issues/158
[#159]: https://github.com/uny/autograph/issues/159
