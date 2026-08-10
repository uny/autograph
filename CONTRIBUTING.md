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

**A break here degrades to double-reporting, never to a lost event**, so it is not a correctness
emergency — but it silently removes a feature, and no unit test can see it.

Re-check it on device when any of these change:

- the `composeMultiplatform` version (fold this into the cold-device check above — same session);
- `Modifier.trackClick`'s own implementation, in particular replacing `clickable` with
  `combinedClickable`, `toggleable`, `selectable`, or a hand-written gesture detector;
- `autocaptureTaps`'s pointer loop, which brackets each dispatch with the generation the mark
  lands in.

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
