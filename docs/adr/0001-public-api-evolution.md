# ADR 0001 — How the public API may evolve after 1.0

- **Status:** Accepted
- **Date:** 2026-07-21
- **Issue:** [#53](https://github.com/uny/autograph/issues/53)

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
  were both added after the fact as members with default bodies, and neither broke an
  implementor.
- The absence of a policy already cost us a decision. [#52](https://github.com/uny/autograph/issues/52)
  kept `close()`'s drain timeout as a `private const` rather than a public knob
  specifically because there was no rule for what adding a public knob would commit us to.

## Decision

### 1. Stability is declared per artifact, not for the project

| Artifact | Guarantee |
|:--|:--|
| `autograph-core`, `autograph-context` | **SemVer ABI stable.** Binary-compatible within a major version; this ADR's rules apply in full. |
| `autograph-compose`, `autograph-uikit`, `autograph-android`, `autograph-segment` | **Stable API, constrained toolchain.** The same source and binary rules apply, but each is bounded by a dependency it does not control (the Compose compiler, the iOS SDK / Xcode, the Android SDK and AGP, the Segment SDK — one each, respectively). A major bump in one of those may force a break that is not ours to schedule. |
| `autograph-test`, `autograph-schema` | **Source-compatible on a best-effort basis; no ABI guarantee.** These are development-time helpers — a test transport and a code generator. They are not on an app's release-critical path, so freezing them would buy safety nobody is asking for. `autograph-schema` therefore does not run `abiValidation()`, and that is deliberate rather than an oversight. |

Declarations marked `@AutographInternalApi` are outside every tier. They are `public`
only because Kotlin's `internal` does not cross a module boundary, and may change in a
patch release.

**Two modules are not covered by the dump check**, so the review checkpoint of §4 does not
fire for them and their rules rest on review alone: `autograph-schema` does not run
`abiValidation()` (deliberate, per the tier above), and `autograph-android` runs it but its
sole AGP `android {}` target produces no dump — `checkKotlinAbi` passes on an empty `api/`
directory. That second one is a gap rather than a decision;
[#106](https://github.com/uny/autograph/issues/106) tracks closing it.

### 2. Public types are classified by *who constructs* and *who implements* them

That question, not the type's shape, determines what can be added to it later. The
categories below are exhaustive over the public surface of the ABI-stable tier — every
public declaration in `autograph-core` and `autograph-context` falls under exactly one, and
a new one that fits none is the signal that this ADR needs another category rather than an
exception.

#### 2a. Library-produced value types — construction is frozen

`Envelope`, `SessionInfo`; and, in `autograph-context`, `ScopeHandle` and `AmbientContext`.

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

`ScopeHandle` and `AmbientContext` are in this category by the same test — the library
produces them, callers only read them — but are plain classes with an `internal`
constructor rather than the `data class` shape above, so they never had `copy` or
`componentN` to lose and gain properties freely. A library-produced type that does *not*
need value equality should prefer that plainer shape; `@ConsistentCopyVisibility` is only
needed to keep `data class` from re-exposing construction through `copy`.

#### 2b. Caller-constructed configuration — carried by a mutable-property receiver

`AutographConfig` is the model: `internal constructor`, knobs as `var`s, populated through
the `Autograph { }` DSL lambda. A new knob is a new `var` — no ABI break, ever. (`transport`
is the exception that proves the shape: it is a `public fun` rather than a `var` so the
backing field can stay `internal`. A new member function is equally additive.)

**New configuration knobs go on `AutographConfig`.** A knob is not given its own
`data class` unless it has enough internal structure to justify one, because a `data class`
constructor cannot gain a parameter without breaking ABI: adding a defaulted parameter
changes the synthetic `DefaultConstructorMarker` bridge.

The existing caller-constructed carriers — `SessionConfig`, `SeqPersistence.Chunked`,
`ScreenContext`, `AutocaptureConfig` — are **frozen at their current shape**. In
particular `SessionConfig` keeps its single `backgroundTimeout` parameter; a future
session knob (foreground timeout, maximum session length, new-session-on-launch) is added
to `AutographConfig`, not to `SessionConfig`.

#### 2c. Caller-implemented SPIs — additions must carry a safe default body

`Transport`, `SeqStore`, `EventIdGenerator`, `EventValidator`, `SegmentBridge`.

Every member added to one of these after 1.0 ships with a default body. The default must
be **correct, not merely compiling**: `SeqStore.flush()`'s no-op default is acceptable only
because it is genuinely right for a synchronous store, and an implementor that inherits it
loses nothing.

When a new capability has no default that is safe for an implementor who has never heard of
it, it does **not** become a member. It becomes a separate optional interface that the core
probes with `is`:

```kotlin
public interface SomethingCapable { public fun something() }
// core: if (transport is SomethingCapable) transport.something()
```

The rule exists because a default body that silently does the wrong thing is worse than a
compile error — it converts a break we would have caught into data loss we would not.

#### 2d. Caller-called interfaces — same default-body rule

`Tracker`, `EnvelopeSource`.

These are called rather than implemented in normal use, but consumers legitimately fake
`Tracker` in their own tests. Converting them to an abstract class with an internal
constructor — which would let us add abstract members freely — would break that, so it is
rejected. They follow 2c's rule instead.

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

`ScopeStack`, `ScreenHistory` (in `autograph-context`), `DebugTransport`.

The caller both constructs these and calls them, but unlike 2b they carry behavior rather
than configuration, and unlike 2a they are not values. Because they are `final` classes
nobody implements, **adding a public member is binary-additive and allowed** — the freedom
2c and 2d have to buy with a default body. What is frozen is the *constructor*: these have
public constructors, so a new parameter is an ABI break exactly as in 2b. A construction
argument they may later need is therefore either given a default at a call site the library
controls, or moved onto `AutographConfig`.

Stateless entry points — the `Autograph { }` builder function, `EventId`, `EmptyJsonObject`,
`platformSeqStore()` — carry no construction contract at all. Adding a new top-level
function or `object` member beside them is additive; changing an existing signature is a
break, with no rule beyond that.

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
- **Kotlin/Native is the weak point, and we have not measured it.** Kotlin guarantees klib
  *backward* compatibility from 1.9.20 and explicitly does not guarantee forward
  compatibility, so the mixed-version diamond — an app resolving `autograph-core:1.1` while
  linking an `autograph-segment:1.0` klib compiled against `1.0` — is not known to be safe
  even for a change the JVM tolerates. Until it is measured, all modules are released in
  lockstep at the same version and the multi-module rules above are trusted only on the
  JVM. [#104](https://github.com/uny/autograph/issues/104) is the follow-up: build a fixture
  that links an old dependent against a new core and see what actually happens, rather than
  writing a constraint we guessed at.

## Consequences

- The one irreversible item — `Envelope`/`SessionInfo` construction — is settled before the
  freeze, and the type most likely to grow is the one that can now grow indefinitely.
- Future API decisions become mechanical: identify which of 2a–2e the type falls under and
  the answer follows. #52's timeout, revisited under this ADR, would be an
  `AutographConfig` `var` (2b) if it is ever wanted publicly.
- Two things are deliberately harder than they were: adding an enum constant, and adding a
  parameter to a config `data class`. Both now cost a major bump, which is the accurate
  price rather than a hidden one.
- Freezing `SessionConfig` rather than converting it to the 2b shape is a bet that session
  knobs will be few. If that bet loses, the cost is a slightly awkward split — a session
  setting living on `AutographConfig` next to `session` — not a break.
