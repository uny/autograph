# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. The stability each
artifact commits to before then is spelled out in [ADR 0001](docs/adr/0001-public-api-evolution.md);
the `context.instrumentation` envelope is already semver-stable (see the README).

## [Unreleased]

### Changed

- **`event_id` and `session.id` come from the stdlib's `Uuid.generateV7()` again**; the
  `UuidV7Generator` that 0.8.0 added is deleted ([#205]). 0.8.0 wrote it on the claim that
  `Uuid.generateV7()` needed Kotlin 2.4 — it does not: it has been in `kotlin-stdlib` since 2.3.0
  (read off the published 2.3.0 and 2.3.21 JVM jars *and* the Kotlin/Native 2.3.0 stdlib klib's
  `kotlin.uuid` metadata, where 2.2.20's has no such symbol), so it always sat inside the floor
  0.8.0 lowered to. That was the second of #205's two premises to fail; the first — that KSP's
  2.3-numbered releases could not run on a Kotlin 2.4 project — is recorded on the issue. The floor
  itself stays on 2.3, now for the only reason that was
  ever real — a klib links only from a toolchain at least as new as the one that built it, so the
  build version is the floor, and it is kept on the oldest line the code allows, as `kotlinx` does.
  Nothing observable changes for consumers: ids are still UUIDv7, still time-ordered, and ids minted
  inside one millisecond are still ordered by a dedicated counter — the stdlib's instead of ours.
  One internal detail moves back to where 0.7.0 had it: a session id's timestamp is wall time, no
  longer the clock injected into `Stamper`; the clock still drives every session decision. One
  edge the deleted generator handled and the stdlib's (as of 2.3.21) does not: if the *first*
  `generateV7()` call in a process sees a wall clock at or before the Unix epoch, the generator's
  zero initial state takes its "clock not ticking" path and the ids it returns until the clock
  passes the epoch carry version nibble `0`, not `7`. Left as is: no supported platform boots with
  such a clock (iOS's floor is 2001, Android's is the build date), and a guard here would be dead
  code the day the stdlib fixes it.

- **`Autograph.xcframework` is linked at `minos 15.0` again, and stays there** ([#208], [#197]).
  0.8.0 and 0.9.0 shipped `minos 14.0` binaries without anyone choosing to (read off the published
  assets; 0.7.0's are 15.0): nothing in the Gradle build set a deployment target, so it was the
  Kotlin/Native toolchain's default, which is 15.0 on the 2.4 line and 14.0 on the 2.3 line that
  [#205] moved the build to. The umbrella now pins the value from a single catalog key
  (`ios-deploymentTarget`), through the same `-Xoverride-konan-properties` flag that 0.6.0's notes
  below declined to use for *lowering* the floor — the trade is different when the alternative is a
  floor that moves with every compiler bump. Because that flag is compiler-internal,
  the build going green is not taken as proof it worked: the `swift-package` CI job, the release dry
  run and the release itself now read `LC_BUILD_VERSION` and `MinimumOSVersion` back from every built
  slice and fail when they differ from `Package.swift`'s `.iOS(.v15)` — in either direction, since a
  manifest above the binary is also what the pin silently failing would look like (while the
  toolchain's default differs from the pin — on a line whose default is already 15.0 the check
  cannot tell the two apart). Measured against `main` before the pin, the check fails on the real
  14.0-vs-`.v15` mismatch; with it, both slices read 15.0. No consumer is affected: `.iOS(.v15)`
  already turned away everything below 15.

### Fixed

- **The ambient screen now follows what is on display, for screens declared in Compose too**
  ([#228]). A `TrackedScreen` composed inside a `ViewPager2` page that was moved off display, or
  inside an Activity paused behind a permission prompt, kept naming `ScopeStack.current()`'s
  screen — measured on a device as "the page most recently *composed*, not the one on display",
  because a pager composes its neighbouring pages at `STARTED`, before they are ever shown, and the
  frames of a composition were born active and never deselected. Two changes, and both halves are
  needed:
  - **An inactive frame takes everything nested under it with it.** `current()` now drops a frame
    whose ancestor on the stack is inactive, whatever the frame's own bit says — so a native surface
    the Android capture deselects silences the composition inside it, and a frame pushed under a
    demoted surface *while* it is demoted starts silent. `current(origin)` applies the same rule
    with one exemption, the origin's own surface (its lineage, and what sits beneath it with no
    boundary between): an origin is the pipeline asserting the event happened in that surface, so a
    tap on a page peeking beside the current one still attributes to that page's own
    `TrackedScreen` — landing on its Compose content or on a native button beside it — while a
    demoted *sibling* page's declarations stay off a tap on the current page exactly as they do
    ambiently. A parent link off the stack is transparent.
  - **A composition's frames follow its `LocalLifecycleOwner`.** `AutographProvider` marks the frame
    it pushes for the composition active exactly while the owner is `RESUMED`, seeded from the
    owner's state at composition; `RESUMED ↔ STARTED` is the demotion signal every host emits, so
    this holds with no native capture installed too, and on iOS, where Compose Multiplatform moves
    a `ComposeUIViewController`'s owner to `RESUMED` at `viewWillAppear` and off it at
    `viewDidDisappear` (two Compose tabs, or a pushed Compose screen, previously left the last
    *composed* one ambient). The two iOS readers of `current()` — the native tap capture and the
    explicit element capture — pick this up with no change of their own.

  The taps autograph captures itself resolve from an origin, so their own surface's demotion never
  gates them — which took two more changes. A Compose tap in a composition whose host view nothing
  claims (every Compose tap on iOS, and on Android without the native capture) used to fall back to
  the ambient read, which would now have dropped a visible page's own `TrackedScreen` whenever its
  host reported `STARTED` (a `ViewPager2` neighbour peeking beside the current page); such a tap now
  resolves from the composition's own frame, unlinked. What an app pushes by hand stays visible to
  it as before; what a *claimed* native surface beside it declares or masks no longer reaches it
  (absent rather than borrowed — the pipeline cannot place the composition under that surface). And
  the Android capture now selects every surface it sees resume, including one that declares nothing:
  a Compose-hosting fragment the adopter opted out by name was left deselected while on display,
  which under the propagation above would have silenced everything composed inside it and let the
  screen beneath answer instead. What changes for adopters is every read of `current()` — a host app
  enriching its own events, and the two iOS captures above. `Screen Viewed` is untouched: a demotion
  still ends no view and a return still reports none. What this does not reach: the retained
  off-screen pages of a Compose `HorizontalPager`, which share one lifecycle owner and emit no
  signal.

## [0.9.0] - 2026-09-13

### Added

- **`ScopeStack.current(origin)`** — the ambient context *as seen from* a frame: the origin's
  lineage (itself and every frame it is nested in), the declarations beneath it, and every frame
  under no boundary at all — nothing else. A sibling surface's screen or mask never reaches an event
  that did not happen in it, whatever the two frames' insertion order. The survivors resolve in
  insertion order, with one correction: a frame ranks no later than the content nested in it (and
  before it on a tie), so a surface adopted late (an Activity that predates the native capture's
  install) does not blank the `TrackedScreen` it hosts. A parent link that points off the stack — a frame since removed, or
  another stack's frame — is transparent: neither a contribution nor a boundary. Main-thread only,
  unlike the no-argument `current()`, because it is computed per event from the frame list rather
  than read from a published snapshot.

- **`ScopeStack.push(…, boundary = true)`** — a frame whose pipeline can tell, for every event it
  captures, whether the event happened inside that frame. A boundary is where `current(origin)`'s
  descent stops: an event resolved from a surface does not pick up the declarations of the surfaces
  nested inside it, because its pipeline has already established the event is not in them. Every
  surface the Android native capture reserves a frame for is one. The root frame a Compose
  `AutographProvider` now pushes for its composition deliberately is **not**: a composition is how
  the surface hosting it declares its screen, not a surface of its own, so a native tap on a toolbar
  beside the `ComposeView` (or on an `AndroidView` interop button inside it, which the native capture
  reports) still sees the `TrackedScreen` in it, and a provider nested inside another's composition
  stays visible to the outer provider's taps. And a frame under **no** boundary at all — a root the
  app pushes by hand, a UIKit screen frame, a declaration outside any claimed surface — applies to
  every origin exactly as it does ambiently, so installing the native capture never hides a
  hand-pushed frame from a tap. A separate overload with no default for the flag, so an existing
  `push()` still resolves to the plain one — additive in both dumps.

  Third of the [#216] dependency stack (`ScopeStack` API → Android capture → Compose/observer), and
  the one that connects the two pipelines: the native capture nests each surface's frame under the
  surface containing it and claims each surface's root view with its frame (`View.autographScopeOwner`,
  an `@AutographInternalApi` in `autograph-context`'s new `androidMain`); the native tap capture
  resolves a tap from the surface the tapped view belongs to, and the Compose provider links its root
  frame under the surface hosting its composition **at tap time** — a composition is created before
  `onFragmentViewCreated` claims a late-added fragment's view (measured), so a lookup at composition
  time finds the *Activity's* claim, a wrong owner rather than a missing one — and also from a posted
  runnable at composition and again when a surface above it claims its root later (a late install),
  so a composition nobody has tapped yet (a pager's off-screen page) is already nested under its
  surface and cannot lend its `TrackedScreen` to a tap on the page beside it. Where nothing claims the host view (no native screen capture, iOS/desktop, a `Dialog` window)
  a Compose tap resolves ambiently, as before. An Activity's claim goes on its decor, not
  `android.R.id.content`, so a Toolbar menu tap outside content still belongs to the Activity; and
  uninstalling the screen capture takes its claims with it.

- **`ScopeStack.maskScreen(handle)`** — turn an already-pushed frame into one that declares *there is
  no screen here*, clearing screen and section rather than naming one. It exists for a surface that
  comes to the foreground and names no screen of its own: without a mask the frame of the screen
  *underneath* stays innermost, and every event captured on the unnamed surface is attributed to the
  screen the user just left — a wrong value, not a missing one, and one that survives every schema
  check. Frames after a mask still win, so content that does name a screen for itself is unaffected.
  One-way: a mask states something about the frame's *contents*, which does not stop being true while
  the surface is off-screen.

- **`AmbientContext.screenMasked`** — whether a mask is what left `screen` null, as opposed to no
  frame ever having named a screen. Both read as `screen == null` but they are opposite instructions
  to a pipeline holding a screen name from elsewhere: an absence may be filled, an assertion must be
  respected — filling it reinstates the screen the user just left, the exact wrong value a mask
  exists to prevent. Autograph's own pipelines hold no such name any more (see the history change
  below) and report no screen for either, so the flag is published for a pipeline that does.
  Additive under [ADR 0001](docs/adr/0001-public-api-evolution.md) §2a — `AmbientContext` is
  library-produced with an `internal` constructor and gains properties freely.

- **`ScopeStack.setActive(handle, active)` and `setActive(handles, active)`** — mark a frame as taking
  part in resolution, or not. An inactive frame keeps its position and its contents but contributes
  nothing: no screen, no section, no scope.

  This is the "is this surface the one on display?" bit, and frame *position* cannot answer it.
  Position stands in for recency of becoming foreground, which holds only while a frame leaves when
  its surface does. A host that keeps several surfaces mounted and merely demotes the off-screen ones
  — a pager caching neighbouring pages, a container that pauses rather than destroys — breaks that:
  the demoted surface's frame stays where it was and, being later in the list than the surface the
  user came back to, wins. Removing the frame instead would be wrong for the opposite reason: it has
  to come back, at the same position, without the surface being rebuilt. Two consequences the tests
  pin directly: a demoted sibling no longer out-ranks the surface on display, and it can no longer
  out-rank a mask reserved before it.

  The batched overload takes a `List` and publishes **one** snapshot for the whole group. `List`
  rather than the wider `Collection` it only needs, because `ScopeStack` is exported into the
  Swift-facing `Autograph.xcframework` and Kotlin/Native maps only `List`/`Set`/`Map` to an
  Objective-C collection — a `Collection` parameter exports as an untyped `id`, i.e. `Any` in Swift. A surface owns more than one
  frame — its screen, its mask, the scopes under it — and switching them one at a time republishes an
  intermediate context in which some of a surface's frames answer and others do not; a tap captured
  against that snapshot reads a state the app was never in.

  Additive: no existing signature changed, so nothing moves in the `api/` dumps (ADR 0001 §2f — `ScopeStack` is a caller-constructed concrete class whose members may grow).
  `update` revises contents only: it never clears a mask and never changes whether a frame is active.

  This is the first of a dependency stack (`ScopeStack` API → Android capture → Compose/observer)
  replacing the design withdrawn in [#217]. The Android capture that drives these switches is below.
  Refs [#216].

### Changed

- **Every screen declaration in `autograph-compose` now pushes an ambient frame, and autocaptured
  taps read only those frames — never `ScreenHistory`** ([#216]). A bare `TrackScreenView` and
  `NavController.TrackScreenViews` used to record history and emit without declaring anything, and
  the tap observer filled the gap by falling back to `ScreenHistory.lastScreen`. History records what
  has been *seen* and outlives it by design, so it cannot answer what is on display: on a surface
  that names no screen the fallback supplied the screen the user just left — a wrong value that
  survives every schema check, and the last place #216's failure remained.

  What changes for an app:
  - A bare `TrackScreenView` now *declares* its screen for as long as it is in the composition, so
    the screen leaves when it does rather than when something overwrites history. The declaration is
    **ambient, not scoped to a subtree** — the composable wraps nothing — so it applies to every
    autocaptured tap in the composition while it is present, and, because a frame naming a screen
    owns its section, it clears the section of a `TrackedScreen` it sits inside. Nested inside one,
    an autocaptured tap says the inner name while an explicit `trackClick` says the enclosing one
    (this still does not provide `LocalScreenContext`); composed as a later sibling — a sheet, a
    dialog — it wins for taps on the content behind it too. Use `TrackedScreen` when the screen
    should be scoped to what it wraps and the two paths should agree.
  - `NavController.TrackScreenViews` declares the current destination, revised in place on every
    change. **Call it above the `NavHost`** — the README example now shows one — so its frame is
    pushed before the destination content and a `TrackedScreen` inside a destination refines the
    route rather than competing with it. Screen and section resolve by push order, so the call order
    is what settles this; written the other way round, the route name would override the declared
    screen and drop its section.
  - A destination `screenName` maps to `null` now declares **no** screen instead of leaving the
    previous route's name attributing taps on it.
  - Content inside a masked surface that names its screen is carried again rather than dropped —
    the one measured cost of the old unconditional gate, now paid back.
  - An app that only ever recorded history without declaring (nothing in this library does) sees
    taps carry no screen. The remedy is the same as it always was: declare one.
  - **Hybrid apps sharing one `ScopeStack` see this on the native side too.** The new frames are
    ambient, and `autograph-uikit`'s tap and explicit-element capture read the same stack, so on iOS
    a screen declared with `TrackScreenView` or a tracked navigation destination now attributes
    native events as well while that composition is alive — previously only `TrackedScreen` did.
    On Android the same is true unless `installAutographNativeScreenCapture` is also installed: it
    is what claims each surface's views, and only then does a native tap resolve from the surface it
    happened in (#222) and see just that surface's declarations. With the tap capture alone nothing
    claims anything, so a tap resolves ambiently and reads the composition's declaration like iOS.

### Fixed

- **A native Android surface that names no screen of its own no longer reports the screen the user
  just left** ([#216]) — an Activity or Fragment excluded by the capture's own filter (a Compose host,
  a fragment-hosting shell) now *masks* the ambient screen while it is on display, so events captured
  on it carry no screen instead of inheriting the frame of the screen underneath, which survives
  whenever that screen was only paused and never stopped (a fragment `add`ed on top, a dialog
  fragment). Content that names a screen for itself — a `TrackedScreen` inside the excluded host — is
  pushed above the mask and still wins.

  The capture was rebuilt around **selection** to make this hold, rather than patched. Each surface
  now owns one frame, reserved empty and inert when it is created or attached and dropped when it is
  destroyed or detached; `setActive` decides whether it takes part. Reserving the position that early
  is what puts a host's frame below the content it hosts (`AbstractComposeView` composes from
  `onAttachedToWindow`, before `onFragmentViewCreated` — measured) and a parent fragment below its
  children (a child resumes *before* its parent — measured, and resume order would invert it).

  Three defects the previous, position-only design could not express are fixed with it:

  - A named page attached *after* an unnamed one is showing no longer lends it its screen. Its frame
    sits above the mask by insertion order but is not selected, so it contributes nothing. Position
    could not distinguish "mounted later" from "on display".
  - Several surfaces `RESUMED` at once (`add` on top, `show()`/`hide()`, a child before its parent) no
    longer all re-emit `Screen Viewed` when the host is merely paused and resumed. Reporting is no
    longer inferred from `ScreenHistory.lastScreen` at resume — that test misfires whenever more than
    one surface is resumed — but tracked per surface, and a host interruption ends nobody's view.
  - A pager page swapped out **while its host was paused** is now handled, and it is described by no
    callback at all: both pages are already `STARTED`, so neither pauses nor resumes (measured). The
    page the host came back to reports, the one it did not is no longer attributed to, and returning
    to that one later reports it again. Which page the host returned to is only readable once its
    resume dispatch has finished, so that single check is posted to the main looper —
    `onActivityPostResumed` would be the exact hook but is API 29+ and this module's floor is 24.
    Bookkeeping only: attribution and reporting are both applied in the callback they belong to.

  Masking is narrower than the filter, because a mask also asserts that the surface *covers* what it
  hides — and every one of those narrowings had to be measured rather than assumed:

  - A `null` from `activityScreenName` / `fragmentScreenName` never causes a mask, and now also
    **suppresses** one the structural filter would have applied. Opting a Compose-based library
    fragment out of reporting must not blank the screen beside it, whichever reason excluded it.
  - A headless fragment (`view == null`) is not a surface, and an excluded fragment nested inside one
    that *names* a screen is part of it — a `DialogFragment` excepted, since it draws its own window.
    The nesting gate asks the ancestor the same *static* question it will ask itself rather than
    reading frames already pushed: a child resumes before its parent, and reading state turned an
    embedded Compose widget's host from `NestingFragment` into `null`.
  - A child is attached from inside its host's own `performAttach`, **before** the host's attach
    callback, so a frame reserved on `onFragmentAttached` put a host's mask above the named child it
    contains. Reservation happens on `onFragmentPreAttached`; nothing later is early enough.
  - An Activity masks only while it is the sole one on display. Under multi-resume (Android 10+ split
    screen) two Activities are `RESUMED` in two windows, and an excluded one otherwise blanked the
    screen of a named Activity it does not cover.
  - What a surface *is* is settled once per mounting, not re-derived per resume. Both inputs are
    time-varying while `maskScreen` is one-way, so a plain Activity that merely paused and resumed
    with a view-bearing dialog attached was masked permanently — it kept emitting its own name while
    reporting no screen at all.
  - A stop replaces the frame rather than just clearing it. A `detach()`ed fragment stops without
    ever reaching `onDetach`, so reusing its old position put the returning fragment underneath a
    sibling that mounted while it was away.

  Residuals are documented on `installAutographNativeScreenCapture` rather than silently
  mis-attributed. Two are one-sided — a screen goes *absent*, never wrong: `show()`/`hide()` gives no
  callback at all, and a `DialogFragment` that builds its content in `onCreateDialog()` has a null
  `view` and is indistinguishable from a worker fragment. (Three more that the selection rebuild
  left — an excluded *sibling* fragment or one added straight into a *capturable Activity* masking
  the screen beside it, and an opted-out surface lending its taps the name of the paused screen it
  covers — are closed by scoping a mask to the surface's own events; see below.)

- **What a surface says now reaches its own events and no others** ([#216]). A mask raised by an
  unnamed fragment, or the screen a fragment names, used to apply to every tap captured while that
  frame was innermost — so an embedded mini-player (an excluded fragment added as a sibling, or
  straight into a capturable Activity) blanked the screen of the taps *beside* it, an opted-out
  surface `add`ed over a paused screen lent that screen's name to its own taps (wrong, not absent),
  and a `ViewPager2` of Compose pages each declaring a `TrackedScreen` attributed a tap on one page
  to whichever page's frame was pushed last. Each surface's frame is now nested under the surface
  containing it and each surface's root view is claimed with its frame; a native tap resolves from
  the surface the tapped view belongs to and a Compose tap from the surface hosting its
  composition, through `ScopeStack.current(origin)`. The mini-player's mask reaches the taps inside
  it and leaves the Activity's own taps naming the Activity; the opted-out surface's taps carry the
  screen of what *contains* it, never the sibling it covers; a page's taps carry that page. The
  ambient `ScopeStack.current()` snapshot still shows the innermost frame — it has no event to go
  by — so what the snapshot shows is no longer what a captured tap carries. Every case is pinned on
  the tap payload the tracker receives, on both pipelines (`AndroidTapOriginTest`, and
  `ComposeTapOriginTest` in `sample-android`, which drives real `MotionEvent`s through a real
  `AutographProvider` composition — the previous `ComposeHostMaskTest` dropped `track` and could not
  see any of this).

  A mounting ends when a fragment's **view** is destroyed, not when it stops: a stop destroys nothing,
  so re-reserving there jumped the frame above the child frames and Compose compositions that outlive
  it — pressing home and returning turned a nested host's child screen into a masked null. An Activity
  never replaces its frame — its content view lives create-to-destroy — but what that frame *says* is
  re-derived at every stop, or one that has since become a fragment shell goes on emitting its own
  name on every foreground return. A `DialogFragment` no longer counts towards "is this Activity a
  shell": it covers the Activity rather than filling it, and counting it made an Activity that merely
  had a sheet up at its first resume report no screen at all for the rest of its life.

  A surface the capture only meets after installing is adopted rather than ignored — an Activity at
  its next resume, and the fragments already attached to it outermost-first. Gating on
  `onActivityCreated` / `onFragmentPreAttached` made such a surface invisible for its whole life while
  its host masked over it, and adopting the fragments lazily at resume instead inverted the nesting,
  because a child resumes inside its parent's `performResume`.

- **The ambient screen of a natively-named surface is now absent while its host Activity is
  paused** — a permission prompt, a translucent Activity — where it previously kept naming the paused
  screen. Autocapture reads this stack to answer "what was on display when the user acted", and
  nothing of this app is. No `Screen Viewed` changes: a pause still does not end a view, so returning
  stays silent. *Natively-named* is the scope, measured on a device: a screen the surface declares
  through `fragmentScreenName` / `activityScreenName` goes absent, while a `TrackedScreen` declared
  in a composition inside that surface still names the ambient screen — the active bit does not
  propagate to nested frames, by design. Events autograph captures from an origin
  (`ScopeStack.current(origin)`) are unaffected either way; what sees the difference is a
  `ScopeStack.current()` read — a host app enriching its own events, or a Compose tap under no
  claimed surface (a `Dialog` window), which resolves ambiently. Whether a composition's frames
  should follow its lifecycle instead is [#228].

## [0.8.0] - 2026-08-21

### Changed

- **The Kotlin floor is now 2.3, down from 2.4.10** ([#205]) — any `2.3.x` consumer, though the
  library itself is built with 2.3.21. KSP has no Kotlin 2.4 release, and
  the Kotlin plugin version is project-wide — so the old floor locked out every consumer that needs
  KSP and targets iOS (Room on KMP among them) with no version combination that could satisfy both.
  A klib carries its producing compiler's ABI version, so 0.7.0's iOS artifacts are rejected outright
  by a 2.3 toolchain: *"Skipping … having incompatible ABI version '2.4.0'"*. Consumers already on
  2.4 are unaffected — the compatibility runs the other way.

  The only thing that required 2.4 was one stdlib call, at two sites. `autograph-core` now generates
  UUIDv7 itself (`UuidV7Generator`, internal — no public API changed, and the `api/` dumps are
  unchanged). **The `event_id` format and its ordering guarantee are the same**: still UUIDv7 per
  RFC 9562, still sorting in generation order. Ids minted inside a single millisecond are ordered by
  a monotonic counter in `rand_a` (RFC 9562 §6.2 method 3) rather than by chance, and randomness
  still comes from the same cryptographically-strong source that backs `EventId.UuidV4`.

  Compose Multiplatform stays at 1.11.1 — it requires only Kotlin 2.3 — so nothing about the iOS
  accessibility bridging or the impression path moves with this.

  **One thing does move that is not source-level: `Autograph.xcframework`'s deployment target.**
  Nothing in the Gradle build sets one, so it is Kotlin/Native's default — `minos 15.0` under
  2.4.10, `minos 14.0` under 2.3.21. The Swift package keeps declaring `.iOS(.v15)`: over-declaring
  turns away a consumer the binary would have run, which is the harmless direction, and lowering it
  needs the iOS-15-API-without-`@available` sites in `AutographUI` audited first. Nothing to do for
  consumers on iOS 15+; iOS 14 support is a follow-up decision, not a side effect of this change.

- **The Android `compileSdk` floor is now 35, down from 37, and jetbrains `lifecycle` drops to
  2.9.6** ([#205]). The 37 requirement was never Compose Multiplatform's — CMP 1.11.1's own metadata
  asks for lifecycle 2.9.6, and it was Autograph's explicit 2.11.0 pin whose AAR metadata forced 37
  on every consumer. Since `compileSdk` 36 also needs a newer AGP than 35 does, this reaches the
  projects most likely to be pinned to an older toolchain — the ones blocked by an upstream release
  they do not control, which is what [#205] is about. Compose Multiplatform stays at 1.11.1.

  `sample-android` now compiles against its own `android-sampleCompileSdk` (36, which its
  `androidx.activity` dependency demands), so a demo app's dependencies can no longer raise the
  published floor. `sample-shared` stays on the library floor, where it doubles as a build-level
  check that a Compose consumer can compile at 35.

## [0.7.0] - 2026-08-16

### Added

- **Tap autocapture for Android View / XML content** ([#63]), via
  `installAutographNativeTapCapture` in `autograph-android`. Opt-in, like every other autocapture
  surface, and it closes the last asymmetry in the support table: a hybrid Android app now gets
  `Element Clicked` for its non-Compose screens, not only `Screen Viewed`.

  **It is not a hit test, and that is the whole design.** Touch dispatch already marks the view it
  delivered to as pressed, so the target is the *root of the pressed subtree* rather than whatever a
  geometric walk finds under the finger. Measured against such a walk on the same taps, on a device:
  the walk named the wrong sibling of an elevation-reordered overlapping pair (Android dispatches by
  Z, not by child order), named a **disabled** button no click ever reached, and named an element at
  the end of a **scroll** — three phantom or wrong events that the pressed-state rule does not have,
  because dispatch answered all three questions before it ran. Reading the deepest pressed view
  instead of the root is also wrong, and measurably so: `ViewGroup.dispatchSetPressed` propagates the
  pressed state down into non-clickable children, so a tap on a label inside a clickable row would
  name the label.

  `target` is the view's resource entry name and never displayed text. Views with no id are skipped —
  including ids from `View.generateViewId()`, which have no resource entry at all, and platform ids
  like the `text1` shared by every `simple_list_item_1` row. It is the entry name of whatever was
  pressed, though, not proof the app author chose it: an AAR's resources are merged into the
  application package at build time, so a Material or AppCompat id cannot be told apart from one of
  yours at runtime and is reported the same way. Exclude what should not be reported with
  `View.isAutographIgnored`, below — an editable `TextView` is clickable, so a tap that merely focuses
  a password field reports its id.

  Compose content inside a View tree needs no de-duplication and gets none: Compose routes pointer
  input itself and never sets the View pressed state, so those taps resolve to nothing here and stay
  the Compose pipeline's exactly once.

  What it does not reach is listed in the README rather than left to be discovered: dialogs and
  popups (a `Spinner` dropdown, an overflow menu), multi-touch on two elements, and — the one with a
  bias rather than a hole — any click that does not travel through touch dispatch, including keyboard,
  D-pad and accessibility-service clicks. One case is a misreport rather than a hole, and is listed
  there too: a long press consumed by an `OnLongClickListener` *is* reported, as a tap on the right
  element — the element named is correct, the gesture kind is not.

- **`View.isAutographIgnored`** ([#63]), the View counterpart of Compose's
  `Modifier.autographIgnore()`: a view carrying it, and everything under it, is excluded from the tap
  autocapture above. Explicit tracking inside an excluded subtree is unaffected. Marking a *container*
  is enough — the exclusion is checked on the resolved target and its ancestors, which is exactly the
  set of marks that can apply to a tap.

  Two boundaries the word "under" does not reach, both documented in the README. A mark *below* the
  view a tap resolves to is never consulted, which makes a mark on a `ListView` **row** a no-op —
  `AbsListView` presses the list too, so a row tap resolves to the list; mark the list itself
  (`RecyclerView` rows are unaffected). And it does not cross into Compose in either direction: a
  `ComposeView` inside a marked subtree is served by `autograph-compose`, which reads semantics and
  not View tags, so a region spanning both pipelines must be marked on both.

  A settable property rather than a one-way mark, because `RecyclerView` recycles views: a holder
  binding a mixed list has to be able to take the mark back, or the exclusion leaks onto whatever row
  inherited the view. A tap that *resolves* to an excluded view is not counted as a failure to
  resolve, so it does not spend the one-shot "a tap resolved to nothing" diagnostic — a tap inside an
  excluded region that presses nothing at all still can, being indistinguishable from any other
  unresolved tap. The marker is a view tag keyed by a resource id this library declares, so
  `View.getTag()` and other libraries' tags are untouched; ids merge by name, so an app declaring its
  own `autograph_ignore` shares the key.

  **Packaging note:** `autograph-android` now ships Android resources (that one id, and the generated
  `R` class) where every release through 0.6.0 shipped none. Nothing to do on upgrade, but a build
  that gates on resource merging or artifact contents will see the change.

### Fixed

- **Calling `ScopeStack.push` from Swift no longer kills the process** ([#193]). It was the
  framework-independent way for a native surface to contribute scope and screen frames, and it was
  unusable: the call compiled, then the app died at runtime — with an **empty** dictionary too.

  The cause is not what the issue guessed. Kotlin/Native converts a Swift dictionary to
  `NSDictionaryAsKMap`, a runtime wrapper implementing `kotlin.collections.Map`, and passes it into a
  parameter declared as a Map **subtype** — `JsonObject` — with no check whatsoever. Measured
  directly, by a probe typed `Any?`: what arrives is non-null, `isMap=true`, `isJsonObject=false`.
  The generated header still renders the parameter as a plain `NSDictionary`, because by supertype it
  is one, so nothing about the API looks wrong from Swift. Reading that object through its declared
  type then reads a layout it does not have and the process dies with **SIGSEGV — not a Kotlin
  exception**, which is why no `Uncaught Kotlin exception:` line, no log entry and no crash report
  ever appeared, and why the issue could not capture one.

  So this was a type-safety hole, not a single broken function. `AmbientContext.enrich` took the same
  parameter type and survived **by accident**: it short-circuits on its own empty scope and returns
  the argument untouched, never reading it through the declared type. `push` stored it and
  `resolveScope` dereferenced it. A call that happened to work was no evidence the API was sound.

### Changed

- **Every public API that accepted a `JsonObject` now declares `Map<String, JsonElement>`** —
  `Tracker.track` / `screen` / `identify`, `Transport.track` / `screen` / `identify`,
  `EventValidator.validate`, `ScopeStack.push` / `update`, and `AmbientContext.enrich` — with a new
  `Map<String, JsonElement>.asJsonObject()` doing the one conversion at each entry point.

  Declaring the interface rather than a subtype of it is what makes the bridge convert correctly, in
  **both** directions, so the fix covers the Swift-implements-the-interface direction as well as the
  Swift-calls-it one. It is also self-enforcing: with the parameter typed `Map`, a body cannot reach
  a `JsonObject`-only operation without an explicit conversion, so the unsound path can no longer be
  written by accident.

  **Kotlin call sites are unaffected at the source level** — `JsonObject` *is* a
  `Map<String, JsonElement>`, so every existing call compiles and behaves identically. It is still
  a **binary** break: the descriptor changes from `…JsonObject;…` to `…java/util/Map;…`, so a
  prebuilt library compiled against 0.6.0 that calls `Tracker.track` fails with `NoSuchMethodError`
  (and `IrLinkageError` on Kotlin/Native) until it is recompiled. **Kotlin implementers of
  `Tracker`, `Transport` or `EventValidator` must widen their overrides** to match.

  There is no deprecation window. [ADR 0001](docs/adr/0001-public-api-evolution.md) governs how the
  public API may evolve *after 1.0* — its ABI guarantee for `autograph-core`/`autograph-context`
  binds from that release onward, and it deliberately leaves signature changes before then
  unconstrained ("the window to decide … is before 1.0"). This is such a change, shipped in a 0.x
  release.

  This does not make non-empty properties expressible from Swift — `JsonElement` still has no
  Objective-C initializer, so a Swift caller can still only pass an empty dictionary. That is the
  separate gap `AutographElementCapture` works around, and it is tracked by [#94].

## [0.6.0] - 2026-08-15

### Added

- **Explicit click instrumentation for SwiftUI**: `AutographButton` and `autograph.track(…)` in the
  `AutographUI` product, over a new Kotlin `AutographElementCapture`. SwiftUI elements are not
  autocaptured and cannot be ([#191]), so this is not a fallback there — it is the whole story.

  Swift could not attach properties to an event at all before this. `Tracker.track` takes a
  `JsonObject`, and the umbrella exports core/context/uikit/segment but not
  kotlinx-serialization-json, so `JsonPrimitive` has no Objective-C counterpart and `JsonElement`
  arrives as an opaque class with no initializer: a Swift caller could pass an empty dictionary and
  nothing else. `AutographElementCapture` converts on the Kotlin side from values Swift can express —
  `[String: String]`, or a JSON object literal for numbers, booleans and nesting. Exporting the
  serialization library instead would have put a third party's whole surface into this SDK's public
  Objective-C API.

  **Two entry points, chosen by one question: can you edit the component's API?** `AutographButton`
  where you can, `autograph.track` inside the handler where you cannot (or for a `Button` initializer
  it does not mirror). Prefer the first: nothing called by hand can prove its caller was a real
  interaction — reached from a timer or a completion block, `track` records a click nobody made, the
  exact phantom event two rejected designs produced. Inside `AutographButton` the recording lives in
  the button's own action, and record-then-act is fixed by the type rather than by the call site.

  Three of its properties are pinned only by UI tests driving **real touches** (`SwiftUIExplicitUITests`),
  because no unit test can reach them: a `.disabled` button records nothing, the recording precedes the
  action, and a touch fires it at all. Activating a button through accessibility is unavailable in a
  headless process — measured; such a test always skips, and a skipping test grants false confidence,
  so none was written. The sample app therefore links the shipped `AutographUI` product itself, rather
  than a shape-identical copy driven through a facade the way `.autographScreen`'s sample does: that
  modifier is glue over a Kotlin facade, while `AutographButton` has no Kotlin half, so a copy would
  verify only the copy.

  Scope is **passed in per call rather than read from `ScopeStack`**, which is the load-bearing
  decision. `resolveScope()` drops sibling frames that are neither's ancestor, because an autocaptured
  tap carries no evidence of which sibling it hit — and a list whose rows each own a scope is exactly
  that shape. An explicit call site knows precisely which row it is, so `.autographScope` accumulates
  lexically through the SwiftUI environment (the analogue of Compose's `ScopedTracker`) and hands the
  result over. Screen and section are still read from the stack: at most one screen is current, so the
  ambiguity that forces the drop cannot arise, and the stack is the only place a SwiftUI screen name
  lives. `.autographScope` reaches **explicit** instrumentation only — not autocapture of UIKit hosted
  below it in a `UIViewRepresentable`, which resolves from the stack, where nothing is pushed.

  `AutographButton` mirrors a free-form label or a title and nothing else. `systemImage`, `role:` and
  `LocalizedStringResource` each arrived in a different iOS version, so mirroring them means an
  availability-gated overload per convenience, growing with every SDK release, for sugar the caller can
  already express — and holding that line is what leaves this API with no `@available` annotation of
  its own. (The effective floor is **iOS 15**, set by `Autograph.xcframework`'s `minos 15.0`, not by
  this API.) Modifiers applied from outside reach the underlying `Button` normally, and it composes
  inside `.alert`,
  `.confirmationDialog`, `Menu`, `.contextMenu`, `.swipeActions` and `.toolbar` exactly as a literal
  `Button` does; swapping the `Button` inside a design-system button of your own is appearance-neutral
  down to a custom `ButtonStyle`'s `isPressed`. All measured across seven container kinds and eight
  styles, not assumed.

  A missing `.autographElementCapture(_:)` is loud — it traps in debug and logs a fault in release —
  rather than silently dropping every event.

  A decorator form (`Button { … }.tracked("event")`) was built and **removed** before release: it
  cannot prevent the phantom event that motivates `AutographButton`, so it added a third way to do the
  same thing with none of the guarantee.

- `AutographElementScope { … }` attaches per-element properties to autocaptured taps on **both**
  Android and iOS ([#185]). The iOS half was declared impossible and [#68] was closed on it: the
  bridged accessibility tree was measured flat, leaving no ancestry for a scope to ride, and the
  three things Compose Multiplatform would have had to grow were enumerated. It grew none of them.
  Compose Multiplatform 1.11 instead began publishing a traversal group as its own accessibility
  element — a fourth route that enumeration did not anticipate — so a wrapper is now a real ancestor
  of the clickable on the bridged tree, and the scope travels in its accessibility identifier, the
  one bridged property that is both identity-bearing and never spoken aloud. Sibling rows attribute
  exactly, which is the case #68 was closed for being unable to do.

  The scope is read off the **same hit path** the identifier came from, so a misattributed tap
  carries the misattributed element's own scope rather than a mismatched pair. That is the property
  every geometric design lacked, and why none of them shipped: two exactly coincident clickables
  cannot be told apart by their rectangles, and the failure would land precisely when the hit test
  got the target right.

  On iOS this changes the host app's accessibility structure, and the change was measured rather
  than assumed — but measured on the *bridged hierarchy*, not by running a screen reader, and the
  distinction is worth stating. Reading order, stop count and spoken labels come out unchanged across
  four controlled A/Bs against identical unwrapped content, including a multi-child row and two
  sibling rows, which matches the mechanism: `clickable` merges its subtree into a single stop and the
  published container never takes focus. What is measured outright is the container type — the
  wrapper publishes `UIAccessibilityContainerTypeSemanticGroup` where the unwrapped control publishes
  none — so with the rotor set to Containers a scoped element becomes one more navigation target.
  That much is additive: nothing hidden, no stop lost, no order moved. **Not checked on a device:**
  whether VoiceOver announces the group's boundary on entry or exit. Being a change an analytics
  library makes to your app, it is opt-in per call site and stated in the API's own documentation. The
  scope's keys and values are also readable by any accessibility client, so treat them as you would a
  `testTag`.

  Requires Compose Multiplatform 1.11 or newer for the iOS half; older versions bridge the wrapper as
  a flat sibling and the scope is silently absent.

  The wrapper is a `Box` that propagates its constraints, minimums included, so it is layout-neutral —
  with one exception it cannot be: modifiers that are parent data for the *enclosing* layout do not
  cross it. `Modifier.weight` and `alignByBaseline` stop compiling when their element moves inside;
  `align` and `matchParentSize` are `BoxScope` members and rebind to the wrapper. Documented at the API
  and in the README migration note.

  On iOS a scope whose encoded form exceeds 2048 characters is dropped whole — it is parsed on the
  main thread inside a tap handler, so its size is a latency budget — and dropping it degrades to
  exactly what no wrapper does. Crossing the ceiling prints a one-line console diagnostic naming the
  size, once per process, because the drop is otherwise indistinguishable from a correctly configured
  app until someone reads the events. The number is a guard against the pathological, not a measured
  budget. The same ceiling is applied when *reading*, which is the side that has to hold: the reserved
  prefix sits in an identifier slot the host app owns, and the walk reaches UIKit interop subtrees, so
  a node this library did not write can carry a payload of any size and would otherwise be parsed on
  the main thread on every tap and folded into the event. An empty scope publishes no marker at all,
  for the same reason an oversized one is dropped whole — a container with no readable scope is this
  API's whole accessibility cost with none of its benefit.

  `autograph-compose` now declares `compose.foundation` as an `api` dependency rather than
  `implementation`: `BoxScope` is the wrapper's content receiver and so sits in the module's public
  signature, and an `implementation` dependency reaches consumers at runtime only.

  `autograph-uikit`'s `deepestAccessibilityHitPath` gained a parameter, so its klib signature is
  **replaced rather than extended** — the five-argument form is gone from the dump. It is
  `@AutographInternalApi` and both modules are released together from this repo, so the only way to
  reach it is a graph that resolves `autograph-compose` and `autograph-uikit` at *different* versions
  (adding `autograph-uikit` directly for the native tap pipeline, at a newer version than the compose
  artifact pulls in). That fails at runtime with `IrLinkageError` on the first tap, not at link time —
  the shape this repo measured for mixed-version klib diamonds. **Keep the two modules on the same
  version.** The previous release changed this signature the same way, silently.

### Changed

- **The Swift package now declares the iOS floor it actually has: 15.0, not 13.0** ([#195]). Every
  Swift product links `Autograph.xcframework`, which Kotlin/Native builds at `minos 15.0` — its
  default for the iOS targets, and nothing in the Gradle build sets a deployment target. The manifest
  nevertheless declared `.iOS(.v13)`, so SwiftPM would accept an iOS 13 or 14 consumer and let it link
  a binary it cannot run. Nothing enforced the declared floor, which is why the mismatch survived
  through 0.5.0 unnoticed.

  **Compatibility.** SwiftPM will now refuse to resolve this package for a consumer whose manifest
  declares an iOS deployment target below 15.0 — raise it to `.iOS(.v15)`. Such a consumer was already
  linking a binary it could not run, so nothing that worked stops working.

  Lowering the binary instead was considered and rejected. Apple's toolchain does not stand in the way
  — Xcode 26.3's SDK compiles a `-target arm64-apple-ios13.0` Swift module without so much as a
  warning, measured — but Kotlin/Native offers no supported way to set an iOS deployment target, only
  the internal `-Xoverride-konan-properties`; iOS 13 and 14 devices are essentially absent by now, and
  a compiler-internal override is a poor trade for them.

  The raised floor also removes the availability plumbing the false floor required: the four
  `@available(iOS 14.0, *)` annotations on `.autographScreen` and its modifier, and the
  `#available(iOS 14.0, *)` gate that kept `Logger` reachable in `autograph.track`'s missing-capture
  diagnostic. No Swift API in this package now carries an `@available` version annotation of its own,
  and the README's Requirements section states the iOS minimum for the first time.

- **iOS native tap capture no longer covers SwiftUI at all, and the accessibility resolver behind it
  is removed** ([#191]). Since [#189] a `hitTest` resolver has handled UIKit without touching
  accessibility state; behind it a second resolver walked the accessibility element tree, which UIKit
  and SwiftUI build only once an accessibility client has run in the process. With UIKit already
  covered, that fallback served exactly one remaining population — **SwiftUI in a process where
  VoiceOver, Voice Control, the Accessibility Inspector or an XCUITest runner happened to be running.**

  That is not partial capture, it is biased capture: the taps it recorded were the ones made by
  assistive-technology users and by test automation, and downstream its silence is indistinguishable
  from nobody having tapped. It also made a simulator you had run UI tests against look reliable while
  the same build dropped every SwiftUI tap on a user's device. Analytics quietly conditioned on a
  user's assistive technology is worse to ship than none, so it was removed rather than kept for the
  population it happened to serve. **Instrument SwiftUI surfaces explicitly.**

  Measured before removing, on a fresh simulator and on a warmed one, driving `hitTest` over a real
  `UITableView`, a real `UICollectionView`, a SwiftUI `List`, `Form`, `Picker` and a plain `Button`:
  no SwiftUI `.accessibilityIdentifier` appears anywhere in the view hierarchy, cold **or** warm. The
  cold half was already known; the warm half is what confirms nothing is lost on the surviving path.

  Two other things go with it. The container misattribution tracked as [#191]'s first item cannot
  happen any more — it was the accessibility walk that let a UIView-backed SwiftUI container claim a
  tap on its own contents. And the one-per-process console warning stopped asking whether the
  accessibility tree is cold, since that no longer decides anything: it now fires on the first tap
  that resolves to nothing, whatever the reason, and says what to do about it.

  What is lost beyond SwiftUI is warm-only too: a `UITableViewCell` or `UICollectionViewCell` whose
  selection is its delegate's, and `.onTapGesture` on a `Text`. Both resolved only on a warm tree
  before, and both are listed as known gaps in the README.

  API: `resolveNativeTapTarget` (`@AutographInternalApi`) keeps its name and its job — resolve a tap
  to the identifier that would be reported — but now answers through `hitTest`, so it takes the tap's
  position in **points as well as pixels** and no longer takes a scale. It exists for `AutographUI`,
  a separate Swift package whose `.autographIgnore()` contract can only be checked by asking what the
  pipeline would report. The accessibility walk itself (`deepestAccessibilityHitPath` and friends) is
  untouched — `autograph-compose`'s iOS resolver uses it, and Compose taps resolve in a cold process,
  so Compose autocapture is unaffected on both platforms.

- `Modifier.autocaptureScope` is deprecated in favour of `AutographElementScope`, and is removed at
  1.0 ([#185]). It is not fixable in place: its documented canonical usage puts the scope on the
  clickable's own chain, and iOS needs it on a layout node of its own, since two `testTag`s on one
  node collapse to the first and the element would lose either its scope or its name. A wrapper
  cannot be written that way. On Android the replacement applies the very same modifier node, so
  switching changes nothing there, and the modifier keeps behaving exactly as before until removal —
  including contributing nothing at all on iOS.

### Fixed

- **iOS native tap capture now works on UIKit in a cold process** ([#189]). Native taps resolved
  through the accessibility element tree, which UIKit and SwiftUI build only once an accessibility
  client has asked for it — so until VoiceOver, Voice Control, the Accessibility Inspector or an
  XCUITest runner had run, every native tap was dropped for the life of the process ([#135]). That was
  known from a freshly created simulator; whether real hardware behaved the same was recorded as
  unmeasured while the README already warned users it did. It has now been measured on a physical iPad
  Pro 11" (3rd gen), rebooted, unplugged and launched from the home screen: 0 of 15 views carried a
  non-zero accessibility frame and none carried any trait, and turning on a single accessibility
  client took the *same 15 views* to 15 of 15. A device is cold exactly like a fresh simulator.

  UIKit taps are now resolved first by `UIView.hitTest`, which never consults accessibility. In that
  same cold process the tap position returned the real `UIButton` **carrying its
  `accessibilityIdentifier`** — the exact string reported as the event's `target`. A `UIControl`, or
  any view with an enabled tap gesture recognizer, is therefore identified cold. (At the time, the
  accessibility resolver remained behind it as a second path covering SwiftUI on a warm tree; the entry
  above supersedes that — [#191] removed it before either change shipped.)

  Two things improve on UIKit as a side effect, because `hitTest` *is* the answer to which view
  receives a touch rather than an approximation of it: the overlap and z-order ambiguity documented
  against the accessibility walk ([#140]) does not arise, and `isUserInteractionEnabled`, `isHidden`
  and `alpha` are honoured — none of which the accessibility tree carries. `UIControl` target-action
  taps, previously listed as a gap on [#86], resolve for the same reason.

  **SwiftUI is unchanged and cannot be fixed this way.** Measured on the same device: under a SwiftUI
  button the cold `hitTest` returns a `PlatformGroupContainer` with a nil identifier and nil label, and
  the `.accessibilityIdentifier(_:)` set on that button appears nowhere in the view hierarchy — SwiftUI
  creates no per-element backing view, so the accessibility tree is its only enumeration and that tree
  is the thing that needs a client. The README's capture matrix and warning are now split accordingly.
  What is deliberately *not* claimed is the frequency: how large a share of real launches stay cold is
  unmeasured, and the mechanism being confirmed on hardware does not license a number.

  A `UIScrollView` — and so `UITableView`, `UICollectionView`, `UITextView`, and the backing view of a
  SwiftUI `List` or `ScrollView` — is never reported as the tapped element, and it **stops** the search
  for one rather than being stepped over. Its tap recognizers are UIKit's own scrolling and selection
  plumbing, and SwiftUI puts a `List`'s `.accessibilityIdentifier` on that backing view while the rows
  have no backing view at all, so treating one as interactive made the container claim its own content's
  taps. Caught by the sample app's XCUITest suite, which reported a `List` in place of the row inside it.
  Merely excluding the scroll view was measured to be half a fix: the search then carried on to the next
  interactive ancestor and let *that* shadow the content instead — a screen container with an
  `accessibilityIdentifier` and a tap-to-dismiss-keyboard recognizer, which is an ordinary UIKit shape,
  claimed every tap on every row.

  Only an element carrying an `accessibilityIdentifier` is ever reported, so an untagged control still
  falls through to the accessibility route and is still dropped cold. The flip side of resolving any
  `UIControl` is that taps which merely focus a `UITextField` now produce `Element Clicked`; use
  `registerAutographIgnoredView` on fields that should stay out of the stream.

  A view a developer excluded with `registerAutographIgnoredView` vetoes a tap on this route even when
  it cannot receive touches itself — the default for `UILabel` and `UIImageView`, and so the shape the
  opt-out is most often reached for. `hitTest` steps over such a view, which would otherwise have let
  the new route report a tap the accessibility route correctly dropped. The exclusion is checked against
  what is *drawn* under the tap, by two walks: `hitTest`'s own frontmost-branch descent with only the
  "declines touches" gate removed, which catches an excluded view drawn *over* a sibling, plus a sweep
  of the reported element's own subtree, which catches one sitting behind a transparent front sibling or
  drawn outside its parent's bounds. An excluded view *behind* whatever the user actually tapped, in
  another branch entirely, correctly does not veto. Note the corollary: registering a full-screen
  container silences native capture for every tap inside it, so register the content, not the chrome.

  One documented UIKit behaviour turned out to be wrong and is corrected here. A disabled `UIControl`
  does not "still get returned by `hitTest` while suppressing its own action" — measured for a bare
  `UIControl`, a `UISwitch` and a `UIButton` alike, it is declined by hit-testing entirely, subtree
  included, and the touch passes through to whatever is drawn behind it. So a tap near a disabled
  control now names the thing that actually received it, which on UIKit is the opposite of what
  [#128] measured for Compose, where a disabled clickable swallows the tap and fires nothing.

- An iOS tap on a `clickable` drawn outside its scope wrapper's own bounds — an overhanging badge, an
  `offset` decoration — was dropped entirely rather than reported ([#185]). The accessibility walk
  gates descent on geometric containment at every node below its starting one, so a container whose
  own frame misses the tap prunes before its child is reached; Compose Multiplatform 1.11 publishing
  traversal groups as containers made that latent gap newly reachable. Measured with an oracle: the
  element's `onClick` fired and no event followed. The exemption is the narrowest one that fixes it —
  an element carrying the reserved scope prefix, never a clickable one, and only on the Compose
  pipeline. Left global it could have let a branch resolve around the Compose host, which is how the
  two iOS pipelines avoid reporting one tap twice and is a privacy boundary rather than an
  attribution detail.

## [0.5.0] - 2026-08-11

### Added

- The release version gate moved into `.github/scripts/validate-release-version.sh`, gained a test
  suite that runs on every PR, and gained a dry-run workflow that calls it ([#167]). `cd.yml` runs
  only on a `v*` tag, so every guard it carries would otherwise first execute during a release that
  cannot be undone — the checks now execute on each PR instead, against a stubbed `gh` so that the
  API-failure branch is exercised rather than simulated. Each case asserts *why* a version was
  rejected and not merely that it was, since every rejection path exits non-zero and a status-only
  assertion would be satisfied by the script failing for the wrong reason. The suite was confirmed
  to fail when the fail-open form of the tag lookup is reintroduced, and when the leading-`v` check
  is removed, so a green result means something. It runs on both Linux and macOS: the gate only ever
  executes on macOS, where `grep`, `sort` and bash all differ from the GNU versions a contributor is
  likely to be writing against.

  `release-dry-run.yml` (`workflow_dispatch`, version input) runs that same script — not a copy —
  then publishes every module to a local repository, builds the xcframework and computes a
  checksum, and stops. It holds no publishing credential — no Maven Central login and no release
  signing key — does not use the `release` environment, and uploads nothing; its only credential is
  the automatic `github.token` at `contents: read`, which is what lets the validation list existing
  tags. The checksum it prints belongs to that build alone and is not the one a release would carry,
  since Kotlin/Native output is not reproducible.
  ADR 0002 lists a dry-run path as a prerequisite for the trigger redesign; this is the half of it
  that does not depend on which trigger wins.

  The local publish exists because `cd.yml` publishes to Maven Central as its *third* step, so a
  Gradle change breaking the POM, the signing wiring or the publication config would otherwise first
  surface mid-release, inside the run that cannot be undone ([#176]). Signing is not optional:
  measured on this tree, a keyless publish assembles every artifact and then fails at
  `signJvmPublication` with "no configured signatory", and skipping the `sign*Publication` tasks
  fails too because the publications declare the `.asc` files as artifacts. So the job generates a
  throwaway GPG key, which adds no repository secret and no publish target — the key lives for one
  run and its public half never reaches a keyserver, so Central could not verify a signature made
  with it even if something uploaded one. The run also fails if a `swiftpm-metadata`
  artifact reaches the publication: Maven Central rejects those and a local repository accepts them
  silently, so that break is invisible without an explicit check.

  It closes most of that gap rather than all of it, and the remainder is named in the workflow:
  `publishToMavenLocal` runs the same publications as `publishToMavenCentral` but not
  `prepareMavenCentralPublishing` or the upload tasks, the throwaway key exercises neither the
  key-id nor the passphrase path the release secrets use, and Central's own POM-field validation
  still runs for the first time during a release ([#176]).

  The `release` environment now also requires a review before a publish can proceed, with
  self-review permitted because the project has a single maintainer.

  The release-notes start tag is derived by `.github/scripts/derive-release-notes-start-tag.sh`,
  extracted from `cd.yml` for the same reason and tested the same way ([#174]). Inline it had the
  fail-open shape the version gate just lost: `set -e` without `pipefail` left a failed `gh api`
  exiting 0 with an empty result, which the caller read as "there is no previous release" and
  answered with the start-tag-less `--generate-notes` that [#166] removed — so an API blip would
  quietly reinstate that bug. An empty answer and a failed one are now distinguishable, and the
  release stops rather than mislabelling its notes — but it stops *before* the publish, not after
  it. The derivation runs in its own step alongside the version gate, because a fail-closed check
  placed after `publishToMavenCentral` would abort between an irreversible publish and
  `gh release upload`, leaving the tag's rewritten `Package.swift` pointing at an asset that was
  never uploaded. Extracting it put the derivation under test for the first time; it has never run
  during a release, having landed after v0.4.0 shipped. Every assertion was confirmed to fail
  against the code it replaces — among them the fail-open lookup, `--paginate` dying with earlier
  pages already read, a mistyped or unpaginated API request, the walk that answered with the highest
  existing tag whenever `gh` replied from a cache older than the tag being released, and an early
  `awk` exit that made a large tag list return the right answer with a SIGPIPE exit status.

- iOS native tap capture warns once via `NSLog` when it drops a tap because the accessibility tree is
  cold ([#170]). `installAutographNativeTapCapture` resolves nothing until some accessibility client
  (VoiceOver, Voice Control, the Accessibility Inspector, an XCUITest runner) has run in the process —
  a platform limit with no fix available from public API — and until now the drop was indistinguishable
  from "nothing was tapped". The first native tap that resolves to nothing now checks whether the whole
  tree still carries the measured cold signature (every node a zero `accessibilityFrame`, no traits) and,
  if so, names the limitation in the console once per process. An ordinary tap on empty background, on a
  warm tree, stays silent: the question is asked of the tree, not of the tap's position.

  Compose-host subtrees are excluded from that question, which is what makes it correct in a hybrid app.
  The same walk activates Compose Multiplatform's accessibility bridge on demand, so a
  `ComposeUIViewController` publishes real frames in a process whose UIKit/SwiftUI half is stone cold —
  counting them would answer "warm" and suppress the warning in exactly the app that needs it.

  The check is fail-closed on a signature measured against one app's cold tree, so an app that
  hand-publishes accessibility elements can still read as warm and never see the line. It is a
  diagnostic, not a guarantee: taps you cannot afford to lose still need explicit instrumentation.

- The release workflow validates the pushed tag before publishing anything ([#167]). Publishing runs
  with `automaticRelease = true` and therefore has no staging repository to inspect or drop, so the
  tag push was already irreversible with nothing checking it. A tag now has to be `vMAJOR.MINOR.PATCH`
  with no leading zeros, have a matching `## [X.Y.Z] - <date>` section in this file, and sort above
  every existing release tag. The middle one catches the transposition a regex cannot — `v0.41.0`
  for `v0.4.1` is well-formed, and only the changelog knows which was meant. A prerelease tag such
  as `v0.5.0-rc1` is rejected by the first: the trigger is `v*`, so pushing one starts the workflow
  and it stops there.

- The release workflow is serialized with `concurrency: release` ([#167]), following `docs.yml`
  (`ci.yml` also has a concurrency block, but a per-ref superseding one rather than a serializing
  one). Two tags pushed close together could otherwise run two publishes at once.
  `cancel-in-progress` stays false because an in-flight run may already have published to Maven
  Central, and `queue: max` is set alongside it because that flag protects only the *running*
  release: the default `queue: single` holds one pending run and cancels it when another arrives,
  so a third tag would have silently dropped the second release entirely.

- [ADR 0002](docs/adr/0002-release-trigger-and-tag-creation.md) records why the release tag is
  force-pushed to a new commit today, what that costs, and the two candidate redesigns ([#167]). The
  trigger choice is left open on purpose. Documented rather than fixed for now: a consumer resolving
  the package in the window between the tag push and the force-push gets an *older* release's binary
  with no error, because `Package.swift` interpolates `releaseVersion` into the download URL, so the
  stale version and stale checksum agree with each other. Measured at each tag's parent commit, that
  window served `v0.1.0` for `v0.1.1`, `v0.2.0` and `v0.3.0` alike, and `v0.3.0` for `v0.4.0`.

### Fixed

- iOS autocapture no longer decides "this element is already instrumented" by comparing rectangles.
  It observes whether `Modifier.trackClick`'s handler actually *ran* during the pointer dispatch
  being resolved, and suppresses that dispatch's `Element Clicked` when it did ([#179]). The public
  contract is unchanged on both platforms, and Android is untouched — it reads the marker off the
  semantics ancestry, which is stronger evidence than timing.

  The rectangle comparison could not be made correct. Compose expands a small element's touch target
  — and the accessibility frame it publishes — toward the minimum, but **clamps that expansion
  against neighbouring layout**, so the published frame is not a function of the element's own
  bounds. Measured on device, a sub-48dp `trackClick` in an unspaced `Column` registered a 72px-tall
  rect and published a 108px one: grown 36px upward, not at all downward, its bottom edge exactly the
  next element's top. Every rule tried over those rectangles picked one of two failures — leave the
  element double-reported, or match a merely-similar enclosing container too and drop *its* tap with
  no event at all. The decisive shape is a `fillMaxWidth` `trackClick` `Text` at the top of a
  `fillMaxWidth`, 48dp, uninstrumented `clickable`: the two publish frames of the same size that both
  contain the inner's bounds, differing only in where the clamp landed, so no comparison can tell
  which owns a tap. Execution needs no comparison — a tap on the host's exposed strip runs no
  `trackClick`. #151, #153, #158 and #159 were all bugs in that same apparatus, and it is now gone,
  along with the per-element rect, the recovered draw scale, and the minimum-touch-target
  reconciliation.

  The ways this breaks fall toward a duplicate rather than a dropped event, which is the property
  the previous design lacked: if the ordering it relies on stops holding, the mark lands outside any
  generation and does nothing, and when the observer cannot attribute a mark to the pointer it is
  reporting it discards the evidence rather than guessing. The one shape that would not degrade that
  way needs a handler running inside a *later* dispatch's window, since the mark records that a
  handler ran and not which element's; `CONTRIBUTING.md` names it as the case to treat as a
  correctness change. Two consequences worth knowing:

  - **A dispatch that consumes more than one pointer suppresses nothing**, so an instrumented element
    tapped as part of a genuine two-finger gesture reports both its explicit event and an
    `Element Clicked`. A second finger merely resting on the screen does not cause this.
  - The suppression now depends on `clickable` invoking its handler synchronously, before the tap
    observer's `Final` pass — measured on Compose Multiplatform 1.11.1, but an implementation detail
    rather than a documented guarantee. `CONTRIBUTING.md` records when to re-check it on device;
    `combinedClickable`, which delays `onClick` past a double-tap timeout, would break it outright.

### Documentation

- `CONTRIBUTING.md` now carries a concrete cold-device checklist to run before merging a
  `composeMultiplatform` bump ([#154]). The 0.4.0 notes named that check as the reason for spelling
  out the CMP dependency, but left it as tribal knowledge; it is now three runnable steps.
  The check cannot be automated in CI, and the checklist says why: `xcodebuild test` is itself an
  accessibility client, so it warms the exact state a cold launch starts without, and a bump that
  broke cold-start tap resolution would land fully green ([#135]). Two details are load-bearing
  rather than incidental — the device must be freshly *created* (`shutdown`+`boot` does not reset the
  accessibility subsystem), and log capture must start before the tap rather than after it.

- The native tap-capture kdoc records that a hybrid app's own Compose autocapture does **not** warm
  the native pipeline ([#135], measured). `autograph-compose`'s iOS resolver activates Compose
  Multiplatform's accessibility bridge on tap, but that is a *separate* on-demand activation from the
  one UIKit and SwiftUI sit behind: measured in sequence within one process, a Compose tap confirmed
  by its `Element Clicked` followed immediately by a tap on an unrelated SwiftUI element still
  resolved to nothing. So the cold-inertness is not narrowed to apps with no Compose anywhere — it
  applies to every hybrid app's native screens too, until a genuine accessibility client has run in
  the process.

- The accessibility-walk kdoc records a measured side effect and closes it as a non-issue ([#156]):
  the walk also flips Compose Multiplatform's internal "a screen reader is active" belief
  (`LocalPlatformScreenReader.current.isActive`) from `false` to `true`, where an identical tap
  through plain Compose leaves it `false`. This is what the already-documented activation call site
  implies rather than a new failure mode — that call site cannot distinguish an app from a screen
  reader, and the flag records exactly that. The flag is `@InternalComposeUiApi` with no supported
  read path from application code, so nothing downstream of Compose can observe it. Whether it
  perturbs real assistive-technology traversal order was **not** measurable, and the entry says so:
  observing AT-facing behaviour requires attaching a real AT, which sets the same flag by itself, so
  no walk-free baseline exists to compare against. What carries the non-issue classification despite
  that gap is not the absence of a measurement but a property that does not need one: the walk cannot
  manufacture a state a genuine AT visit would not already produce, so it can only make Compose
  believe that arrival happened earlier and more often than it otherwise would — a state every
  screen-reader user reaches anyway, not a new one.

## [0.4.0] - 2026-08-06

### Changed

- `Modifier.trackImpression` no longer suppresses autocapture on the element it marks ([#158]).
  It reports a **visibility** event and never a click, so there was no click of its own for
  autocapture to double-report — and marking it cost an event rather than saving one: a tappable
  element that also reported an impression fired no click event at all, on either platform. An
  element that should report neither is what `Modifier.autographIgnore()` is for. `trackClick`, which
  does fire a click, is unchanged.

  On iOS this also removes a whole class of defect rather than picking a side of it. The UIKit
  accessibility bridge carries no custom semantics key, so the resolver compared a registered rect
  against the resolved clickable's frame — and a `trackImpression` element coincident with the
  clickable enclosing it publishes that clickable's frame exactly, so the host's real tap was
  silently dropped. Measured on device (iPhone 17 Pro, iOS 26.2, CMP 1.11.1): tapping a 48dp
  `clickable` containing a `trackImpression` `Text` that fills it fired the `clickable`'s own
  `onClick` and reported nothing. Qualifying the match to save that tap would have double-reported
  the opposite shape — an element that is itself both `trackImpression` and `clickable`, with a
  descendant filling it — and the two were measured **byte-identical** from the accessibility tree
  (same clickable frame, same claim rect, one descendant publishing the same rect in each), so no
  geometry could have told them apart. Removing the claim removes the ambiguity. Android read the
  marker off the semantics ancestry and was never ambiguous, but changes behaviour the same way.

### Fixed

- Autocapture on iOS no longer double-reports a `Modifier.trackClick` element under a **scale
  transform** ([#159]). Compose qualifies the touch target on the element's *measured* size and then
  draws the result through the transform, while the claim the modifier registers is
  `boundsInWindow()` — already transformed. Deriving the plain minimum from it therefore matched
  nothing and both the explicit event and an `Element Clicked` fired. Claims now carry the scale the
  element was drawn through alongside the drawn bounds, and the minimum is scaled before the
  expansion — the identity whenever there is no transform. Measured on device: a `scale(0.5f)` `Text`
  drawn at `246x36px` from a measured `492x72px` publishes its frame at `246x72px`, the 144px minimum
  halved on the axis that needed expanding, to the pixel. That scale is read off the element's own
  corners rather than as `boundsInWindow()` over the measured size: `boundsInWindow()` is clipped, so
  the ratio of the two reports an ancestor's clip as a scale (measured: an element centred in a host
  half its height reports the same `0.5` a genuine `scale(0.5f)` does), which would have shrunk a
  clipped element's derived touch target below the plain minimum it matches on today. Android reads
  the marker off the semantics ancestry and is unaffected. Only a scale is recovered, and only an
  axis-aligned one: under a rotation the corners span the rotated diagonal, so the ratio is not the
  scale — the same axis-aligned assumption the Android hit test already documents. That costs the
  expansion alone, leaving a rotated element *below* the minimum touch target double-reporting
  exactly as it did before.

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
  single one since both entries carry the same target. The residual this left for a **scaled**
  element is fixed above ([#159]).

- Autocapture on iOS no longer drops a tap on an **uninstrumented clickable** that merely contains a
  small `Modifier.trackImpression` element ([#153]). Expanding a claim to the minimum touch target is
  not injective, so a sub-minimum instrumented element centred in a clickable exactly at the minimum
  expands onto that clickable's accessibility frame precisely — and the fix above then read the
  clickable as already-instrumented and suppressed an event nothing else reported. Measured on device:
  an inner frame of `(16, 636, 370x24)` expanding onto a host at `(16, 624, 370x48)`, to the point.
  `trackImpression` is what makes this reachable where a `trackClick` inner element cannot — it adds
  no `clickable`, so Compose leaves the tap with the enclosing element instead of routing it inward,
  which is the premise the previous entry's reassurance rested on. Claims now record which modifier
  registered them, and a `trackImpression` claim's expansion match was honoured only when no
  accessibility descendant of the resolved clickable published that claim unexpanded. Android was
  never affected, on either count. Introduced by the fix above and never released. That qualification
  is itself superseded below: it narrowed the ambiguity rather than removing it, and the residual it
  left — a `trackImpression` element *coincident* with the clickable enclosing it, which matches with
  no expansion at all — is what [#158] turned out to be.

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

- The reason given for why iOS Compose autocapture survives a cold process is corrected ([#157]).
  `AccessibilityTree.kt`, `NativeTapResolution.kt` and the README said Compose Multiplatform bridges
  its semantics "unconditionally", so the tree is there "from the first layout pass". Since CMP 1.8
  (compose-multiplatform-core#1780) it is built *on demand*, and "populated at layout" was never
  observable from application code in the first place — reading the tree is what populates it. What
  actually holds is narrower: the activation call site cannot distinguish one caller from another, so
  this library's own walk triggers it, and the two gates in front of it (whether a `focusable` layer
  sits above this scene, and the traversal itself) are neither of them tied to assistive technology. The
  measured evidence is unchanged and still load-bearing — on a freshly created simulator no
  accessibility client had touched, CMP's bridged elements carry correct frames, identifiers and
  traits while UIKit and SwiftUI supply nothing at all in the same process. Naming the dependency is
  the point: it is what makes the cold-device check on a Compose Multiplatform bump ([#154]) more
  than superstition. The shipped `0.3.0` entry below is left as released history.

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

[Unreleased]: https://github.com/uny/autograph/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/uny/autograph/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/uny/autograph/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/uny/autograph/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/uny/autograph/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/uny/autograph/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/uny/autograph/compare/v0.3.0...v0.4.0
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
[#63]: https://github.com/uny/autograph/issues/63
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
[#154]: https://github.com/uny/autograph/issues/154
[#156]: https://github.com/uny/autograph/issues/156
[#157]: https://github.com/uny/autograph/issues/157
[#158]: https://github.com/uny/autograph/issues/158
[#159]: https://github.com/uny/autograph/issues/159
[#166]: https://github.com/uny/autograph/pull/166
[#167]: https://github.com/uny/autograph/issues/167
[#170]: https://github.com/uny/autograph/issues/170
[#174]: https://github.com/uny/autograph/issues/174
[#176]: https://github.com/uny/autograph/issues/176
[#179]: https://github.com/uny/autograph/issues/179
[#185]: https://github.com/uny/autograph/issues/185
[#189]: https://github.com/uny/autograph/issues/189
[#191]: https://github.com/uny/autograph/issues/191
[#193]: https://github.com/uny/autograph/issues/193
[#195]: https://github.com/uny/autograph/issues/195
[#197]: https://github.com/uny/autograph/issues/197
[#205]: https://github.com/uny/autograph/issues/205
[#208]: https://github.com/uny/autograph/issues/208
[#216]: https://github.com/uny/autograph/issues/216
[#217]: https://github.com/uny/autograph/pull/217
[#228]: https://github.com/uny/autograph/issues/228
