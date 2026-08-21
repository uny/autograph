# Contributing

Thanks for considering a contribution to Autograph.

## Before you start

For anything beyond a small fix, please open an issue first to discuss the change.
This project is in early development and APIs are still moving, so it helps to align
on direction before investing time in a PR.

## Development

- Requires JDK 21 and Kotlin/Compose Multiplatform toolchains (Android SDK, Xcode for
  iOS targets). See the [Requirements](README.md#requirements) section of the README.
- Build and test: `./gradlew build`
- The `autograph-segment-swift` package has its own test suite; see
  [`AutographSegmentSwift/`](AutographSegmentSwift).

## Pull requests

- Keep PRs focused on a single change. Unrelated cleanup makes review harder.
- Add or update tests for behavior changes.
- Make sure CI passes (`./gradlew build`) before requesting review.
- Follow the existing commit style (`type: short description`, e.g. `fix: ...`,
  `feat: ...`, `chore: ...`).

## Changing public API

Every published module runs `explicitApi()`, and most also run `abiValidation()`, so a
change to the public surface shows up as a diff in that module's `api/` dump. Regenerate it
with `./gradlew :<module>:updateKotlinAbi` and verify with `:checkKotlinAbi`. Two modules
are not covered — `autograph-schema` and `autograph-android` — so a public change there
needs the rules below applied by hand; ADR 0001 §1 says why.

**An API dump change in a PR is a review checkpoint, not a formality.** Before adding
anything public, read [ADR 0001 — How the public API may evolve after 1.0](docs/adr/0001-public-api-evolution.md).
It classifies each public type by who constructs and who implements it, and the
classification determines what you are allowed to add. In short:

- New configuration goes on `AutographConfig` as a `var`, not into a config `data class`
  constructor (which cannot gain a parameter without breaking ABI).
- New members on an SPI (`Transport`, `SeqStore`, …) must carry a default body that is
  genuinely correct for an implementor who has never heard of the feature. If no such
  default exists, add a separate optional interface instead.
- `Envelope` and `SessionInfo` are constructed by the library only; their constructors are
  intentionally `internal`. Tests construct them through `testEnvelope(...)` in
  `autograph-test` — including tests that fake `EnvelopeSource`, which now need that
  dependency.
- Enum constants and sealed subtypes are frozen within a major version.

If a change does not fit those rules, say so in the PR rather than working around them —
the rules exist to make the cost visible, and some changes are worth paying it.

## Bumping dependencies

**The `kotlin` version in `gradle/libs.versions.toml` *is* the floor every consumer must compile at,
so raising it is a compatibility decision and not a dependency chore.** A KMP consumer needs a
Kotlin at least as new as the one that produced our klib metadata, and the Kotlin plugin version is
project-wide — so a floor above the newest KSP release locks out every project that needs KSP,
whatever else it is willing to do. That is why the floor sits at 2.3.21 rather than 2.4.x
([#205](https://github.com/uny/autograph/issues/205)), and why `autograph-core` owns
[`UuidV7Generator`](autograph-core/src/commonMain/kotlin/dev/ynagai/autograph/UuidV7Generator.kt)
instead of calling 2.4's `Uuid.generateV7()`. Before bumping `kotlin`, check that
[KSP](https://github.com/google/ksp/releases) has shipped for the target version, and say so in the
PR. Nothing in CI enforces this — building at the floor is the only thing that keeps it honest.

**A `composeMultiplatform` version bump in `gradle/libs.versions.toml` needs a manual cold-device
check before merging — CI cannot catch a regression here.** iOS Compose autocapture depends on an
undocumented CMP implementation detail: the accessibility-tree activation call site that autograph's
tap-time walk relies on (see the kdoc in
[`AccessibilityTree.kt`](autograph-uikit/src/iosMain/kotlin/dev/ynagai/autograph/uikit/AccessibilityTree.kt)).
`xcodebuild test` in CI is itself an accessibility client, so it warms the exact state a real cold
launch starts without — a CMP bump that silently breaks cold-start tap resolution would land fully
green (see [#135](https://github.com/uny/autograph/issues/135)).

Before merging a `composeMultiplatform` bump:

1. Create a fresh simulator, not an existing one — `shutdown`+`boot` does not reset the accessibility
   subsystem, only a genuinely new device does (`xcrun simctl create cold-check "iPhone 17 Pro"`, then
   note the returned `<udid>`). Never attach VoiceOver, Voice Control, the Accessibility Inspector,
   XCUITest, or any other accessibility client to it before the check below.
2. Start log capture first so you don't race the tap: `xcrun simctl spawn <udid> log stream --style
   compact --predicate 'eventMessage CONTAINS "AutographSample:"'`. Then build and install `sample-ios`
   on the simulator, launch it, and tap an autocaptured element (e.g. the README quick-start sample) as
   the very first interaction. Confirm the tap shows up in the log.
3. Delete the simulator afterward (`xcrun simctl delete <udid>`) — once warmed, it no longer exercises
   the cold path and cannot be reused for this check.

This is a manual step, not something a script in this repo currently automates.

### The second iOS check: `trackClick` must run before the tap observer sees the release

iOS suppresses autocapture for an explicitly instrumented element by observing that
`Modifier.trackClick`'s handler *ran* during the pointer dispatch being resolved (see the kdoc on
[`AutocaptureClaims`](autograph-compose/src/commonMain/kotlin/dev/ynagai/autograph/compose/AutocaptureClaims.kt)).
That works because `clickable` invokes its handler synchronously on the `Main` pass of the release,
strictly before the observer's `Final` pass — measured on CMP 1.11.1, but a Compose implementation
detail rather than anything the API promises. Upstream has previously routed clicks through a
suspending `detectTapAndPress`, and `combinedClickable` deliberately delays `onClick` past a
double-tap timeout.

**A handler that runs late degrades to double-reporting, not to a lost event** — it lands outside
any generation and marks nothing — so the ordinary form of this break is not a correctness
emergency. It still silently removes a feature.

The exception is worth naming, because the mark records *that* a handler ran and not *which
element's*: a replacement that invokes `onClick` inside a **later** dispatch's `Initial`→`Final`
window suppresses that dispatch instead, and if the element tapped there is uninstrumented its event
is lost outright. A timeout resuming between dispatches cannot do this — the main thread runs one
dispatch at a time — but a hand-written detector that defers a tap to the next pointer event can.
Treat that shape as a correctness change, not a feature regression.

Re-check it on device when any of these change:

- the `composeMultiplatform` version (fold this into the cold-device check above — same session);
- `Modifier.trackClick`'s own implementation, in particular replacing `clickable` with
  `combinedClickable`, `toggleable`, `selectable`, or a hand-written gesture detector;
- `autocaptureTaps`'s pointer loop, which brackets each dispatch with the generation the mark
  lands in.

Two unit tests do cover part of this, so run them first — they are cheaper than a device and they
fail loudly. `AutocaptureClaimDisposalTest.trackClickMarksItsExecutionBetweenTrackAndOnClick` injects
a real touch and reads the flag from inside the handler, so a handler that no longer runs inside its
dispatch turns it red; `PointerEventIdentityTest` pins the `PointerEvent` identity the observer's
attribution guard depends on. What neither can see is the on-device pipeline itself, which is what
the manual check below is for.

The check: with `AutographSample` running and log capture attached as above, tap
`explicit_tracked_small`. The log must show `Recipe Saved` and no `Element Clicked` for it. Tap
`clamped_inner_host`'s lower strip and confirm the opposite — an `Element Clicked` with no explicit
event — which is what proves the suppression is still discriminating rather than simply always on.

## Reporting bugs

Open a [GitHub issue](https://github.com/uny/autograph/issues/new) with steps to
reproduce, expected vs. actual behavior, and relevant environment details (platform,
Kotlin/Compose versions).

For security issues, please follow [SECURITY.md](SECURITY.md) instead of filing a
public issue.
