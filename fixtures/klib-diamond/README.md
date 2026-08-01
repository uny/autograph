# klib mixed-version diamond

Measures what ADR 0001 §4 could previously only guess at: whether the multi-module evolution
rules survive on Kotlin/Native when an app resolves a newer `autograph-core` than the
`autograph-segment` klib in its graph was compiled against.

Answers [#104](https://github.com/uny/autograph/issues/104).

## Running it

```bash
./fixtures/klib-diamond/run.sh          # no compiler skew: the repo's Kotlin throughout
./fixtures/klib-diamond/run.sh 2.2.20   # build the OLD half with an older Kotlin
```

Publishes to the local Maven repository under group `fixture`, which nothing else uses. Takes
about a minute warm. **Not wired into per-PR CI on purpose**: Kotlin/Native link is already the
CI critical path, and this answers a question that only moves when the toolchain does. Run it
when bumping Kotlin, or before a release that relaxes an ADR 0001 rule.

## Shape

Three standalone Gradle builds, so the diamond resolves from published coordinates rather than
project dependencies — a project dependency would recompile the dependent and dissolve the whole
question.

- `core` — the library under test, published three times from three source generations:
  - `1.0` (`src/v1`) baseline: a `Transport` SPI, an `Envelope` with an `internal` constructor,
    an enum.
  - `1.1` (`src/v2`) adds exactly the three things ADR 0001 permits without a major bump: a
    default-bodied member on the SPI (§2c), a property on the internal-constructor class (§2a),
    an enum constant (§2e).
  - `1.2` (`src/v3`) **negative control** — `Transport.send` gains a required parameter. A real
    ABI break, present only to prove the fixture can go red.
- `dependent` — an SPI implementor in another module, compiled against `core:1.0`, published as
  `dependent:1.0`, and never recompiled. Stands in for `autograph-segment`.
- `dependent-new` — the same artifactId at `1.1`, compiled against `core:1.1` and using members
  that exist only there. Stands in for a later release of that module.
- `consumer` — runs tests on `macosArm64` and `iosSimulatorArm64`.

## Results

Measured 2026-08-02, Kotlin 2.4.10 consumer, Xcode 26.3. Verified on both targets separately, and
unchanged with the old half built by Kotlin 2.2.20, 2.3.0, or 2.4.0.

| Arm | Graph | Outcome |
| --- | --- | --- |
| `upgrade` | `dependent:1.0` (built against core 1.0) + `core:1.1` | **Works.** All three permitted changes link and run. |
| `downgrade` | `dependent:1.1` (built against core 1.1) + `core:1.0`, forced | **Fails**, at runtime: `IrLinkageError`. |
| `break` (control) | `dependent:1.0` + `core:1.2` | **Fails**, at runtime: `IrLinkageError`. |

Verbatim, from the control:

```
kotlin.internal.IrLinkageError: Abstract function 'send' is not implemented
in non-abstract class 'DependentTransport'
```

and from the downgrade:

```
kotlin.internal.IrLinkageError: Property accessor 'extra.<get-extra>' can not be called:
No property accessor found for symbol 'fixture/Envelope.extra.<get-extra>|<get-extra>(){}[0]'
```

### Why the upgrade direction is safe

Kotlin/Native does not load a klib the way a JVM loads a class file. A klib carries serialized
IR, and the link step re-lowers every module in the graph together against whichever `core`
actually resolved. A symbol that still exists is simply re-resolved; the dependent's own
compiled-against view of `core` is not baked into machine code, so adding to `core` cannot
invalidate it.

That mechanism explains all three arms at once, which is the reason to trust the green ones:
adding members leaves every existing symbol resolvable, while removing or re-signing one leaves
a dangling reference.

### Two things that are *not* build failures

Both failing arms **compile and link successfully** and throw at first use. `IrLinkageError` is
deferred to the call site, so a CI job that builds but does not execute the affected path will
not catch either. Worth knowing when reading a crash report: this error means version skew in
the dependency graph, not a bug in the calling code.

### The enum case

The added constant links and runs. What breaks is narrower and not klib-specific: an exhaustive
`when` in the old klib, compiled when the enum had two constants, throws
`NoWhenBranchMatchedException` when handed the third. The JVM behaves the same way. ADR 0001 §2e
already freezes the case set for the major version, so this confirms that rule rather than
changing it.

## Scope, and what would invalidate this

- One Kotlin major (2.2.20 – 2.4.10). klib is not a frozen format; re-run on a Kotlin bump. That
  is what `run.sh` is for.
- The XCFramework consumption path is unaffected either way: CD builds every module at one
  version into a single `Autograph.xcframework`, so a Swift consumer never links klibs and the
  diamond cannot arise there.
- The consumer's Kotlin version is read from `gradle/libs.versions.toml`, not pinned in the
  script. Pinning it would quietly defeat the instruction above: re-running after a Kotlin bump
  would re-measure the old compiler and report a reassuring green about nothing.
- **A false green is the standing hazard here, and it caught this fixture four times while it was
  being built.** Two of the three arms *expect* a non-zero exit, so almost any breakage produces
  the result they were looking for. Each guard in `run.sh` exists because the corresponding
  mistake was actually made:
  - The downgrade arm asked for an artifactId that had never been published, failed dependency
    resolution, and reported `expected=fail actual=fail [OK]` while measuring nothing. Every
    coordinate an arm rests on is now asserted to be in the resolved graph — *both* sides of the
    diamond, since `break` would otherwise be satisfied by resolving the unbroken core.
  - A failed publish let the run continue against stale artifacts from a previous run.
    `publish()` now aborts.
  - Both targets ran in one Gradle invocation, and Gradle stops at the first failing task — so
    `iosSimulatorArm64Test` never ran on either failing arm, making "identical on both targets"
    really "verified on macOS" for two of the three. One invocation per target now, reported per
    target.
  - A failing arm counts only if it failed *with* `IrLinkageError`. A compile error, an
    unavailable simulator or a Gradle crash all exit non-zero and would otherwise pass for the
    linkage failure the negative control rests on.
