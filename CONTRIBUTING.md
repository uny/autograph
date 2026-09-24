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

**Leave the `enabled.set(true)` inside those `abiValidation { }` blocks alone — including if the
Kotlin floor rises again.** It looks redundant and is not: on KGP 2.3 an empty block registers the
tasks and then leaves them `SKIPPED` and out of `check`'s task graph, so `./gradlew build` passes
without validating anything. That is the same vacuous green ADR 0001 §1 calls "worse than nothing"
for `autograph-android`, and nothing announces it — you have to go looking for the task in the build
log. Measured while lowering the floor (#205): `abiValidation {}` runs the checks under 2.4.10 and
skips them under 2.3.21, so the behaviour turns on the KGP version rather than on the call form.
Setting `enabled` explicitly is what makes it independent of both on the 2.3 line, which is the
point. KGP 2.4 removed the property, so a bump back to 2.4 *does* change the form — but only in the
bump commit itself, never ahead of it, because the bare call is exactly what disarms the gate under
2.3 (see "Bumping dependencies" below).

If you touch these blocks, check the gate still fires rather than trusting a green build:
`./gradlew :autograph-core:checkKotlinAbi` must print the task, not `SKIPPED`.

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

**The `kotlin` version in `gradle/libs.versions.toml` sets the floor every consumer must compile at,
so raising it is a compatibility decision and not a dependency chore.** A klib carries the ABI
version of the compiler that produced it, so a consumer's Kotlin/Native toolchain must be at least as
new — at *minor* granularity, which is why building with 2.3.21 still leaves 2.3.20 consumers able
to link, and why a 2.4 build is rejected outright by every consumer still on a 2.3 toolchain. The
floor sits on the 2.3 line for that reach — the same practice `kotlinx` follows, building
coroutines with Kotlin 2.2 and serialization with 2.3 — and there is no way to publish a klib for a
*range* of Kotlin versions: the oldest supported minor *is* the build version. Both reasons
[#205](https://github.com/uny/autograph/issues/205) gave for the line it chose turned out to be
false, and neither may be reused to argue a bump either way: KSP is *not* what keeps consumers on
2.3 (KSP decoupled its version from the compiler's at 2.3.0, and KSP 2.3.12 runs under KGP 2.4.10 —
measured 2026-09-13 on a scratch KMP project with `jvm()` + `iosSimulatorArm64()` and a KSP
processor on both: code generated, an `abi_version=2.4.0` klib produced, no warnings), and
`Uuid.generateV7()` never required 2.4 — it is in `kotlin-stdlib` since 2.3.0 (read off the
published 2.3.0 and 2.3.21 JVM jars with `javap`, and off the Kotlin/Native 2.3.0 stdlib klib's
`linkdata/package_kotlin.uuid` metadata with `strings`, where the 2.2.20 klib has no such symbol;
2026-09-20), which is why the `UuidV7Generator` #205 added was deleted again. So the hold ends
only when the library actually needs a newer line — a fix that needs 2.4, or the Compose
Multiplatform / AGP baselines this library tracks moving to 2.4 themselves — and the PR says which.
Bumping the patch within a supported minor is the ordinary chore this warning is not about. Nothing
in CI enforces this — building at the floor is the only thing that keeps it honest.

**Raising the floor to 2.4 flips the `abiValidation` DSL in every published module, and the flip
must land in the bump commit — never before it.** KGP 2.4 removed the `enabled` property, so the
`abiValidation { enabled.set(true) }` the "Changing public API" section tells you to keep is a script
compilation error under 2.4.x (`Property was removed, to enable ABI validation call function
abiValidation()`). The bare `abiValidation()` is the 2.4 form and stays non-vacuous there (measured
on 2.4.10: adding one public function fails `:autograph-core:checkKotlinAbi`,
[#224](https://github.com/uny/autograph/issues/224)) — but under 2.3.21 that same bare call leaves
the checks `SKIPPED`, which is what #205 had to work around. So the two forms are correct on exactly
one side of the floor each: change all six blocks in the same commit as `kotlin =`, re-run
`fixtures/klib-diamond/run.sh` on the new version, and confirm `:autograph-core:checkKotlinAbi`
prints the task rather than `SKIPPED`.

**A `kotlinx` bump can raise the same floor without touching `kotlin`, and its version number will
not tell you.** Every `org.jetbrains.kotlinx` artifact ships klibs with *their* producing compiler's
ABI, and that is what a consumer's toolchain checks — not the artifact's own version. Measured on
the catalog as of 0.9.0: `kotlinx-coroutines-core` 1.11.0 and `atomicfu` 0.33.0 were built with
Kotlin 2.2, `kotlinx-serialization-json` 1.11.0 and `kotlinx-io-core` 0.9.1 with 2.3 — a
`serialization` release built with 2.4 would lock out 2.3 consumers exactly as a `kotlin` bump
would, while looking like a routine minor. Before merging any `kotlinx` bump, read the ABI off the
published klib; it takes seconds and needs no toolchain:

```bash
cd "$(mktemp -d)"
curl -sSfO https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-iosarm64/<version>/kotlinx-serialization-json-iosarm64-<version>.klib
unzip -p kotlinx-serialization-json-iosarm64-<version>.klib default/manifest | grep -E 'abi_version|compiler_version'
```

`abi_version` must stay at or below the floor's minor (`2.3.0` today). The same check is how the
Compose Multiplatform 1.12.0 bump was shown *not* to be Kotlin-blocked
([#224](https://github.com/uny/autograph/issues/224)) — it works for any klib, and it is what
decides whether a dependabot PR in the `kotlin` group is a chore or a floor change.
`dependabot.yml` ignores the `kotlin` 2.4 line itself; it cannot express this rule for `kotlinx`, so
the check is manual.

**`ios-deploymentTarget` is the published iOS floor, and a Kotlin bump no longer moves it.**
Kotlin/Native's default deployment target differs between Kotlin lines (14.0 on 2.3, 15.0 on 2.4),
which is how [#205](https://github.com/uny/autograph/issues/205) changed the shipped binary's
`minos` without any line in the diff saying so
([#208](https://github.com/uny/autograph/issues/208)). `autograph-apple` now pins it from that
catalog key, and `Package.swift`'s `.iOS(.vN)` must declare the same value: the `swift-package`
job, the release dry run and `cd.yml` read `LC_BUILD_VERSION` and `MinimumOSVersion` back from every
built slice and fail on any difference
([#197](https://github.com/uny/autograph/issues/197), `.github/scripts/check-ios-floor.sh`). The
pin goes through `-Xoverride-konan-properties`, which is compiler-internal — if a Kotlin bump breaks
it, that check is what tells you, not the build. One blind spot: it can only see a dead pin while
the toolchain's default differs from the pinned value (2.4.x defaults to 15.0, the same as the pin),
so a bump onto such a line proves nothing about the flag until the default next moves. To change
the floor, change the key and the manifest in one commit and let the check confirm the binary
followed.

**`android-compileSdk` and `android-coreCompileSdk` are the published Android floors**, not build
details: AGP writes each into its modules' AAR metadata as `minCompileSdk`, so a consumer must
compile against at least the value of every module it depends on. Each floor sits exactly where its
group's own dependencies put it, and moves only when they do ([#224](https://github.com/uny/autograph/issues/224)):

- `android-compileSdk` is for the module that draws, `autograph-compose`. It is whatever the
  androidx Compose line the current `composeMultiplatform` resolves to declares (1.12.0 → 37). A
  Compose consumer already compiles there, so a lower value buys nobody anything, and a higher one
  would lock out consumers CMP itself admits. A CMP bump can move it; read the new line's
  `aar-metadata.properties` and update both together.
- `android-coreCompileSdk` is for the Android modules that do not draw (`core`, `context`,
  `segment`, `test`, `android`). Their floor is their own dependencies' — `androidx.startup` and
  `androidx.fragment` today, both 34 — and `checkAndroidMainAarMetadata` is transitive through
  project dependencies, so everything that depends on `autograph-core` inherits `core`'s. Raise it
  only when one of those dependencies demands it, and prefer pinning that dependency lower: a newer
  `compileSdk` implies a newer AGP, which a consumer pinned by its own toolchain may not have.

`sample-android` has its own `android-sampleCompileSdk` key so the demo app's dependencies cannot
push either floor up; `sample-shared` deliberately stays on `android-compileSdk`, which is what
makes CI prove a Compose consumer can build at the floor.

One trap when the offending dependency is `lifecycle`: Compose Multiplatform's own module metadata
already `requires` a specific jetbrains `lifecycle` version — CMP 1.12.0 still asks for 2.9.6 — and Gradle
resolves to the highest request in the graph. So the catalog value changes nothing unless it sits
*above* what CMP asks for, which is exactly how the old 2.11.0 pin dragged the floor to 37. Check
what CMP already requires before pinning it yourself.

**A `composeMultiplatform` version bump in `gradle/libs.versions.toml` needs a manual cold-device
check before merging — CI cannot catch a regression here.** iOS Compose autocapture depends on an
undocumented CMP implementation detail: the accessibility-tree activation call site that autograph's
tap-time walk relies on (see the kdoc in
[`AccessibilityTree.kt`](autograph-uikit/src/iosMain/kotlin/dev/ynagai/autograph/uikit/AccessibilityTree.kt),
and [`docs/design/135-ios-cold-accessibility.md`](docs/design/135-ios-cold-accessibility.md) for the
names it depends on and the CMP version each was read on).
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
