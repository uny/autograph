# Autograph

[![CI](https://github.com/uny/autograph/actions/workflows/ci.yml/badge.svg)](https://github.com/uny/autograph/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.ynagai.autograph/autograph-core)](https://central.sonatype.com/namespace/dev.ynagai.autograph)

> Your app signs its own story.

**Autograph** is an analytics *instrumentation* layer for Kotlin Multiplatform and
Compose Multiplatform. It makes your app write its own analytics — automatically
tracked screens, impressions, and clicks, with a verifiable envelope
stamped onto every event:

- **`event_id`** — time-ordered [UUIDv7](https://www.rfc-editor.org/rfc/rfc9562) by
  default (pluggable), reused as the transport's message id for deduplication.
- **`seq`** — a per-session and/or device-lifetime sequence number. Gaps reveal event
  loss; the sequence restores in-session ordering without trusting client timestamps.
- **`session_id` / `session_start`** — timeout-based sessions that survive process
  restarts.
- **`event_timestamp`** — captured the moment `track`/`screen`/`identify` is called,
  independent of the transport's own event-time field (which can lag behind by however
  long it batches/enqueues before sending).

The on-disk sequence/session store is written via a write-tmp-then-atomic-rename, so a
process crash mid-write can never leave a corrupt or partially-written file. It does not
`fsync`, though, so on a hard power loss the last write may not have reached the disk
platter yet — that's a gap in the guarantee, not in the atomicity.

Autograph deliberately owns **no transport**: queueing, batching, and retries stay in
the battle-tested SDK underneath. The first adapter targets
[Segment](https://segment.com) (`analytics-kotlin` / `analytics-swift`); the transport
SPI is vendor-neutral.

> **Status: early development.** APIs are unstable. Artifacts are published to Maven
> Central (group `dev.ynagai.autograph`) starting with `v0.1.0`.

## Modules

| Module | What it does |
|:--|:--|
| `autograph-core` | `Tracker` facade, envelope stamping (id / seq / session), transport SPI. Zero UI dependencies. |
| `autograph-segment` | Segment adapter. Android: wraps `analytics-kotlin`, stamping inside the pipeline (a `Before` plugin) so even SDK-generated lifecycle events carry the envelope. iOS: bridge interface for `analytics-swift`, implemented by the `autograph-segment-swift` reference adapter (see below). |
| `autograph-compose` | Compose Multiplatform instrumentation: `AutographProvider`, `TrackScreenView` / `TrackedScreen`, automatic screen tracking for navigation-compose, and `Modifier.trackImpression` / `Modifier.trackClick`. |

## Quick start

```kotlin
// Android — build on your existing Segment client
val analytics = Analytics("WRITE_KEY", context)
val tracker = Autograph {
    transport(SegmentTransport(analytics))
    eventId = EventId.UuidV7            // or UuidV4, or your own generator
    sequence = SequenceMode.PerSession  // or PerDevice / Both / None
}
```

```swift
// iOS — add `.package(url: "https://github.com/uny/autograph.git", from: "0.1.0")` as a SwiftPM
// dependency (AutographSegmentSwift product), then:
let analytics = Analytics(configuration: Configuration(writeKey: "WRITE_KEY"))
let bridge = AutographSegmentBridge(analytics: analytics)
// Pass `bridge` to Kotlin's SegmentTransport(bridge) when constructing your Autograph tracker.
```

```kotlin
// Compose (common code)
AutographProvider(tracker) {
    App()
}

// Screens track themselves
navController.TrackScreenViews()

// ...or per screen
TrackedScreen("RecipeDetail") { RecipeDetailContent() }

// Custom events, anywhere in the composition
LocalTracker.current.track("Recipe Saved")

// target identifies which element triggered the event — a stable, library-managed
// properties["target"] key rather than an ad-hoc one every app names differently
LocalTracker.current.track("Recipe Saved", target = "share_button")

// Impressions and clicks — screen/section from the ambient ScreenContext (TrackedScreen)
// are attached automatically
Text("Save", Modifier.trackClick("Recipe Saved") { save() })
Card(Modifier.trackImpression("Recipe Viewed", target = "recipe_card")) { RecipeCard() }
```

Every event now carries:

```jsonc
"context": {
  "instrumentation": {
    "event_id": "0197c9a1-…",   // UUIDv7, also used as messageId
    "session_id": "0197c99f-…",
    "session_start": 1783585920000,
    "seq": 42,                   // gap ⇒ an event was lost
    "sdk": "autograph/0.1.0",
    "event_timestamp": "2026-07-11T09:12:03.456Z", // captured at call time, not the transport's own
    "schema_version": "2024-01" // your own tracking-plan version, if set — omitted otherwise
  }
}
```

## Validation

Enforce your own tracking-plan contract — required properties, allowed event names, naming
conventions — by plugging an `EventValidator` into `Autograph { }`. Autograph ships no rules of
its own; you write the check, Autograph enforces where it applies:

```kotlin
val tracker = Autograph {
    transport(SegmentTransport(analytics))
    validator = EventValidator { name, properties ->
        if (name !in knownEventNames) "unknown event name" else null
    }
    strictValidation = !BuildConfig.RELEASE // throw in debug, drop + log in release
}
```

`validate` returns null for a valid event, or a reason otherwise. `strictValidation` decides what
happens next: `true` throws immediately (fail fast during development), `false` drops the event
and logs the reason (never crash in production) — the same validator works in both modes. Applies
to `track`/`screen`; `identify` is unaffected, since it carries no event name to validate.

## Debugging

`DebugTransport` wraps another transport and logs every outgoing event before delivering it — for
eyeballing events on a real device/build during manual QA, separate from the app's production
transport (and separate from the planned `autograph-test` module's unit-test assertions):

```kotlin
val tracker = Autograph {
    transport(DebugTransport(SegmentTransport(analytics)))
}
```

The default logger dumps full event properties — don't wrap a production transport with this in a
release build (gate it behind a debug-build check, or supply a logger that redacts what it prints).

## iOS: `AutographSegmentSwift`

`SegmentBridge` (the interface `SegmentTransport` calls on iOS) is exported from Kotlin as an
Objective-C protocol via `AutographSegment.xcframework`. The `AutographSegmentSwift` product (this
repo's root `Package.swift`) is the reference adapter implementing it against `analytics-swift` —
`analytics-swift`'s event model is pure-Swift structs/generics with no Objective-C-visible surface
for the stamping this library needs, so this adapter exists as plain Swift rather than something
Kotlin/Native could call into directly.

`Package.swift` lives at the repository root (not a subdirectory) specifically so external apps
can add it the normal way, `.package(url: "https://github.com/uny/autograph.git", from: "…")` —
SwiftPM only resolves a URL-based dependency's manifest from the repo root.

Its `AutographSegment` binary target picks one of two sources depending on what's on disk:
- **Monorepo/local dev**: if `autograph-segment/build/XCFrameworks/release/AutographSegment.xcframework`
  exists (built via `./gradlew :autograph-segment:assembleAutographSegmentReleaseXCFramework`), it's
  used directly — so this always reflects whatever the Kotlin side currently builds, uncommitted
  changes included.
- **External consumers**: otherwise, falls back to a checksummed download from that version's
  GitHub Release asset (`AutographSegment.xcframework.zip`).

## Requirements

- Kotlin **2.4.0** (UUIDv7 generation comes from the standard library)
- Compose Multiplatform **1.11.1** (`Modifier.trackImpression` uses its stable
  `Modifier.onVisibilityChanged`)
- Targets: Android, iOS, JVM. Web (Wasm) is planned.

## Roadmap

- [x] `Modifier.trackImpression` / `Modifier.trackClick` built on Compose visibility APIs
- [ ] Navigation 3 `NavEntryDecorator` for automatic screen tracking
- [ ] `autograph-test`: in-memory transport with assertion helpers
- [x] `autograph-segment-swift` companion package (SPM)
- [ ] More transport adapters (PostHog, Amplitude, Firebase)

## Releasing

Publishing to Maven Central (group `dev.ynagai.autograph`) is configured via the
[vanniktech maven-publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin),
mirroring [`firebase-kotlin-sdk`](https://github.com/uny/firebase-kotlin-sdk)'s setup. Pushing a
`vX.Y.Z` tag triggers `.github/workflows/cd.yml`, which runs `publishToMavenCentral` with automatic
release. This requires the `release` GitHub Environment to have `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `GPG_KEY_ID`, `GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE` secrets configured
— a one-time, manual setup outside of what this repo's automation should do on its own.

`AutographSegmentSwift`'s binary target checksum can't be known ahead of time: Kotlin/Native's
build output isn't reproducible across separate builds (confirmed — rebuilding the same source on
the same machine changes the xcframework zip's checksum), so there's no value to pre-compute and
commit before tagging. Instead, `cd.yml` builds the xcframework once, computes its checksum, and
self-corrects: it updates `Package.swift`'s `releaseVersion`/`releaseChecksum` to match, commits
that fix, and moves the release tag to point at the new commit before uploading that same zip to
the GitHub Release — so the committed checksum and the uploaded artifact can never disagree. This
means pushing a `vX.Y.Z` tag almost always results in that tag pointing one commit further than
where it was originally pushed; that's expected, not a sign anything went wrong.

## License

```text
Copyright 2026 Yuki Nagai

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
