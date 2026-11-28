# ADR 0001 — How the public API may evolve after 1.0

- **Status:** Accepted
- **Date:** 2026-07-21
- **Issue:** [#53](https://github.com/uny/autograph/issues/53)
- **Amended:** 2026-10-03 — §2c/§2d freeze the member set of every caller-implemented
  interface, because a default body does not keep a Swift conformer compiling
  ([#283](https://github.com/uny/autograph/issues/283#issuecomment-5920254763))
- **Amended:** 2026-11-15. §2 now covers both stable tiers, and §2h classifies every public
  declaration as the 1.0 baseline ([#283](https://github.com/uny/autograph/issues/283))
- **Amended:** 2026-11-28. **Proposed, pending the owner's decision:** §1 gives the Swift
  package's products a tier, and §2h classifies their declarations
  ([#339](https://github.com/uny/autograph/issues/339))

## Context

Every published module runs `explicitApi()`, and most also run `abiValidation()`, so a
change to the public surface is *detected* — with the two gaps §1 records. Detection is
not a plan. Once 1.0 declares an ABI stable, detection only tells us we are stuck: the
tooling reports the break, and the only remedy left is a major version bump.

The window to decide *how each public type is allowed to grow* is before 1.0, because
several of the choices — most of all whether `Envelope`'s constructor is public — cannot
be made later without the major bump they exist to avoid.

Two facts shaped the answer:

- The mechanism is already in use. `Transport.flush()` and `EnvelopeSource.stamp(Long)`
  were both added after the fact as members with default bodies, and neither broke a
  Kotlin implementor. (Neither would have kept a Swift implementor compiling — §2c records
  why, and why that changed the rule.)
- The absence of a policy already cost us a decision. [#52](https://github.com/uny/autograph/issues/52)
  kept `close()`'s drain timeout as a `private const` rather than a public knob
  specifically because there was no rule for what adding a public knob would commit us to.

## Decision

### 1. Stability is declared per artifact, not for the project

| Artifact | Guarantee |
|:--|:--|
| `autograph-core`, `autograph-context` | **SemVer ABI stable.** Binary-compatible within a major version; this ADR's rules apply in full. |
| `autograph-compose`, `autograph-uikit`, `autograph-android`, `autograph-segment` | **Stable API, constrained toolchain.** The same source and binary rules apply, but each is bounded by a dependency it does not control (the Compose compiler, the iOS SDK / Xcode, the Android SDK and AGP, the Segment SDK — one each, respectively). A major bump in one of those may force a break that is not ours to schedule. |
| `AutographUI`, `AutographSegmentSwift` (Swift package products) | **Stable API, constrained toolchain, for Swift source only.** SwiftPM compiles these from source inside the app, so there is no binary to keep compatible: the promise is that Swift code which compiles against one minor version compiles against the next. `AutographUI` is bounded by SwiftUI and Xcode, and `AutographSegmentSwift` by analytics-swift within the major version `Package.swift` allows (1.x today). The Kotlin types in their signatures (`AutographScreenCapture`, `AutographElementCapture`, `SegmentBridge`) keep the tier of the artifact that declares them. |
| `autograph-test`, `autograph-schema` | **Source-compatible on a best-effort basis; no ABI guarantee.** These are development-time helpers — a test transport and a code generator. They are not on an app's release-critical path, so freezing them would buy safety nobody is asking for. `autograph-schema` therefore does not run `abiValidation()`, and that is deliberate rather than an oversight. |

The guarantee covers Swift *source* compatibility for the Kotlin/Native artifacts as well as
binary compatibility: an app's Swift code that compiles against one minor version compiles
against the next. §2c records the constraint that follows.

Declarations marked `@AutographInternalApi` are outside every tier. They are `public`
only because Kotlin's `internal` does not cross a module boundary, and may change in a
patch release.

**That marker is for machinery, never for an entry point this project tells adopters to call.**
Until [#240](https://github.com/uny/autograph/issues/240) the four `installAutographNative*`
functions and the handles they return carried it while the README documented them as the supported
way to instrument a native surface and this section listed the Android pair as the review-enforced
public surface of `autograph-android` — so an adopter following the docs had to opt out of a guarantee already
made, and had no way to tell which of the two statements was the contract. The annotation could not
enforce the boundary it claimed either: `RequiresOptIn` does not reach the Objective-C header a
Swift consumer sees, and it is not recorded in the klib dump, so it is invisible to both of this
project's mechanical checks. The test to apply before adding it: *would we write a README section
telling someone to call this?* If yes, it belongs in a tier. Something genuinely unfinished wants a
separate, differently-named opt-in rather than this one.

**Some public surface is not covered by the dump check**, so the review checkpoint of §4 does
not fire for it and its rules rest on review alone. This is a boundary of the tooling, documented
here rather than hidden — it is the resolution of
[#106](https://github.com/uny/autograph/issues/106):

- `autograph-schema` does not run `abiValidation()` — deliberate, per the tier above (a no-ABI
  code generator).
- `autograph-android` does not run `abiValidation()` **either, and by decision rather than
  oversight.** KGP derives its JVM/binary ABI dump from a `jvm()` target; a pure-Android module
  built on `Activity`/`Fragment` cannot have one, so there is nothing for the dump to cover. The
  module previously *applied* `abiValidation()`, which produced an empty `api/` and a
  vacuously-passing `checkKotlinAbi` — worse than nothing, because it read as protected while
  catching no public-API change (a contributor could add public API and get no signal). Removing
  it makes the boundary honest; the module's public surface
  (`installAutographNativeScreenCapture`, `AutographNativeScreenCapture`, …) is enforced by review.
- `autograph-segment`'s **Android-specific** surface has the same gap for the same reason: it
  keeps `abiValidation()` because its klib dump legitimately covers the common/iOS API, but with no
  `jvm()` target its Android `SegmentTransport` face is not dumped, and is review-enforced.
- `autograph-core`'s Android source set has one public class, `AutographInitializer`, the
  `androidx.startup` initializer that captures the application `Context` for `platformSeqStore()`.
  The JVM dump is built from `jvm()`, not from `androidMain`, so it does not list this class, and the
  class is review-enforced. `autograph-context`'s Android source set has only
  `@AutographInternalApi` declarations (`View.autographScopeOwner` and its listeners), which are
  outside every tier anyway.

- The Swift package's products (`AutographUI`, `AutographSegmentSwift`) have no Kotlin dump, because
  they are Swift. SwiftPM's API digester covers them instead:

  ```bash
  swift package --triple arm64-apple-ios15.0-simulator \
      --sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
      diagnose-api-breaking-changes <previous release tag>
  ```

  This was measured on 2026-11-28 with Xcode 27.1 / Swift 6.4, against `v0.13.0`. Without `--triple`
  and `--sdk` the command fails: it builds for the macOS host, and `Autograph.xcframework` has only
  iOS slices. With them it reported no break on `main`. It reported each of three deliberate breaks:
  a renamed `View.autographIgnore()`, a defaulted parameter added to `AutographButton`'s primary
  initializer, and a relabelled `AutographSegmentBridge.init(analytics:)`. Its limits:
  - The baseline is checked out without a local framework, so it takes `Package.swift`'s download
    branch. The baseline has to be a release tag that has an asset, and the run needs the network.
  - The current tree compiles against whatever framework `autograph-apple/build` holds, so a CI job
    must build it first, as the `swift-package` job already does.
  - It compares declarations only. It does not see an added overload that makes an existing call
    ambiguous, a manifest change, or behaviour.

  **The command is not wired into CI.** Until it is, these products are enforced by review plus
  running this command by hand, the same boundary as `autograph-android`.

The common root cause — a JVM/Android ABI dump requires a `jvm()` target — is why the modules whose
common surface *is* dump-covered (`autograph-core`, `-context`, `-compose`) all declare `jvm()`.

### 2. Public types are classified by *who constructs* and *who implements* them

That question, not the type's shape, determines what can be added to it later. The
categories below are exhaustive over the public surface of both stable tiers of §1, the
SemVer ABI tier and the stable-API tier. Every public declaration in `autograph-core`,
`autograph-context`, `autograph-compose`, `autograph-uikit`, `autograph-android` and
`autograph-segment` falls under exactly one of them, or is `@AutographInternalApi` and outside
every tier. The Swift package's products, `AutographUI` and `AutographSegmentSwift`, are classified the
same way (proposed, #339). §2h lists each declaration with its category. A new declaration that fits no category
is the signal that this ADR needs another category, not an exception.

#### 2a. Library-produced value types — construction is frozen

`Envelope`, `SessionInfo`, `EventMetadata`, `CloseResult`, `TransportEvent`; and, in `autograph-context`, `ScopeHandle` and `AmbientContext`.
The handles that the native capture entry points return belong here too: in `autograph-uikit`,
`AutographNativeTapCapture`, `AutographNativeScreenCapture`, `AutographScreenView`,
`AutographIgnoredBoundsRegistration` and `AutographIgnoredViewRegistration`; in
`autograph-android`, `AutographNativeTapCapture`, `AutographNativeScreenCapture` and
`AutographScopeHandle`.

The library creates these; callers only read them. They are declared

```kotlin
@ConsistentCopyVisibility
public data class Envelope internal constructor(/* ... */)
```

so `public val`s, `equals`/`hashCode`/`toString`, and `componentN` (destructuring) stay
public, while the constructor and `copy` leave the *validated* ABI — both dumps — entirely.
**New properties are therefore additive forever** for every caller the compiler governs,
which is the whole point: the envelope is the type most likely to grow.

One limit, measured rather than assumed. On Kotlin/Native the constructor is genuinely gone
from the klib. On the JVM it is not: `internal` cannot be expressed in bytecode, so `copy`
is name-mangled to `copy$dev_ynagai_autograph_autograph_core` but the primary constructor
stays a plain `ACC_PUBLIC` method. A *Java* caller can therefore still invoke it, and would
break when a property is added — without `checkKotlinAbi` saying a word, since the dump no
longer tracks it. Kotlin has no stronger mechanism here (`@JvmSynthetic` does not apply to
constructors), Kotlin callers in every module are protected by the compiler, and the
position before this change was strictly worse. So the guarantee is stated as it is: binding
for Kotlin consumers on all targets, and for Swift and Kotlin/Native absolutely; on the JVM
a Java caller who reaches around the visibility is outside it.

The other way through is deliberate: `@AutographInternalApi public fun createEnvelope(...)`
exists so `autograph-test` can build an envelope across a boundary `internal` does not
cross. It is opt-in-gated and outside every tier (§1), and its signature gains a parameter
whenever `Envelope` does.

Four consequences we accept knowingly:

- Callers can no longer write `Envelope(...)` or `envelope.copy(...)`. The legitimate way
  for a transport to obtain an envelope was always `EnvelopeSource.stamp()`; constructing
  one by hand bypasses event-id uniqueness, session rotation, and sequence monotonicity,
  so this removes a footgun rather than a feature. Tests get an explicit factory in
  `autograph-test`, which is outside the ABI guarantee (tier table above) and can gain
  parameters as the envelope grows — that change is still *detected* by its dump, it just
  does not have to be avoided.
- Faking `EnvelopeSource` now requires `autograph-test`. `stamp(): Envelope` cannot be
  implemented without an envelope, so a consumer stubbing it in their own tests takes a
  `testImplementation` dependency on `autograph-test`. This PR's own `autograph-segment`
  plugin test is the first instance. The escape hatch is real and published, but it is a
  migration step, not a no-op.
- Swift consumers of the XCFramework lose the generated initializer. That is a *gain*:
  a public constructor exports an initializer selector, and adding a field to it is a
  source break for Swift even when it is binary-compatible for Kotlin.
- Adding a property is binary-compatible but **not behavior-compatible**: it changes what
  `equals`/`hashCode`/`toString` mean. A caller keying a map on an `Envelope` sees
  behavior change without a compile error. New properties go at the end of the parameter
  list, and the changelog says so explicitly.

`ScopeHandle`, `AmbientContext`, `TransportEvent` and the capture handles are in this category by
the same test — the library produces them, callers only read them or call them — but are plain
classes with an `internal`
constructor rather than the `data class` shape above, so they never had `copy` or
`componentN` to lose and gain properties freely. A library-produced type that does *not*
need value equality should prefer that plainer shape; `@ConsistentCopyVisibility` is only
needed to keep `data class` from re-exposing construction through `copy`.

#### 2b. Caller-constructed configuration — carried by a mutable-property receiver

`AutographConfig` is the model: `internal constructor`, knobs as `var`s, populated through
the `Autograph { }` DSL lambda. A new knob is a new `var` — no ABI break, ever. (`transport`
is the exception that proves the shape: it is a `public fun` rather than a `var` so the
backing field can stay `internal`. A new member function is equally additive.)
`AutocaptureConfig` in `autograph-compose` has the same shape since #257: `internal constructor`,
knobs as `var`s, built by the `Autocapture { }` builder.

**New configuration knobs go on `AutographConfig`.** A knob is not given its own
`data class` unless it has enough internal structure to justify one, because a `data class`
constructor cannot gain a parameter without breaking ABI: adding a defaulted parameter
changes the synthetic `DefaultConstructorMarker` bridge.

The existing caller-constructed carriers — `SessionConfig`, `SeqPersistence.Chunked` and
`ScreenContext` — are **frozen at their current shape**. In
particular `SessionConfig` keeps its single `backgroundTimeout` parameter; a future
session knob (foreground timeout, maximum session length, new-session-on-launch) is added
to `AutographConfig`, not to `SessionConfig`.

#### 2c. Caller-implemented SPIs — the member set is frozen for the major version

`Transport`, `SeqStore`, `EventIdGenerator`, `EventValidator`, `SegmentBridge`, `AutographLogger`,
`MetadataAwareTransport`, `AwaitableCloseTracker`.

No member is added to one of these within a major version, **including a member with a
default body**. A new capability is added in one of two ways instead:

- as a separate interface that the core probes with `is`:

  ```kotlin
  public interface SomethingCapable { public fun something() }
  // core: if (transport is SomethingCapable) transport.something()
  ```

- or as a library-produced value object (§2a) passed through a member that already exists,
  which can then gain properties freely.

A capability interface is itself a caller-implemented interface under this section, so its
member set is frozen once it ships. A second capability is a second interface.

`AwaitableCloseTracker` has exactly one member, `suspend fun closeAndAwait(timeoutMillis: Long)`.
It was kept to one so that the frozen surface is as small as it can be: the no-argument helper
is the `Long.MAX_VALUE` case of that member, not a second member. Only `Autograph { }` can
really implement it, because `CloseResult` has no public constructor (§2a), so any other
implementation can only forward to a tracker from `Autograph { }`. Whether a Swift class can
conform was measured (Xcode 26 / iOS 26.4 simulator, the `Autograph.xcframework` built from
this repository, `CloseAndAwaitTests`). The generated header declares the member as a
completion-handler method, and a Swift class that adopts `AwaitableCloseTracker` and writes
it as `func closeAndAwait(timeoutMillis: Int64) async throws -> CloseResult` compiles. A
Swift class that is both a `Tracker` and an `AwaitableCloseTracker` is probed with `is` by
the Kotlin helper, and the Kotlin helper's call reaches its Swift `async` implementation and
returns the forwarded result. Both are forwarding implementations; a Swift conformer that
produces its own `CloseResult` is impossible. The `CloseResult.isUnsupported` property is
encoded as `!cutoffReached`, and that encoding is intentionally frozen.

**Why a default body is not enough.** A Kotlin default body keeps a Kotlin implementor
compiling, and keeps an already-compiled one linking (§4). It does nothing for Swift.
Kotlin/Native's Objective-C export declares every member of an exported interface
`@required`, including members with a default body. The generated `Autograph.h` has no
`@optional` member at all. So `SeqStore.flush()`, whose no-op default this section used to
hold up as the model, is a method every Swift `SeqStore` has to write. A member added later
with a default body is therefore a new requirement for every Swift class that already
conforms, and a default body does nothing to keep such a class compiling.

These conformers exist today. `AutographSegmentBridge` (`Sources/AutographSegmentSwift`)
implements `SegmentBridge`. The Swift `Tracker` fakes in `Tests/AutographUITests` and the
sample app's `SwiftUIExplicitTracker` implement every member of `Tracker`, including
`close`, `flush` and `reset`, which have Kotlin default bodies. Swift source compatibility is
part of the 1.0 contract, so the rule follows Swift, the stricter of the two languages.

**Why `is` works for Swift.** This was measured with Kotlin/Native 2.3.21, using a
`macos_arm64` framework called from Swift. A Kotlin `x is Cap` check, where `Cap` is a
second exported interface, returns `true` for a Swift class that adopts `Cap`. That holds
whether the class adopts it in its declaration or in a Swift extension. The check returns
`false` for a class that does not adopt `Cap`. A Swift class compiled before `Cap` existed
keeps compiling, and the core simply never treats it as a `Cap`. The probe defined both
interfaces in the same framework as the check. A setup that splits them across two
Kotlin/Native frameworks was not measured.

The `is` route moves the old rule's question rather than removing it. A capability that an
implementor never heard of goes unused, so the core's behavior when the probe fails must be
**correct, not merely compiling**. Behavior that silently does the wrong thing is worse than a
compile error, because it turns a break we would have caught into data loss we would not.
If no fallback is correct, the capability is not optional, and it waits for the next major
version.

The members that already have default bodies, such as `Transport.connect`/`flush`/`reset`
and `SeqStore.flush()`, keep them. The defaults still save Kotlin implementors the work, and
Swift implementors already write those members.

**A decorator hides a capability it does not declare.** A wrapper forwards only the
members it knows about, so an `is` probe that reaches a wrapper fails even when the wrapped
object has the capability. The library's own wrappers, the public `DebugTransport` (§2f) and
the internal `ScopedTracker` in `autograph-compose`, are reviewed whenever a capability is
added: Kotlin cannot make a class conform only when its delegate does, so a wrapper either
declares the capability and supplies its own correct behavior, or hides it. Wrappers that
callers write are outside our control, which is a second reason the fallback has to be
correct. Where the capability acts on a resource, the fallback must also respect the
wrapper's ownership: a wrapper that does not own what it wraps must not become a route to
release it.

Before 1.0 these interfaces may still change, but a change is a migration for implementors,
and the CHANGELOG says so. A default body does not make the change free.

#### 2d. Caller-called interfaces — the same freeze

`Tracker`, `EnvelopeSource`.

These are called rather than implemented in normal use, but consumers legitimately implement
them. They fake `Tracker` in their own tests, wrap it in decorators (the library has an
internal one, `ScopedTracker`), and conform to it from Swift. Converting them to an
abstract class with an internal constructor, which would let us add abstract members
freely, would break all of that, so it is rejected. They follow 2c's rule instead.

#### 2e. Enums and sealed hierarchies — the case set is frozen for the major version

`SequenceMode`, `SeqPersistence`.

Adding an enum constant or a sealed subtype is binary-additive but breaks every exhaustive
`when` a caller has written, and we have no way to stop callers writing one — the library
does it itself, over `SeqPersistence`, in
[`Stamper.kt`](../../autograph-core/src/commonMain/kotlin/dev/ynagai/autograph/Stamper.kt).
Telling callers "always add an `else`" is a rule we cannot enforce and would not notice
being broken.

So the honest commitment is the stronger one: **no new constants or subtypes within a major
version.** New behavior is expressed as a separate `AutographConfig` property (2b), which
costs nothing and breaks nobody. This is also why a public sealed type is not introduced for
anything callers are expected to `when` over.

#### 2f. Caller-constructed concrete classes — members may be added, constructors may not

`ScopeStack`, `ScreenHistory` (in `autograph-context`), `DebugTransport`, `DefaultProperties`,
`InMemorySeqStore`; `SegmentTransport` (in `autograph-segment`, one constructor per platform);
`AutographScreenCapture` and `AutographElementCapture` (in `autograph-uikit`).

`AutographInitializer` (in `autograph-core`'s Android source set) is here too, with one
difference: `androidx.startup` constructs it from the manifest, not the app. Its public no-argument
constructor is what `androidx.startup` calls, so that constructor is frozen in the same way. Apps
only name the class, to disable or reorder it in their manifest, so its name is frozen as well.

The caller both constructs these and calls them, but unlike 2b they carry behavior rather
than configuration, and unlike 2a they are not values. Because they are `final` classes
nobody implements, **adding a public member is binary-additive and allowed** — the freedom
2c and 2d do not have. What is frozen is the *constructor*: these have
public constructors, so a new parameter is an ABI break exactly as in 2b. A construction
argument they may later need is therefore either given a default at a call site the library
controls, or moved onto `AutographConfig`.

Stateless entry points — the `Autograph { }` builder function, `EventId`, `EmptyJsonObject`,
`platformSeqStore()`, the `Transport.intercept`, `Tracker.closeAndAwait` and `asJsonObject`
extensions, the constants `RESERVED_METADATA_KEY`, `EventKinds`, `TransportEventTypes` and
`DEFAULT_AUTOCAPTURE_EVENT_NAME`, and in the stable-API tier every composable, `Modifier`
extension, `CompositionLocal`, `install…`/`register…` function and `View` extension property
listed in §2h — carry no construction contract at all. Adding a new top-level
function or `object` member beside them is additive; changing an existing signature is a
break, with no rule beyond that.

#### 2g. Declarations hidden from Swift

`Transport.intercept`, `TransportEvent` and `TransportEventTypes` carry `@HiddenFromObjC`, the first
use of it in this project. Kotlin-only for 0.13.0 was the owner's decision (2026-10-25, #302): a
Swift closure would receive `TransportEvent.properties` as a `[String: JsonElement]` dictionary and
trap on a nested value (#299). A hidden declaration is still in the JVM and klib dumps, so it is
frozen for Kotlin/JVM and Kotlin/Native consumers exactly as its category above says. It is absent
from the Objective-C header, so the Swift source-compatibility promise does not cover it. Swift
support, if it comes, is a separate Swift-safe entry point added beside it, not the annotation
removed: removing it would export the same trap. Revisit this when the 1.0 Swift protocol that
#299 left open is designed.

#### 2h. Every public declaration, by artifact

This is the 1.0 baseline, taken from `main` after 0.13.0. A row names a top-level declaration, or
a nested type with its parent's name. Members are not listed: a member follows its type's category.
An overloaded function or a function declared once per platform has one row. In the category
column, *entry* means the stateless entry points at the end of §2f, and *internal* means
`@AutographInternalApi`, which is outside every tier (§1). *Undumped* marks a declaration that no
ABI dump covers, so review enforces it (§1).

**`autograph-core`**

| Declaration | Category |
|:--|:--|
| `Autograph` | entry |
| `AutographConfig` | 2b |
| `AutographInitializer` | 2f, framework-constructed; Android only, undumped |
| `AutographInternalApi` | the opt-in marker itself (§1) |
| `AutographLogger` | 2c |
| `AwaitableCloseTracker` | 2c |
| `CloseResult` | 2a |
| `DebugTransport` | 2f |
| `DefaultProperties` | 2f |
| `EmptyJsonObject` | entry |
| `Envelope` | 2a |
| `EnvelopeSource` | 2d |
| `EventId` | entry |
| `EventIdGenerator` | 2c |
| `EventKinds` | entry |
| `EventMetadata` | 2a |
| `EventValidator` | 2c |
| `InMemorySeqStore` | 2f |
| `MetadataAwareTransport` | 2c |
| `RESERVED_METADATA_KEY` | entry |
| `SeqPersistence` | 2e |
| `SeqPersistence.Chunked` | 2e; also a frozen 2b carrier |
| `SeqPersistence.EveryEvent` | 2e |
| `SeqStore` | 2c |
| `SequenceMode` | 2e |
| `SessionConfig` | 2b, frozen carrier |
| `SessionInfo` | 2a |
| `Tracker` | 2d |
| `Transport` | 2c |
| `TransportEvent` | 2a; hidden from Swift (2g) |
| `TransportEventTypes` | entry; hidden from Swift (2g) |
| `asJsonObject` | entry |
| `callableFromKotlin` | internal |
| `closeAndAwait` | entry |
| `createEnvelope` | internal |
| `createEventMetadata` | internal |
| `deliverableTo` | internal |
| `intercept` | entry; hidden from Swift (2g) |
| `platformSeqStore` | entry |
| `withEventMetadata` | internal |

**`autograph-context`**

| Declaration | Category |
|:--|:--|
| `AmbientContext` | 2a |
| `DEFAULT_AUTOCAPTURE_EVENT_NAME` | entry |
| `ScopeHandle` | 2a |
| `ScopeStack` | 2f |
| `ScreenHistory` | 2f |
| `continueScreenVisit` | internal |
| `emitScreenView` | internal |
| `endScreenVisit` | internal |
| `screenViewIdOf` | internal |
| `startScreenVisit` | internal |
| `autographScopeOwner` | internal; Android only, undumped |
| `autographScopeOrigin` | internal; Android only, undumped |
| `addAutographScopeOwnerListener` | internal; Android only, undumped |
| `removeAutographScopeOwnerListener` | internal; Android only, undumped |
| `notifyAutographScopeOwnerChanged` | internal; Android only, undumped |

**`autograph-compose`**

| Declaration | Category |
|:--|:--|
| `Autocapture` | entry |
| `AutocaptureConfig` | 2b |
| `AutographElementScope` | entry |
| `AutographProvider` | entry |
| `AutographScope` | entry |
| `ImpressionScope` | entry |
| `LocalScreenContext` | entry |
| `LocalTracker` | entry |
| `ScreenContext` | 2b, frozen carrier |
| `TrackScreenView` | entry |
| `TrackScreenViews` | entry |
| `TrackedScreen` | entry |
| `autographAutocaptureRoot` | entry |
| `autographIgnore` | entry |
| `trackClick` | entry |
| `trackImpression` | entry |

**`autograph-uikit`**

| Declaration | Category |
|:--|:--|
| `AutographElementCapture` | 2f |
| `AutographIgnoredBoundsRegistration` | 2a |
| `AutographIgnoredViewRegistration` | 2a |
| `AutographNativeScreenCapture` | 2a |
| `AutographNativeTapCapture` | 2a |
| `AutographScreenCapture` | 2f |
| `AutographScreenView` | 2a |
| `defaultScreenName` | entry |
| `installAutographNativeScreenCapture` | entry |
| `installAutographNativeTapCapture` | entry |
| `registerAutographIgnoredBounds` | entry |
| `registerAutographIgnoredView` | entry |
| `AUTOGRAPH_SCOPE_IDENTIFIER_PREFIX` | internal |
| `AutographComposeHosts` | internal |
| `AxPoint` | internal |
| `AxRect` | internal |
| `accessibilityBoundsInWindowPx` | internal |
| `accessibilityChildren` | internal |
| `accessibilityIdentifierOrNull` | internal |
| `anyAccessibilityDescendant` | internal |
| `deepestAccessibilityHitPath` | internal |
| `isAccessibilityButton` | internal |
| `isAccessibilityDisabled` | internal |
| `isAutographScopeContainer` | internal |
| `isCapturableWindow` | internal |
| `nearestAccessibilityClickable` | internal |
| `resolveNativeTapTarget` | internal |

**`autograph-android`** (no dump, by decision: every row is undumped)

| Declaration | Category |
|:--|:--|
| `AutographNativeScreenCapture` | 2a |
| `AutographNativeTapCapture` | 2a |
| `AutographScopeHandle` | 2a |
| `autographScope` | entry |
| `installAutographNativeScreenCapture` | entry |
| `installAutographNativeTapCapture` | entry |
| `isAutographEmbedded` | entry |
| `isAutographIgnored` | entry |

**`autograph-segment`**

| Declaration | Category |
|:--|:--|
| `SegmentBridge` | 2c; iOS only |
| `SegmentTransport` | 2f; its Android face is undumped |

**The Swift package's products** (`Sources/`). Swift has no klib or JVM dump, so these tables are
checked by the second command below and by the API digester (§1's Swift-package bullet). A row names a
top-level type, or an extension member on a type this package does not declare (`View`,
`EnvironmentValues`), since that member is the entry point. Members of the package's own types follow
their type, as above.

**`AutographUI`**

| Declaration | Category |
|:--|:--|
| `AutographButton` | entry |
| `AutographTracking` | 2a |
| `autograph` | entry; `EnvironmentValues`, get-only outside the module |
| `autographElementCapture` | entry; `View` |
| `autographIgnore` | entry; `View` |
| `autographScope` | entry; `View` |
| `autographScreen` | entry; `View` |
| `autographScreenCapture` | entry; `View` |

**`AutographSegmentSwift`**

| Declaration | Category |
|:--|:--|
| `AutographSegmentBridge` | 2f; its member set is `SegmentBridge`'s (2c) |

Three judgement calls in these tables, each reversible before 1.0:

- **`AutographButton` is *entry*, not 2f.** It is a SwiftUI `View`, the Swift counterpart of a
  composable, and nobody holds one or calls a member on it, which is what 2f's freedom to add
  members is for. Under *entry* its existing initializers are frozen signatures, which is what 2f
  would freeze too. Adding a defaulted parameter to an existing initializer counts as a break, even
  though most call sites would still compile: the digester reports it as a removed initializer
  (measured), and a call that names the initializer as a value with its full labels would stop compiling. A new initializer
  beside the existing ones is additive only when no existing call becomes ambiguous. The
  `LocalizedStringKey` / `StringProtocol` pair is that case: a string literal still resolves to the
  localized overload. The digester does not check ambiguity, so review does.
- **`AutographTracking` is 2a.** It has no public initializer, the library puts it in the
  environment, and callers only call `track`. New methods are additive.
- **`AutographSegmentBridge` is 2f.** The app constructs it with `init(analytics:)`, which is frozen.
  Its member set is not free the way 2f's usually is, because it is exactly `SegmentBridge`'s
  requirements, which 2c freezes for the major version. So the bridge's own tier cannot be weaker
  than `SegmentBridge`'s on the Kotlin side: a bridge that stopped compiling against the protocol
  would break every app that uses the product. The `init()` the API digester lists is the implicit
  `NSObject` override. Swift cannot call it, and it is outside the guarantee.

The manifest is part of the contract only where an app names it: the product names `AutographUI` and
`AutographSegmentSwift`, and the module names they import. The iOS floor (`platforms`) and the
analytics-swift range follow §1's toolchain bound. A change to either is a breaking change in the
CHANGELOG, not one this tier forbids. `swift-tools-version` is not part of the contract.

To re-check this list against the dumps and sources, every printed name must have a row above:

```bash
{ for m in core context compose uikit segment; do
    grep -E '^(final |abstract |open |sealed )|^    [a-z ]*(class|interface|object) ' \
        autograph-$m/api/autograph-$m.klib.api |
      grep -v stableprop | sed -E 's/.*\/\/ [^/]*\/([^|@]+)[|@].*/\1/'
  done
  grep -rhE '^public ' autograph-{android,core,context,compose,segment}/src/androidMain |
    sed -E 's/^public ((data|sealed|enum|abstract|fun|annotation|const) )*(class|interface|object|fun|val|var) +([A-Za-z]+\.)?`?([A-Za-z_]+).*/\5/'
} | sort -u | while read -r n; do
  grep -qF "| \`$n\` |" docs/adr/0001-public-api-evolution.md || echo "unclassified: $n"
done
```

To re-check the Swift tables against `Sources/`, every printed name must have a row in its own
product's table above (not in a Kotlin table, which already has `autographScope` and
`autographIgnore`, nor in the other product's table):

```bash
for t in AutographUI AutographSegmentSwift; do
  sed -nE "/^\*\*\`$t\`\*\*/,/^(\*\*|Three judgement calls)/p" \
      docs/adr/0001-public-api-evolution.md > /tmp/adr-swift-rows
  awk 'pass != 2 {
         if ($0 ~ /^(@[A-Za-z]+ +)*(public|open) +(final +)?(struct|class|enum|protocol|actor|typealias) /) {
           n = $0; sub(/^(@[A-Za-z]+ +)*(public|open) +(final +)?(struct|class|enum|protocol|actor|typealias) +/, "", n)
           sub(/[^A-Za-z0-9_].*/, "", n); own[n] = 1 }
         next }
       /^(@[A-Za-z]+ +)*(public|open) +(final +)?(struct|class|enum|protocol|actor|typealias|func|var|let) / {
         n = $0; sub(/^(@[A-Za-z]+ +)*(public|open) +(final +)?(struct|class|enum|protocol|actor|typealias|func|var|let) +/, "", n)
         sub(/[^A-Za-z0-9_].*/, "", n); print n }
       /^(public +)?extension / {
         ext = $0; sub(/^(public +)?extension +/, "", ext); sub(/[^A-Za-z0-9_.].*/, "", ext)
         pub = ($0 ~ /^public/); inext = !(ext in own); next }
       /^}/ { inext = 0 }
       inext && /^    / && (pub || /^    (@[A-Za-z]+ +)*(public|open) /) &&
         /^    (@[A-Za-z]+ +)*((public|open|internal\(set\)|static|nonisolated) +)*(func +|var +|let +|subscript *[(<])/ {
         n = $0; sub(/^    (@[A-Za-z]+ +)*((public|open|internal\(set\)|static|nonisolated) +)*(func|var|let) +/, "", n)
         sub(/[^A-Za-z0-9_].*/, "", n); if (n == "") n = "subscript"; print n }
  ' Sources/$t/*.swift pass=2 Sources/$t/*.swift |
    sort -u | while read -r n; do
      grep -qF "| \`$n\` |" /tmp/adr-swift-rows || echo "unclassified in $t: $n"
    done
done
```

The `pass=2` argument splits the two readings of the same files: the first collects the package's own
types from every file, the second classifies.

It prints nothing when every name has a row. It reads declarations line by line, so a public
declaration split across lines before its name, or a `public` member inside a non-public extension of
a type declared elsewhere, is caught only if it matches those patterns. The API digester (§1) sees the
whole module and is the backstop.

### 3. The wire format is versioned separately from the ABI

The JSON the envelope serializes to (`Envelope.toJson`) is a *downstream* contract —
dashboards and warehouse schemas are built on it — and it is already declared stable in
the README ahead of the Kotlin API. It evolves by **adding keys only**; a rename or a type
change is a major bump for the library regardless of what it does to the ABI.
`AutographConfig.schemaVersion` versions the adopter's own tracking plan and is not a lever
on this contract.

### 4. Mechanics

- `./gradlew :<module>:checkKotlinAbi` verifies; `:updateKotlinAbi` re-dumps. An API dump
  change in a PR diff is the signal to check this ADR — that is the intended workflow, and
  an unexplained dump change is a review blocker.
- `-jvm-default=enable` is Kotlin's default from 2.2 onward, so an interface member with a
  default body compiles to a real Java `default` method and an already-compiled implementor
  keeps working. The build does not set the flag explicitly; it is pinned here so that a
  future toolchain change flipping the default is recognized as the ABI event it would be.
- **Kotlin/Native holds in the direction that matters, and this is measured**
  ([#104](https://github.com/uny/autograph/issues/104); fixture and full results in
  `fixtures/klib-diamond/`). The mixed-version diamond — an app resolving `autograph-core:1.1`
  while linking an `autograph-segment:1.0` klib compiled against `1.0` — was reproduced for all
  three binary-additive change kinds (default-bodied SPI member, property on an
  internal-constructor class, enum constant). All three link and run, on `iosSimulatorArm64`
  and `macosArm64` alike, and unchanged whether the old half was built by Kotlin 2.2.20,
  2.3.0, or 2.4.0 against a 2.4.10 consumer. A deliberate ABI break in the same rig does
  fail, so the green result is not an inert fixture.

  The reason is that Kotlin/Native does not load a klib the way a JVM loads a class file: a klib
  carries serialized IR, and the link step re-lowers the whole graph against whichever `core`
  resolved. The dependent's compiled-against view of `core` is never baked into machine code, so
  adding to `core` cannot invalidate it. **The multi-module rules in §2 are therefore trusted on
  Kotlin/Native as well as the JVM, and lockstep release is no longer load-bearing** — it stays
  the practice because it is simpler, not because the alternative was shown to be unsafe.

  These results cover binary compatibility for Kotlin. Two of the three changes are no longer
  permitted within a major version: a default-bodied member, because §2c found it breaks Swift
  *source* compatibility, which this measurement does not cover, and an enum constant, which
  §2e already forbade. The rig keeps all three as a record of binary compatibility.

- **No Gradle version constraint is warranted, and shipping one would be worse than the risk.**
  What does fail is the *reverse* diamond — a module linked against an `autograph-core` older
  than the one it was compiled against — which the same fixture confirms dies with an
  `IrLinkageError`. Gradle's default conflict resolution picks the highest version, i.e. always
  the safe direction; producing the unsafe one takes a deliberate `strictly` or `force` in the
  consumer's own build. A `strictly` shipped from this side would not prevent that and would
  break consumers doing legitimate alignment, so the honest tool here is documentation, not
  resolution rules. Downgrading one Autograph module below another's version is unsupported.

- **A klib version-skew failure is not a build failure.** Both failing arms above compile and
  link cleanly and throw `IrLinkageError` at the first call. A CI job that builds without
  executing the affected path will not catch it, and the crash names the missing symbol rather
  than anything about the calling code. Worth recognizing on sight.

- The measurement covers one Kotlin major (2.2.20 – 2.4.10); klib is not a frozen format.
  `fixtures/klib-diamond/run.sh` re-runs the whole matrix in about a minute — run it on a Kotlin
  bump, rather than assuming this bullet still holds.

### 5. Removal goes through deprecation, from 1.0 onward

Everything above governs what may be *added*. The rule for taking something away, which applies
once 1.0 ships:

- A public declaration in a tiered artifact is removed only after at least **two minor releases**
  carrying `@Deprecated`, and only in a **major** release. `WARNING` for the first of those minors,
  `ERROR` for the second: a consumer who upgrades one minor at a time meets a warning before a
  compile error, and a compile error before the symbol is gone.
- Every `@Deprecated` carries a `ReplaceWith` when a mechanical replacement exists, and names the
  alternative in prose when it does not. "Deprecated, removed in the next major" with no successor
  is a design gap, not a deprecation.
- The CHANGELOG entry that introduces a deprecation states the release the removal is planned for.
  Moving that target later is fine; moving it earlier is not.
- Before 1.0 this does not apply — the README's stability banner says so, and
  `Modifier.autocaptureScope` was deprecated for removal at 1.0 under the pre-1.0 rule, with no
  window, and removed by #283. It was grandfathered rather than retro-fitted: applying the rule
  above to it would have held a superseded API past the release that is meant to be the clean line.
- `@AutographInternalApi` declarations are exempt, as they are from every other rule here.

## Consequences

- The one irreversible item — `Envelope`/`SessionInfo` construction — is settled before the
  freeze, and the type most likely to grow is the one that can now grow indefinitely.
- Future API decisions become mechanical: identify which of 2a–2f the type falls under and
  the answer follows. #52's timeout, revisited under this ADR, became an
  `AutographConfig` `var` (2b), `closeDrainTimeoutMillis`, when #261 wanted it public.
- Two things are deliberately harder than they were: adding an enum constant, and adding a
  parameter to a config `data class`. Both now cost a major bump, which is the accurate
  price rather than a hidden one.
- Adding a member to an interface that callers implement (§2c/§2d) costs a major version,
  even with a default body. Kotlin alone would allow it, but a Swift conformer would stop
  compiling, so the major version is the real price. A new capability is a separate
  interface probed with `is`, and its fallback is part of its design.
- Freezing `SessionConfig` rather than converting it to the 2b shape is a bet that session
  knobs will be few. If that bet loses, the cost is a slightly awkward split — a session
  setting living on `AutographConfig` next to `session` — not a break.
