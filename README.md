# Autograph

[![CI](https://github.com/uny/autograph/actions/workflows/ci.yml/badge.svg)](https://github.com/uny/autograph/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.ynagai.autograph/autograph-core)](https://central.sonatype.com/namespace/dev.ynagai.autograph)
[![API docs](https://img.shields.io/badge/API-docs-4c1)](https://uny.github.io/autograph/)

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

Two further boundaries of *"a sequence number is never reused"* are worth naming rather than
implying:

- **It assumes a single process.** The store is an in-memory map persisted last-write-wins, so it
  expects one Autograph instance to own the file. That holds for a normal app, but on Android
  `Application.onCreate` runs in **every** process — a `:webview`/`:service`/push-SDK process, not just
  the main one — and a second Autograph built there would share the same file: the two could hand out
  the same sequence numbers and clobber each other's session state. If your app runs Autograph in more
  than one process, give each its own `AutographConfig.store` pointed at a process-named directory. A
  built-in per-process split (a name suffix, or a file lock) is deliberately deferred until that need is
  observed rather than guessed at.
- **A corrupt state file resets to zero.** An unreadable or half-parsed file is treated as *absent*, so
  the counters restart from 0 — a reuse, not a crash on launch (starting a fresh session beats refusing
  to open the app). The atomic write above is exactly what keeps this unreachable in practice: a reader
  only ever sees a complete old or complete new file. It is stated because a "never reused" guarantee
  deserves its one exception named.

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
>
> How the Kotlin API will be allowed to change once it *does* stabilize — which artifacts get a
> SemVer ABI guarantee, and what may be added to each public type without a major bump — is
> settled in [ADR 0001](docs/adr/0001-public-api-evolution.md).

## Modules

| Module | What it does |
|:--|:--|
| `autograph-core` | `Tracker` facade, envelope stamping (id / seq / session), transport SPI. Zero UI dependencies. |
| `autograph-segment` | Segment adapter. Android: wraps `analytics-kotlin`, stamping inside the pipeline (a `Before` plugin) so even SDK-generated lifecycle events carry the envelope. iOS: bridge interface for `analytics-swift`, implemented by the `autograph-segment-swift` reference adapter (see below). |
| `autograph-compose` | Compose Multiplatform instrumentation: `AutographProvider`, `TrackScreenView` / `TrackedScreen`, automatic screen tracking for navigation-compose, `Modifier.trackImpression` / `Modifier.trackClick`, `AutographScope` for screen-scoped event context, and opt-in autocapture of taps (Android and iOS). |
| `autograph-context` | The ambient scope / screen-context stack that autocapture reads at tap time. Framework-agnostic (no Compose dependency), so native UIKit / SwiftUI / Android View surfaces can push context too. `autograph-compose` mirrors `AutographScope` and `TrackedScreen` into it for you — you only touch this module directly when instrumenting a non-Compose surface. |
| `autograph-uikit` | iOS-only, and two mechanisms rather than one. Native (non-Compose) taps are mapped to an element through `UIView.hitTest`, which needs no accessibility state and so works in a cold process; the accessibility-tree walk in the same module is what `autograph-compose`'s iOS resolver uses for Compose Multiplatform. `installAutographNativeTapCapture`, `installAutographNativeScreenCapture`, and the tap opt-outs `registerAutographIgnoredView` / `registerAutographIgnoredBounds` are supported public API. The rest of the module is `@AutographInternalApi` — don't depend on it directly. |
| `autograph-android` | Android-only. Native (non-Compose) screen capture: `installAutographNativeScreenCapture` auto-emits `Screen Viewed` from `Activity` / `Fragment` lifecycle, so a hybrid app's View/XML screens are tracked alongside its Compose ones. Taps on Android View content are *not* captured — see [What is and isn't captured](#what-is-and-isnt-captured). |
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
- **Autocapture carries the scope, but attributes it by lineage.** Autocaptured taps fire from
  the root tracker above your screens, so they can't read this `CompositionLocal`; they read an
  ambient stack (`autograph-context`) that `AutographScope` and `TrackedScreen` mirror into instead,
  and so do carry the scope, the screen, and the section you passed to `TrackedScreen`. (The section
  is a screen-wide sub-label — a tab or layout variant, e.g. `TrackedScreen(name, section = "For You")`
  — not a region within the screen; every tap under the screen carries it. Set it through
  `TrackedScreen`, not by providing a `ScreenContext` through `LocalScreenContext` yourself, which is
  still not mirrored. `trackClick` / `trackImpression` read screen and section from
  `LocalScreenContext` either way.) The stack resolves scope along the scope **lineage**: nested
  scopes (a screen/route and the scopes inside it — the intended unit) lie on a single chain and
  merge exactly, inner winning a key clash. When sibling scopes are mounted **simultaneously** —
  rows in a list each wrapped in their own scope, split-pane content, a bottom sheet or dialog over
  the screen beneath it — neither encloses the other, and the stack cannot tell from mount order
  alone which subtree a tap landed in. Rather than attribute the tap to an arbitrary sibling, it
  drops **those** scopes: a wrong scope is worse than none and irreversible in analytics. What
  **encloses** them all still attributes, since the tap is under it whichever sibling it hit — so a
  route scope wrapping a list of per-row scopes keeps carrying the route, and only the ambiguous
  row-level keys go missing. Screen and section are unaffected (one screen is active at a time), and
  so is explicit instrumentation — `trackClick` / `trackImpression` and manual `track` calls keep
  their exact lexical scope. To scope siblings *exactly* rather than have them drop, use
  `AutographElementScope` below.
- **`AutographElementScope { … }` scopes one element's subtree exactly.** It is the per-element
  counterpart of `AutographScope`, for the case that composable cannot serve — simultaneously-mounted
  siblings, above all a list's rows:

  ```kotlin
  LazyColumn {
      items(articles) { article ->
          AutographElementScope("article_id" to article.id) {
              Row(Modifier.clickable { open(article) }) { ArticleRow(article) }
          }
      }
  }
  ```

  It **wraps** the element rather than modifying it, and that shape is load-bearing rather than
  stylistic: on iOS the scope travels in the element's accessibility identifier, the same slot the
  tap's `target` comes from, and Compose collapses two `testTag`s on one layout node to the first —
  so the scope needs a layout node of its own. This supersedes `Modifier.autocaptureScope`, which is
  deprecated and removed at 1.0; on Android it applies the very same node, so switching changes
  nothing there.

  The wrapper is a `Box` that propagates its constraints and clips nothing, so it is layout-neutral
  with two exceptions worth knowing when you migrate, both of which follow from it being a real layout
  node. **Modifiers that are parent data for the *enclosing* layout don't cross it:** `Modifier.weight`
  in a `Row`/`Column` and `alignByBaseline` stop compiling once their element moves inside, while
  `align` and `matchParentSize` are `BoxScope` members and quietly rebind to the wrapper instead. And
  **wrap one element, not a run of siblings** — the content is a `BoxScope`, so two children stack
  rather than being laid out by your `Row`.

  Attribution reads the marker back off the **tapped element's own ancestry** in the semantics tree
  — the same chain the tap's `target` is picked from, so the scope always describes the element that
  was actually hit rather than a neighbour. Mount order never enters it. Nesting composes exactly
  (inner wins a key clash), enclosing `AutographScope` frames still contribute underneath, and
  screen/section still win over both wherever they are actually set — with no screen resolved at
  all, a scope key you named `screen` stands (and likewise `section` where no section is set),
  exactly as it would on an explicit `track` call, so don't name a key either of those. A `clickable`
  drawn *outside* the wrapper's own bounds — an overhanging badge, an `offset` decoration — is scoped
  too. Three things to know, all deliberate:
  - **Autocapture only.** `trackClick` / `trackImpression` / manual `track` calls inside the subtree
    do *not* pick these properties up. Pass them explicitly there, or wrap the content in
    `AutographScope` too. It is a companion to `AutographScope`, not a replacement — and folding the
    two together would push one ambient frame per list row, every one of which `ScopeStack` drops as
    ambiguous, so the cost would land exactly where the benefit does not.
  - **On iOS it changes your app's accessibility structure, slightly.** The scope reaches the tap
    through the UIKit accessibility tree, the only route to a tapped element Compose Multiplatform
    offers there, so the wrapper is published as a semantic-group container and the scope's keys and
    values sit in its `accessibilityIdentifier` verbatim. VoiceOver's reading order, stop count and
    spoken labels come out unchanged in four controlled A/Bs against identical unwrapped content —
    but those read the *bridged hierarchy*, not a running VoiceOver, so treat them as strong evidence
    rather than as a screen-reader pass. What is measured outright is the rotor: the wrapper publishes
    a container type where the unwrapped control publishes none, so with the rotor set to Containers
    the scoped element becomes one more navigation target. Whether VoiceOver announces the group's
    boundary on entry or exit has not been checked on a device.
    The identifier is also readable by any accessibility client (Accessibility Inspector,
    Appium, Maestro), so don't put anything in a scope you wouldn't put in a `testTag`. On Android
    none of this applies: the scope is a private semantics property no assistive technology can see.
  - **The iOS half needs Compose Multiplatform 1.11 or newer.** Older versions don't publish a
    traversal group as its own accessibility element, so the wrapper is bridged as a flat sibling
    rather than an ancestor and the scope is silently absent — a missing property rather than a wrong
    one, which is the trade this library takes on purpose, but silent. Pin your Compose version if
    you rely on iOS scopes. On an older Compose, instrument the element explicitly instead:
    `Modifier.trackClick("Article Opened", properties) { open(article) }` in place of its
    `clickable` carries the properties on both platforms, reporting the element under that name
    rather than autocapturing it.
- **ViewModels / non-Compose emitters** don't see the scope (a `CompositionLocal` covers the
  composition subtree only). Since the scoped value is usually the route argument the ViewModel
  already receives, include it there explicitly.

### Autocapture

`AutocaptureConfig` passed to `AutographProvider` observes taps app-wide and reports the tapped
element's identifier as `target` on a configurable event name (`"Element Clicked"` by default) —
without needing `Modifier.trackClick` on every element. It's opt-in: observing every tap is a
meaningfully different privacy posture than explicit instrumentation, so it's off unless you ask
for it. Elements already instrumented with `trackClick` are not double-reported — with one iOS
multi-touch exception, described below — `Modifier.autographIgnore()` excludes a subtree entirely,
and [`AutographElementScope { … }`](#scoped-context) attaches per-element properties to the taps
under it.

`trackImpression` deliberately does **not** suppress autocapture: it reports a visibility event and
never a click, so a tap on the element it marks is one autocapture owns. Use `autographIgnore()` for
an element that should report neither.

The two platforms establish "already instrumented" differently, and it costs iOS one edge case.
Android reads the marker off the tapped element's semantics ancestry. iOS cannot — Compose
Multiplatform's UIKit accessibility bridge carries only the fixed `UIAccessibility` properties, not
arbitrary semantics keys — so it observes instead whether a `trackClick` handler *ran* during the
tap being resolved. When a single dispatch consumes more than one pointer, which handler belongs to
which finger is undecidable, and rather than guess, iOS reports the tap: **an instrumented element
tapped as part of a genuine multi-touch gesture emits both its explicit event and an
`Element Clicked`**. A second finger merely resting on the screen does not trigger this. The
alternative — guessing — risks dropping an unrelated element's tap entirely, which is the failure
this design refuses to make ([#179](https://github.com/uny/autograph/issues/179)).

Implemented on Android (hit-testing the semantics tree via the same opt-in `RootForTest` entry
point other autocapture SDKs use) and iOS (walking the native accessibility tree Compose
Multiplatform bridges its semantics into — the walk lives in `autograph-uikit`'s
`AccessibilityTree.kt`, driven by `autograph-compose`'s `ElementResolver.ios.kt`; identification
there is `testTag`-only, since UIKit gives no way to tell an explicit label apart from Compose's
own text-synthesized one).

#### What is and isn't captured

Autocapture is per **surface**, not per app: a Compose screen and a native screen go through different
pipelines, each opt-in separately. An app migrating to Compose incrementally therefore runs both. This
table is the whole picture, because a gap here produces **no event at all** — which reads downstream as
"users didn't tap this" rather than "this surface isn't instrumented".

| Surface | Taps | Screen views |
|:--|:--|:--|
| Compose — Android | ✅ `AutocaptureConfig` | ✅ `TrackedScreen` / navigation-compose |
| Compose — iOS | ✅ `AutocaptureConfig` | ✅ `TrackedScreen` / navigation-compose |
| Compose — JVM/desktop | ❌ not captured | ✅ `TrackedScreen` |
| iOS native — UIKit | ✅ `installAutographNativeTapCapture` — for identified controls; **cell selection is not covered, see below** | ✅ `installAutographNativeScreenCapture` |
| iOS native — SwiftUI | ❌ **not captured — instrument explicitly, see below** | ✅ [`.autographScreen`](#ios-swiftui-screens-with-autographscreen) |
| Android native — View / XML | ❌ **not captured** ([#63](https://github.com/uny/autograph/issues/63)) | ✅ `installAutographNativeScreenCapture` (Activity / Fragment) |

The asymmetry worth stating plainly: **a hybrid Android app gets `Screen Viewed` for its non-Compose
screens but no `Element Clicked` for taps on them**, while the same app on iOS gets both. If that gap
matters to you, say so on [#63](https://github.com/uny/autograph/issues/63) — it is deferred for want of
a demand signal, not for a technical reason.

> [!WARNING]
> **iOS native tap capture does not cover SwiftUI at all** ([#135](https://github.com/uny/autograph/issues/135),
> [#191](https://github.com/uny/autograph/issues/191)). A SwiftUI element has no per-element backing
> view, so a tap on one cannot be named. Measured on a freshly created simulator and again on a physical
> iPad Pro 11" rebooted and launched from the home screen: a whole SwiftUI screen is a handful of plain
> `UIView`s, and a `.accessibilityIdentifier(_:)` set on a SwiftUI button appears on none of them. A
> later sweep over `List`, `Form`, `Picker` and a plain `Button` found the same — no SwiftUI identifier
> anywhere in the view hierarchy.
>
> **Instrument SwiftUI surfaces explicitly**, with `Modifier.trackClick` on Compose content or a
> `track` call in the button's action. There is no partial credit to rely on here and no workaround in
> this library.
>
> Earlier versions did report SwiftUI taps *sometimes*: a second resolver walked the accessibility
> element tree, which UIKit and SwiftUI build only once an accessibility client has run in the process
> (VoiceOver, Voice Control, Switch Control, the Accessibility Inspector, an XCUITest runner). That has
> been **removed**, because "sometimes" meant precisely *for users running assistive technology, and in
> your test runs*. Analytics conditioned on a user's assistive technology is not partial data, it is
> biased data, and downstream its silence is indistinguishable from nobody having tapped. It also made
> a simulator you had run UI tests against look reliable while the same build dropped every SwiftUI tap
> on a user's device.
>
> **UIKit is covered, cold and warm alike** ([#189](https://github.com/uny/autograph/issues/189)). UIKit
> taps resolve through `UIView.hitTest`, which never consults accessibility: in a stone-cold process on
> a physical device, the tap position handed to `hitTest` returned the real `UIButton` carrying its
> `accessibilityIdentifier`. A `UIControl`, or any view with an **enabled single-tap** gesture
> recognizer, is identified in a cold process exactly as in a warm one — **provided it carries an
> `accessibilityIdentifier`**, which is the only thing ever reported as a `target`. An untagged control
> is not reported, and neither is a `UITableViewCell` whose selection is the delegate's (see the gap
> list below). `hitTest` is also *the* answer to which view receives a touch, so it settles the overlap
> and z-order ambiguities an accessibility walk only approximates, and it honours
> `isUserInteractionEnabled`, `isHidden` and `alpha`, which such a walk cannot see at all.
>
> **Compose autocapture on iOS is not affected either** — Compose Multiplatform builds its bridged
> accessibility elements *on demand* too, but its activation path does not require an accessibility
> client: reading the tree is what triggers it, and this library's walk is such a reader. The gates in
> front of that are about which scene is live, not about assistive technology — `AccessibilityTree.kt`
> names them. So Compose taps resolve in a cold process. That is a dependency on CMP's activation rather
> than a guarantee, which is why a CMP bump gets a cold-device check
> ([#154](https://github.com/uny/autograph/issues/154)).

Known gaps *within* iOS **UIKit** tap capture, all tracked on
[#86](https://github.com/uny/autograph/issues/86): a `UITableViewCell` or `UICollectionViewCell` whose
selection is the delegate's rather than a control's — it reaches nothing `hitTest` calls interactive, so
it is not reported. The same applies to a `UITextView`, which is a
`UIScrollView` subclass, and to any control with no `accessibilityIdentifier`. (A scroll view is
deliberately never reported as the tapped element, and also **stops** the search rather than deferring it
to an ancestor: otherwise a container would claim every tap on its own content.) Note the flip side — a
`UIControl` is reported whatever kind it is, so taps that merely focus a `UITextField` become
`Element Clicked` events; reach for
[`registerAutographIgnoredView`](#ios-excluding-native-content-from-tap-capture) on fields you do not
want in the stream. Also unreported: a `UIKitView` hosted inside Compose (excluded by the Compose
boundary, unresolvable by Compose).
`UIControl` target-action taps used to be listed here; they resolve since
[#189](https://github.com/uny/autograph/issues/189), because `hitTest` names the control directly.

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
Objective-C protocol via `Autograph.xcframework`. The `AutographSegmentSwift` product (this repo's
root `Package.swift`) is the reference adapter implementing it against `analytics-swift` —
`analytics-swift`'s event model is pure-Swift structs/generics with no Objective-C-visible surface
for the stamping this library needs, so this adapter exists as plain Swift rather than something
Kotlin/Native could call into directly.

`Autograph.xcframework` is a single umbrella framework carrying the whole Kotlin iOS surface — tracker
core, the ambient scope/screen stack, UIKit capture, and the Segment bridge — emitted by the
`autograph-apple` aggregation module. It has to be one framework: Kotlin/Native prefixes every exported
Objective-C class with its framework's name, so splitting it would make a `Tracker` from one framework
a different ObjC type than a `Tracker` from another, and a hybrid app uses more than one part together.

**Splitting it also breaks the tap opt-out, silently and open.** Kotlin/Native embeds all reachable
Kotlin code into *each* framework, so two frameworks carrying `autograph-uikit` carry two independent
copies of its registries. Register a view or region through one and install tap capture through the
other, and the resolver consults a registry that has never heard of your exclusion: taps you opted out of
are captured, with nothing logged. Reach `registerAutographIgnoredView` / `.autographIgnore()` and
`installAutographNativeTapCapture` through the same framework — which the umbrella gives you by
construction, and which is why this repo's own sample registers through `sample_shared` (the framework it
installs capture from) rather than through the umbrella.

`Package.swift` lives at the repository root (not a subdirectory) specifically so external apps
can add it the normal way, `.package(url: "https://github.com/uny/autograph.git", from: "…")` —
SwiftPM only resolves a URL-based dependency's manifest from the repo root.

Its `Autograph` binary target picks one of two sources depending on what's on disk:
- **Monorepo/local dev**: if `autograph-apple/build/XCFrameworks/release/Autograph.xcframework`
  exists (built via `./gradlew :autograph-apple:assembleAutographReleaseXCFramework`), it's
  used directly — so this always reflects whatever the Kotlin side currently builds, uncommitted
  changes included.
- **External consumers**: otherwise, falls back to a checksummed download from that version's
  GitHub Release asset (`Autograph.xcframework.zip`).

## iOS: SwiftUI screens with `.autographScreen`

> **Consumption model.** iOS instrumentation is **first-class today for hybrid apps** — a Kotlin
> Multiplatform shared module (which builds the `Tracker` and owns the `ScopeStack`) plus a Swift /
> SwiftUI shell. A **pure-Swift** app (no KMP module) can already implement `SegmentBridge`
> (`AutographSegmentSwift`) and use `.autographScreen` (`AutographUI`), but there is not yet a
> Swift-idiomatic way to *construct* a `Tracker` — tracked as its own epic in
> [#94](https://github.com/uny/autograph/issues/94). The snippets below assume the hybrid shape.

Native screen capture auto-emits `Screen Viewed` for UIKit `UIViewController` transitions (an opt-in
`viewDidAppear:` swizzle). It cannot see **SwiftUI** screens: every SwiftUI screen is one
system-bundle `UIHostingController`, and `NavigationStack` swaps its destinations inside that single
host with no per-destination `viewDidAppear:`. So SwiftUI screens name themselves, using the
`AutographUI` product (iOS 14+):

```kotlin
// Kotlin, in your shared module: expose a capture built on the SAME ScopeStack you hand
// AutographProvider / the native capture, so a SwiftUI screen becomes the previous_screen of the
// next screen and taps under it carry it.
fun makeScreenCapture(tracker: Tracker, scopeStack: ScopeStack) =
    AutographScreenCapture(tracker, scopeStack)
```

```swift
// Swift: set the capture once at the root, then name each screen.
import AutographUI

ContentView().autographScreenCapture(capture)          // once, at the root

RecipeDetail().autographScreen("RecipeDetail")         // on each screen
```

A missing `.autographScreenCapture(_:)` is **loud** — it traps in debug and logs a fault in release,
rather than silently dropping every screen view.

**Hybrid double-emit.** If you host a `.autographScreen` SwiftUI screen inside an *app-bundle*
`UIViewController` (not a plain `UIHostingController`, which the swizzle already skips) while native
screen capture is also installed, both could report that screen. Return `null` from that controller's
`screenName` in `installAutographNativeScreenCapture` to let the explicit path own it.

## iOS: excluding native content from tap capture

The native counterpart of Compose's `Modifier.autographIgnore()` — a **privacy** control for subtrees
that must not be autocaptured (a payment field, anything sensitive). Explicit `trackClick` inside an
excluded subtree still fires; only *ambient* tap autocapture is suppressed.

```swift
import AutographUI

// SwiftUI — excludes this view's on-screen region:
SensitiveCard()
    .autographIgnore()
```

```swift
import Autograph

// UIKit — excludes a view (and its subtree). Keep the returned token; call `unregister()` to stop.
let registration = registerAutographIgnoredView(view: sensitiveView)
```

A `UIView` *is* the thing on the tap's delivery path, so the UIKit form excludes it directly. A
SwiftUI view is not UIView-backed, so `.autographIgnore()` reports the wrapped content's **window
rectangle** and the pipeline vetoes taps that land inside it — tracked every frame, so scrolling or
relayout can't leave a stale region behind. Place `.autographIgnore()` as close to the sensitive
content as possible: the excluded region is the rectangle at the point of insertion, and rotation or
non-rectangular clipping is approximated by the axis-aligned bounding box.

**What the SwiftUI form is for now that SwiftUI taps are not captured.** Ambient capture never names a
SwiftUI element, so wrapping pure SwiftUI content changes nothing on its own. It still matters where a
SwiftUI subtree *hosts UIKit* — a `UIViewRepresentable`, or a `UIViewControllerRepresentable` — since
those controls are captured normally and the region veto is what excludes them without reaching for a
`UIView` reference SwiftUI does not hand you. Keeping it is also the safe direction: it holds the
region whatever a future version learns to resolve there.

## Requirements

- Kotlin **2.4.10** (UUIDv7 generation comes from the standard library)
- Compose Multiplatform **1.11.1** (`Modifier.trackImpression` uses its stable
  `Modifier.onVisibilityChanged`)
- Android `compileSdk` **37** or later, for consumers of `autograph-compose` — required by the
  `androidx.lifecycle` 2.11.0 it depends on
- Targets: **Android**, **JVM**, and **iOS** — device `iosArm64` and the Apple-Silicon simulator
  `iosSimulatorArm64`. The Intel-Mac simulator (`iosX64`) is intentionally not shipped: Apple-Silicon
  simulators cover current development, and adding a target costs a Kotlin/Native link on every CI run,
  so it will be added when an Intel-Mac consumer needs it — open an issue if that's you. Compose Web /
  Wasm (`wasmJs`) is out of scope for now, and by design rather than oversight: a browser target has no
  filesystem for the sequence/session store and a different transport story, so it is a deliberate
  design task, not a target flag to flip.

## Roadmap

- [x] `Modifier.trackImpression` / `Modifier.trackClick` built on Compose visibility APIs
- [x] Autocapture on Android (opt-in `AutocaptureConfig` on `AutographProvider`)
- [x] Autocapture on iOS (walks the native accessibility tree Compose Multiplatform bridges its semantics into)
- [x] Native (non-Compose) capture for hybrid apps: iOS UIKit taps, iOS + Android screen views,
  and the tap opt-outs (`registerAutographIgnoredView`, SwiftUI `.autographIgnore()`)
- [ ] Native taps on the Android View system ([#63](https://github.com/uny/autograph/issues/63)) — the
  one hybrid gap left; deferred for want of a demand signal, so say so on the issue if you need it
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

Because that self-correcting commit is pushed to `refs/tags/<tag>` and nowhere else, it used to
land on no branch at all — `main`'s copy of those values stayed at v0.1.0 for three releases. A
final CD step now replays the same rewrite onto `main` — retrying if `main` moves mid-release, and
standing down if `main` names a newer release, since two tags can be in flight at once — so reading
`Package.swift` on `main` describes the most recent release rather than an ancient one. It runs
after the release assets are uploaded, so a failure there can't leave a half-published tag.
Note this does not make `.package(url: …, branch: "main")` a supported way to consume the package:
`Sources/` would be at `main`'s HEAD while the binary target is the last *released* Kotlin build,
so the Swift and Kotlin halves can be out of step. Depend on a version.

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
