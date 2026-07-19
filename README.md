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
>
> **Exception — the envelope is stable now.** The fields stamped under
> `context.instrumentation` (or wherever a given transport places it) — `event_id`, `seq`,
> `global_seq`, `session_id`, `session_start`, `sdk`, `event_timestamp`, `schema_version` —
> and that object's top-level shape follow semver: no renames or type changes without a
> major version bump. This is the part of Autograph that gets persisted into a downstream
> analytics pipeline, so it's safe to build dashboards, data-quality checks, and schema
> migrations on top of today — everything else (Compose APIs, autocapture config, validator
> shape, transport adapters) remains unstable under the
> banner above.

## Modules

| Module | What it does |
|:--|:--|
| `autograph-core` | `Tracker` facade, envelope stamping (id / seq / session), transport SPI. Zero UI dependencies. |
| `autograph-segment` | Segment adapter. Android: wraps `analytics-kotlin`, stamping inside the pipeline (a `Before` plugin) so even SDK-generated lifecycle events carry the envelope. iOS: bridge interface for `analytics-swift`, implemented by the `autograph-segment-swift` reference adapter (see below). |
| `autograph-compose` | Compose Multiplatform instrumentation: `AutographProvider`, `TrackScreenView` / `TrackedScreen`, automatic screen tracking for navigation-compose, `Modifier.trackImpression` / `Modifier.trackClick`, `AutographScope` for screen-scoped event context, and opt-in autocapture of taps (Android and iOS). |
| `autograph-context` | The ambient scope / screen-context stack that autocapture reads at tap time. Framework-agnostic (no Compose dependency), so native UIKit / SwiftUI / Android View surfaces can push context too. `autograph-compose` mirrors `AutographScope` and `TrackedScreen` into it for you — you only touch this module directly when instrumenting a non-Compose surface. |
| `autograph-uikit` | iOS-only. The UIKit accessibility-tree hit-test that maps a tap position to an element — the one mechanism that identifies tapped elements across UIKit, SwiftUI, and Compose Multiplatform alike. Today it is an implementation detail shared by `autograph-compose`'s iOS resolver (everything in it is marked `@AutographInternalApi` and you should not depend on it directly); it becomes user-facing when native iOS tap capture lands. |
| `autograph-test` | `InMemoryTestTransport` and `assert*` helpers for unit-testing your own instrumentation, with no real transport or network involved (see [Testing](#testing) below). |
| `autograph-schema` | Generates typed `Tracker.track<EventName>(...)` extension functions from a JSON Schema tracking-plan document, as a compile-time alternative to `EventValidator` (see [Typed event schemas](#typed-event-schemas) below). |

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

// Scope a property onto every event fired below — e.g. the id from an articles/{article_id}
// route. trackClick/trackImpression, screen views, and plain track() calls all pick it up.
AutographScope("article_id" to articleId) { ArticleScreen() }

// Custom events, anywhere in the composition
LocalTracker.current.track("Recipe Saved")

// target identifies which element triggered the event — a stable, library-managed
// properties["target"] key rather than an ad-hoc one every app names differently
LocalTracker.current.track("Recipe Saved", target = "share_button")

// Impressions and clicks — screen/section from the ambient ScreenContext (TrackedScreen)
// are attached automatically
Text("Save", Modifier.trackClick("Recipe Saved") { save() })
Card(Modifier.trackImpression("Recipe Viewed", target = "recipe_card")) { RecipeCard() }

// Opt-in: report every tap without instrumenting each element — pass AutocaptureConfig to
// AutographProvider. Identification prefers testTag, then role, then the accessibility label;
// displayed text is never collected. Exclude a subtree with Modifier.autographIgnore().
AutographProvider(tracker, autocapture = AutocaptureConfig()) {
    App()
}
```

## Samples

`sample-shared`'s demo composables exercise every snippet above for real — `AutographProvider`,
`Modifier.trackClick`/`trackImpression`, and opt-in `AutocaptureConfig` autocapture — against a
`LoggingTracker` that prints each event so you can watch them fire as you tap:

- **Android**: `sample-android`. Run `./gradlew :sample-android:installDebug` and launch it, or
  open the project in Android Studio.
- **iOS**: `sample-ios/iosApp.xcodeproj` — a thin SwiftUI shell hosting `sample-shared`'s
  `ComposeUIViewController`. Open it in Xcode and run, or
  `xcodebuild build -project sample-ios/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=<device>"`.
  Its Run Script build phase calls `./gradlew :sample-shared:embedAndSignAppleFrameworkForXcode`
  automatically, so no separate Gradle step is needed first.

### Scoped context

`AutographScope` attaches a property to **every** event emitted from its content — the canonical
case being a route parameter like the `article_id` on `articles/{article_id}` that you want on all
of that screen's events without threading it through each call:

```kotlin
composable("articles/{article_id}") { entry ->
    val id = entry.arguments!!.getString("article_id")!!
    AutographScope("article_id" to id) {
        ArticleScreen()   // every event below carries article_id
    }
}
```

It works by wrapping the ambient `LocalTracker` in a decorator that merges the property into each
event, so it reaches everything nested inside that reads the tracker — `Modifier.trackClick` /
`trackImpression`, `TrackScreenView` / `TrackedScreen`, and plain `LocalTracker.current.track(...)`.
There's a `JsonObject` overload for non-string values. Notes:

- **Scopes nest and compose.** An inner scope adds to the enclosing one; on a key clash the inner
  scope wins, and a property the call site passes explicitly always wins over the scope (the scope
  is a default a specific event can still refine). Screen/section from `TrackedScreen` compose
  independently.
- **`identify` traits are not scoped** — they describe the user, not the screen the event fired on.
- **Autocapture carries the scope, but attributes it by push order.** Autocaptured taps fire from
  the root tracker above your screens, so they can't read this `CompositionLocal`; they read an
  ambient stack (`autograph-context`) that `AutographScope` and `TrackedScreen` mirror into instead,
  and so do carry the scope, the screen, and the section you passed to `TrackedScreen`. (The section
  is a screen-wide sub-label — a tab or layout variant, e.g. `TrackedScreen(name, section = "For You")`
  — not a region within the screen; every tap under the screen carries it. Set it through
  `TrackedScreen`, not by providing a `ScreenContext` through `LocalScreenContext` yourself, which is
  still not mirrored. `trackClick` / `trackImpression` read screen and section from
  `LocalScreenContext` either way.) That stack is ordered by when a scope was *mounted*,
  not by where the tap landed, so a tap is attributed to the **innermost scope mounted last** — which
  is the tapped element's own scope only while a single scope subtree is mounted at a time (a screen
  or route — the intended unit). When sibling scopes are mounted **simultaneously** — rows in a list
  each wrapped in their own scope, split-pane content, a bottom sheet or dialog over the screen
  beneath it — a tap on an earlier sibling is attributed to the later one. Scope a screen/route
  rather than individual list rows until the Compose path can resolve scope from the tap-position
  semantics tree ([#68](https://github.com/uny/autograph/issues/68)). Explicit instrumentation and
  manual `track` calls are unaffected: they keep their lexical scope and are always exact.
- **ViewModels / non-Compose emitters** don't see the scope (a `CompositionLocal` covers the
  composition subtree only). Since the scoped value is usually the route argument the ViewModel
  already receives, include it there explicitly.

### Autocapture

`AutocaptureConfig` passed to `AutographProvider` observes taps app-wide and reports the tapped
element's identifier as `target` on a configurable event name (`"Element Clicked"` by default) —
without needing `Modifier.trackClick` on every element. It's opt-in: observing every tap is a
meaningfully different privacy posture than explicit instrumentation, so it's off unless you ask
for it. Elements already instrumented with `trackClick` / `trackImpression` are never
double-reported, and `Modifier.autographIgnore()` excludes a subtree entirely.

Implemented on Android (hit-testing the semantics tree via the same opt-in `RootForTest` entry
point other autocapture SDKs use) and iOS (walking the native accessibility tree Compose
Multiplatform bridges its semantics into — the walk lives in `autograph-uikit`'s
`AccessibilityTree.kt`, driven by `autograph-compose`'s `ElementResolver.ios.kt`; identification
there is `testTag`-only, since UIKit gives no way to tell an explicit label apart from Compose's
own text-synthesized one). Taps are silently not captured on JVM/desktop.

Every event now carries — this shape is the stable envelope contract described above:

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

## Typed event schemas

`autograph-schema` is a compile-time alternative to `EventValidator`: it generates a typed
`Tracker.track<EventName>(...)` extension function per event from a JSON Schema tracking-plan
document, so a missing required property or a wrong type is a compile error instead of a runtime
validator rejection.

```json
{
  "events": [
    {
      "name": "Recipe Saved",
      "properties": {
        "type": "object",
        "properties": { "target": { "type": "string" }, "quantity": { "type": "integer" } },
        "required": ["target"]
      }
    }
  ]
}
```

```kotlin
tracker.trackRecipeSaved(target = "share_button") // quantity is optional, defaults to null
```

**This first slice ships the codegen engine and a plain `GenerateAutographEventsTask` you register
and wire into your source set by hand** — a convenience Gradle plugin that applies and wires it
automatically is a planned follow-up, not yet shipped:

```kotlin
val generateEvents = tasks.register<GenerateAutographEventsTask>("generateAutographEvents") {
    schemaFile.set(layout.projectDirectory.file("tracking-plan.json"))
    packageName.set("com.example.analytics.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/autographSchema"))
}
kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateEvents.map { it.outputDirectory })
}
```

Only a minimal JSON Schema subset is understood: a top-level `events` array, each with a `name`
and an optional `properties` object schema (`string`/`integer`/`number`/`boolean` properties,
`required`). Nested objects/arrays, `enum`, `$ref`, and `oneOf`/`anyOf`/`allOf` are not supported.
Generated functions don't yet expose `Tracker.track`'s `target` parameter — pass it via a regular
schema property for now. This is additive, not a replacement — `EventValidator` remains useful for
consumers who don't want a codegen step, or for cross-cutting rules a single event's shape doesn't
capture.

## Testing

`autograph-test`'s `InMemoryTestTransport` records every event in memory instead of sending it
anywhere, so your own instrumentation code can be asserted against in a fast unit test — no real
backend, network, or device needed, and unlike `DebugTransport` below (log-only, for eyeballing on
a real build) it's actually assertable:

```kotlin
val transport = InMemoryTestTransport()
val tracker = Autograph {
    transport(transport)
    store = InMemorySeqStore() // don't let seq/session state leak onto disk between test runs
    dispatcher = Dispatchers.Unconfined // stamp synchronously, so assertions run right after the call
}

tracker.track("Recipe Saved", target = "share_button")

transport.assertEventFired("Recipe Saved", properties = mapOf("target" to "share_button"))
```

Also included: `assertScreenFired` / `assertIdentifyFired`, `assertEventNotFired`, `assertOrder`
(delivery ordering), and envelope-aware checks — `assertNoSeqGaps` (a gap means a lost event) and
`assertSingleSession` (no session rotation occurred). `properties`/`traits` match by containment by
default (`exact = true` for an exact match), and accept plain Kotlin values or `JsonElement`
directly.

## Debugging

`DebugTransport` wraps another transport and logs every outgoing event before delivering it — for
eyeballing events on a real device/build during manual QA, separate from the app's production
transport and from `autograph-test`'s unit-test assertions above:

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
- [x] Autocapture on Android (opt-in `AutocaptureConfig` on `AutographProvider`)
- [x] Autocapture on iOS (walks the native accessibility tree Compose Multiplatform bridges its semantics into)
- [x] `sample-android` runnable sample app
- [x] iOS sample app
- [ ] Navigation 3 `NavEntryDecorator` for automatic screen tracking
- [x] `autograph-test`: in-memory transport with assertion helpers
- [x] `autograph-segment-swift` companion package (SPM)
- [ ] More transport adapters (PostHog, Amplitude, Firebase)
- [ ] `autograph-schema`: typed generated event schemas — codegen engine + manual `Task` shipped; a
  convenience Gradle plugin for automatic wiring is not yet

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
