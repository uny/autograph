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
  `autograph-compose` must now compile against Android SDK 37 or later ([#120]).

### Fixed

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
[#120]: https://github.com/uny/autograph/pull/120
