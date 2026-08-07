# ADR 0002 — How a release is triggered, and when the tag is created

- **Status:** Proposed — this document is the comparison [#167](https://github.com/uny/autograph/issues/167) asks for in step 1 of its sequencing. **The trigger choice is deliberately left open**; see §"A1 vs A2". The only part implemented alongside this document is §"Do this regardless of A or B", items 3 and 4 (`concurrency` and the version assertions in `cd.yml`), because neither depends on the outcome. Items 1 and 2 are GitHub environment settings and cannot land in a PR.
- **Date:** 2026-08-08
- **Issue:** [#167](https://github.com/uny/autograph/issues/167); related [#166](https://github.com/uny/autograph/pull/166)
- **Reviewed by:** Fable 5 and GPT-5.6 Sol, independently, on the first draft — both GO-WITH-CHANGES, and both refuted the two arguments that draft used to pick a trigger. Their findings are folded in below; §"A1 vs A2" is materially different from the draft as a result.

## Context

### The constraint that forces the whole shape

`Package.swift` must contain the SHA-256 of the exact `Autograph.xcframework.zip` a release
publishes, as a string literal (`private let releaseChecksum`, `Package.swift:30`). SwiftPM
resolves the manifest **as it exists in the tree the tag points at**, so the checksum has to be
in the tagged tree.

Kotlin/Native's output is not reproducible across builds — the comment at `cd.yml:42-47` records
rebuilding the same source on the same machine and getting a different zip checksum each time. So
the checksum is only knowable *after* the build, and the build only happens after the tag is
pushed, because the tag push is the trigger (`cd.yml:3-6`).

The current workflow resolves this circle by moving the tag: it rewrites `Package.swift` with the
checksum it just built, commits, and `git push origin "HEAD:refs/tags/$tag" --force`
(`cd.yml:61-71`). Then a second step replays the same rewrite onto `main` as a *different* commit
(`cd.yml:98-161`), because the force-pushed commit lands on no branch.

### What that costs, measured

Every release tag except the first is unreachable from `main`:

| Tag | Commit | Reachable from `main`? |
|:--|:--|:--|
| `v0.1.0` | `6268e6a` | yes |
| `v0.1.1` | `0b792fd` | **no** |
| `v0.2.0` | `11b0776` | **no** |
| `v0.3.0` | `af9bf1f` | **no** |
| `v0.4.0` | `340a0e7` | **no** |

And `git merge-base --is-ancestor` confirms none of `v0.1.1`/`v0.2.0`/`v0.3.0` is an ancestor of
`v0.4.0` — only `v0.1.0` is, which is exactly why `gh release create --generate-notes` picked
`v0.1.0` as "previous" for every release. #166 fixed that symptom by deriving `--notes-start-tag`
from the sorted tag list instead of trusting ancestry.

What remains broken is not a symptom with a workaround:

- `git log v0.3.0..v0.4.0` and `git diff v0.3.0 v0.4.0` describe a history that never happened.
- `git describe` on `main` finds no tag, so nothing can derive a version from the checkout.
- **A consumer resolving between the tag push and the force-push silently gets the wrong binary.**
  This is worse than the first draft of this ADR said, and worth stating precisely. In that window
  `Package.swift` at the tag still carries the *previous* release's `releaseVersion` and
  `releaseChecksum` — and `releaseVersion` is interpolated into the download URL
  (`Package.swift:36`). So the two values are mutually consistent and point at an asset that
  **exists**. SwiftPM does not error; it resolves the new tag's Swift sources against the previous
  release's Kotlin binary and caches the result. There is no 404 and no self-recovery. No incident
  has been reported across five releases, but the failure is silent, so absence of reports is weak
  evidence.

### The blast radius of changing it

- `publishToMavenCentral(automaticRelease = true)` (`build.gradle.kts:67`) — no staging
  repository to inspect or drop. A publish is final.
- The `release` environment has `protection_rules: []` and `deployment_branch_policy: null`
  (checked via the API on 2026-08-08). No approval gate, no ref restriction.
- `main` has no branch protection and no rulesets — the bot pushes to it directly today.
- `cd.yml` has no `concurrency:` block, unlike `ci.yml` and `docs.yml`.

So this is a change to an irreversible pipeline with no dry-run path and no human gate. That is
the reason for this document rather than a PR.

## The options

### B. Do nothing (or: harden without re-triggering)

The only confirmed *and diagnosed* harm — wrong `--notes-start-tag` — is already fixed. Five
releases have gone out on this pipeline.

This stays a legitimate answer, and both reviewers independently held it open. Two things qualify
it:

- The remaining harm is not purely archaeological. The resolution window above is a silent
  wrong-binary window, not a stale-then-recovers one.
- The damage is cumulative: every additional release adds another orphaned tag. The existing
  orphans `v0.1.1`–`v0.4.0` are permanent under **every** option here — nothing below repairs
  history, it only stops adding to it.

**The hardening in §"Do this regardless" is not part of this choice.** It applies to B as much as
to A, and both reviewers put it first.

### A. Build first, tag once

Reorder to: build → compute checksum → commit `Package.swift` on `main` → create `vX.Y.Z` on that
commit, once, never moved → publish the release with its asset already attached.

This makes the tag an ordinary commit on `main`, restores ancestry, and **deletes the entire
main-sync step** (`cd.yml:98-161`, its three-attempt race loop, and its version-ordering
tie-break) because the tag commit *is* the main commit. That deletion is the largest single
simplification available here.

It cannot keep `on: push: tags: 'v*'` as the trigger, because there is nothing to tag until the
build has run. Hence the two trigger candidates below.

### Options considered and rejected

- **Make the build reproducible** so the checksum can be committed in a PR before tagging.
  The non-reproducibility is measured, and it is Kotlin/Native's, not the zip metadata's. Not ours
  to fix.
- **Compute the checksum in a pre-release PR** and have CD reuse that PR run's artifact instead of
  rebuilding. This genuinely works without reproducibility, and it has a real advantage the first
  draft of this ADR denied: the exact checksum being released is visible in a reviewable diff
  before anything is published. It is rejected for one reason only — CD would depend on
  downloading an artifact produced by a *different* workflow run, which needs the run id threaded
  across workflows and adds a failure mode (artifact missing) whose recovery is "cut a new
  version". That complexity buys less than moving the trigger does.
- **Move the SwiftPM manifest to a separate distribution repo** whose tags are cut post-build,
  leaving this repo's tags checksum-free. A standard pattern, and it would work — but it changes
  the `.package(url:)` every existing Swift consumer already depends on, and it splits the Swift
  sources from the Kotlin sources that must move in lockstep with them.
- **Commit `Autograph.xcframework.zip` into the tagged tree** and use a path-based `binaryTarget`,
  removing the checksum from the problem entirely. Technically sound; rejected because tens of MB
  per release accumulate permanently in git history.

## A1 vs A2 — the trigger

**A1: `workflow_dispatch` with a `version` input.** A human runs the workflow and types `0.5.0`.

**A2: a prep tag.** Pushing `release/v0.5.0` starts CD; CD creates the real `v0.5.0` at the end.

The first draft of this ADR picked A1 on two arguments. **Both reviewers refuted both, and they
are withdrawn:**

- *"A2's retry requires deleting or force-moving the prep tag."* **False.** GitHub Actions
  re-runs a tag-triggered run against the same commit without touching the tag. The prep tag only
  has to move if you want to release a *different* commit — which under A1 is a new dispatch, an
  exactly symmetric operation.
- *"Dry run is nearly free under A1 and costs a second namespace under A2."* **Overstated.** A2
  can carry a `workflow_dispatch` dry-run entry point on the same workflow; nothing about a prep
  tag prevents it.

What survives, honestly:

| | A1 `workflow_dispatch` | A2 prep tag |
|:--|:--|:--|
| Version source | free-text input | parsed from the prep tag name |
| Build commit pinned by the trigger | `main`'s tip at run start | the prep-tagged commit, durably |
| Namespaces to reason about | one | two |
| Durable record of an attempt | run log only | the prep tag survives the run |
| Retry | re-run the workflow | re-run the workflow |
| Dry run | an input | an input |

**The main-moving race is closed identically under both**, so it is not a discriminator. Push the
checksum commit to `main` as a **fast-forward only**, and fail the run if it is rejected. Under A1
the built tree is `$GITHUB_SHA`; under A2 it is the prep-tagged commit; a non-fast-forward push
means `main` moved and the run must stop. This is fail-closed and replaces the three-attempt loop.
Note what it does *not* recover: A2's prep tag is a durable ref and an audit record of the attempt,
and A1 has no equivalent.

**The typo risk of A1's free-text input is closable**, so it is not a discriminator either. Four
cheap assertions run before anything irreversible: semver regex; `CHANGELOG.md` contains a
`## [X.Y.Z]` heading (the released-section format, `CHANGELOG.md:31`); the tag does not exist; the
version sorts above every existing `v*` tag. A2 needs the same four.

**Conclusion: this is close, and this ADR does not claim otherwise.** A1 is preferred by a narrow
margin — one tag namespace instead of two, and no stray `release/v*` refs to garbage-collect —
and that is the whole of the case. Anyone who weights the durable audit ref higher should pick A2;
the rest of this document applies unchanged to either.

## Do this regardless of A or B

Both reviewers put these first, and none of them depends on the trigger question.

1. **A human gate**, since there is no staging gate. Add `required_reviewers` to the `release`
   environment (currently `protection_rules: []`). This repository is public, so environment
   protection rules are available on the Free plan, and a sole owner can approve their own
   deployment.
2. **`deployment_branch_policy`** on the `release` environment, currently `null`, meaning any ref
   can deploy.
3. **`concurrency: { group: release, cancel-in-progress: false }`** in `cd.yml`, which `ci.yml`
   and `docs.yml` already have and `cd.yml` does not.
4. **The four version assertions**, which are useful under the current trigger too.

## What A must carry before it can replace the current pipeline

1. **Publish through a draft release, so the tag never exists without its asset.** GitHub does not
   create the git tag for a *draft* release until it is published. So: push the checksum commit to
   `main` → create a **draft** release with `--target <that commit's SHA>` → upload the asset to
   the draft → publish. At the instant `vX.Y.Z` becomes visible, the asset it names is already
   there. The naive order (tag → release → upload) reopens a resolution window of exactly the kind
   §Context describes.
2. **Maven publish goes last, and the claim about what that buys must be stated accurately.**
   Today it runs first (`cd.yml:30`), so a later failure leaves a version on Maven Central that
   cannot be re-published and cannot be reproduced. Moving it last means the *unrecoverable* step
   is last. It does **not** mean earlier failures publish nothing — by then the commit, tag,
   release and asset are all public. It means an earlier failure is recoverable by cutting the
   next patch version, and a failure at Maven itself is resumable.
3. **Resume must branch before the build, not after.** A re-run after a Maven failure would
   otherwise rebuild, get a different checksum from Kotlin/Native, and produce a second rewrite
   commit — at which point the "tag must point at the expected commit" assertion in point 4 fires
   and the resume can never succeed. So: if the tag already exists **and** `Package.swift` at that
   tag already names this version, skip the build, the rewrite, the tag and the release entirely,
   and run only the Maven publish. This is sound because the Maven artifacts are not
   byte-coupled to the xcframework zip — the checksum contract is SwiftPM's alone — so publishing
   them from a later build is legitimate. Say so in the recovery doc, because it is the
   non-obvious part.
4. **Every write step idempotent and asserted, not inferred.** Tag creation skips if the tag
   exists *and points at the expected commit*, and fails otherwise. Never re-upload an existing
   asset: a rebuild's zip has a different checksum, and `--clobber` would break a previously-good
   release. Use `--verify-tag` on the release call so it can never silently create a tag at the
   default-branch tip (`target_commitish` is ignored once a tag exists). Keep #166's explicit
   `--notes-start-tag`: ancestry-based resolution stays wrong until the orphaned `v0.1.1`–`v0.4.0`
   fall out of range.
5. **A written manual recovery path** for "Maven published, everything else failed", produced
   before cutover rather than during one.
6. **A merge freeze during a release run.** The fast-forward-only push is fail-closed, which is
   correct, but a Kotlin/Native build is long and `main` moves often here. Without a freeze the
   realistic failure is a long build discarded because a PR merged during it.
7. **Validate on a dry run first** — build, checksum, and the fast-forward check against a scratch
   ref, end to end, before the first real cutover release.

## Consequences

- `cd.yml:98-161` — the main-sync step, its retry loop, and its version-ordering tie-break — is
  deleted, not modified. The class of bug it defends against stops existing.
- `Package.swift:21-27`'s comment, which currently explains the tag-move to readers as intended
  behavior, is rewritten. So is `cd.yml:42-47`.
- Releases stop being "push a tag" and become "run a workflow". A real ergonomic loss.
- **Two capabilities are lost, and this is irreversible without another redesign.** The current
  trigger can tag *any* commit, including one on a hotfix branch; fast-forward-only-onto-`main`
  can only release `main`'s tip. And the "sorts above every existing tag" assertion structurally
  forbids an old-series patch (a `0.4.1` after `1.0.0` ships). Both are harmless under the
  single-line 0.x release history this project has, and both would need revisiting before a
  maintenance branch exists.
- The orphaned tags `v0.1.1` through `v0.4.0` stay orphaned. `git log v0.3.0..v0.4.0` is wrong
  forever; only ranges from the first post-cutover tag onward are trustworthy.
- The cutover release is the highest-risk release this project will have done, on a pipeline with
  no staging gate. Point 7 is not optional.
