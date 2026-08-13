# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. The stability each
artifact commits to before then is spelled out in [ADR 0001](docs/adr/0001-public-api-evolution.md);
the `context.instrumentation` envelope is already semver-stable (see the README).

## [Unreleased]

### Added

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

  On iOS a scope whose encoded form exceeds 2048 characters is dropped whole — it is parsed on the
  main thread inside a tap handler, so its size is a latency budget — and dropping it degrades to
  exactly what no wrapper does. Crossing the ceiling prints a one-line console diagnostic naming the
  size, once per process, because the drop is otherwise indistinguishable from a correctly configured
  app until someone reads the events. The number is a guard against the pathological, not a measured
  budget.

### Changed

- `Modifier.autocaptureScope` is deprecated in favour of `AutographElementScope`, and is removed at
  1.0 ([#185]). It is not fixable in place: its documented canonical usage puts the scope on the
  clickable's own chain, and iOS needs it on a layout node of its own, since two `testTag`s on one
  node collapse to the first and the element would lose either its scope or its name. A wrapper
  cannot be written that way. On Android the replacement applies the very same modifier node, so
  switching changes nothing there, and the modifier keeps behaving exactly as before until removal —
  including contributing nothing at all on iOS.

### Fixed

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

[Unreleased]: https://github.com/uny/autograph/compare/v0.5.0...HEAD
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
