# ADR 0002 — How a release is triggered, and when the tag is created

- **Status:** Proposed — this document is the comparison [#167](https://github.com/uny/autograph/issues/167) asks for in step 1 of its sequencing. **The trigger choice is deliberately left open**; see §"A1 vs A2". The only part implemented alongside this document is §"Do this regardless of A or B", items 3 and 4 (`concurrency` and the version assertions in `cd.yml`), because neither depends on the outcome. Item 1 has since been applied as a GitHub environment setting (`required_reviewers`, with self-review permitted since there is one maintainer); item 2 is deliberately not, see that section. Point 7 of §"What A must carry" — the dry-run path — also exists now.
- **Date:** 2026-08-08
- **Issue:** [#167](https://github.com/uny/autograph/issues/167); related [#166](https://github.com/uny/autograph/pull/166)
- **Reviewed by:** Fable 5 and GPT-5.6 Sol, independently, on the first draft — both GO-WITH-CHANGES, and both refuted the two arguments that draft used to pick a trigger. Their findings are folded in below; §"A1 vs A2" is materially different from the draft as a result.

## Context

### The constraint that forces the whole shape

`Package.swift` must contain the SHA-256 of the exact `Autograph.xcframework.zip` a release
publishes, as a string literal (`private let releaseChecksum`, `Package.swift:30`). SwiftPM
resolves the manifest **as it exists in the tree the tag points at**, so the checksum has to be
in the tagged tree.

Kotlin/Native's output is not reproducible across builds — the comment on `cd.yml`'s "Publish
Autograph.xcframework binary target" step records rebuilding the same source on the same machine
and getting a different zip checksum each time. So the checksum is only knowable *after* the build,
and the build only happens after the tag is pushed, because the tag push is the trigger.

The current workflow resolves this circle by moving the tag: it rewrites `Package.swift` with the
checksum it just built, commits, and `git push origin "HEAD:refs/tags/$tag" --force`
in the same step. Then "Sync Package.swift's binary target onto main" replays that rewrite onto
`main` as a *different* commit, because the force-pushed commit lands on no branch.

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
  `Package.swift` at the tag still carries whatever `releaseVersion` and `releaseChecksum` the
  commit being tagged inherited — and `releaseVersion` is interpolated into the download URL
  (`Package.swift:36`). So the two stale values are mutually consistent and point at an asset that
  **exists**. SwiftPM does not error; it resolves the new tag's Swift sources against an older
  release's Kotlin binary and caches the result. There is no 404 and no self-recovery.

  Not "the previous release", either — measured at each tag's parent commit, the window served
  `v0.1.0` for `v0.1.1`, `v0.2.0` **and** `v0.3.0`, and `v0.3.0` for `v0.4.0`. The first three are
  three releases stale, because until the main-sync step existed nothing advanced `main`'s copy of
  those values at all. No incident has been reported across five releases, but the failure is
  silent, so absence of reports is weak evidence.

### The blast radius of changing it

- `publishToMavenCentral(automaticRelease = true)` (`build.gradle.kts:67`) — no staging
  repository to inspect or drop. A publish is final.
- The `release` environment has `protection_rules: []` and `deployment_branch_policy: null`
  (checked via the API on 2026-08-08). No approval gate, no ref restriction.
- `main` has no branch protection and no rulesets — the bot pushes to it directly today.
- `cd.yml` had no `concurrency:` block and nothing validated the pushed tag. Both are fixed by the
  change that lands with this document (§"Do this regardless"), so they describe the situation this
  ADR was written against, not the current one.

So this is a change to an irreversible pipeline with no dry-run path and no human gate. That is
the reason for this document rather than a PR.

**`cd.yml` is cited by step name, not line number**, because this document's own change shifted
every line reference in it twice before landing.

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
main-sync step** — `cd.yml`'s "Sync Package.swift's binary target onto main", its three-attempt
race loop, and its version-ordering tie-break — because the tag commit *is* the main commit. That
deletion is the largest single simplification available here.

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

**The typo risk of A1's free-text input is closable**, so it is not a discriminator either. Three
of the four cheap assertions below already run today: semver regex; `CHANGELOG.md` contains a
`## [X.Y.Z] - <date>` heading (the released-section format `CHANGELOG.md` already uses); the version sorts
above every existing `v*` tag. A design that creates the tag itself adds a fourth — the tag must
not already exist — which is structurally meaningless under the current trigger, where the tag's
existence is what started the run. A2 needs all four too.

**Conclusion: this is close, and this ADR does not claim otherwise.** A1 is preferred by a narrow
margin — one tag namespace instead of two, and no stray `release/v*` refs to garbage-collect —
and that is the whole of the case. Anyone who weights the durable audit ref higher should pick A2;
the rest of this document applies unchanged to either.

## Do this regardless of A or B

Both reviewers put these first, and none of them depends on the trigger question.

1. **A human gate**, since there is no staging gate. **Done** — the `release` environment carries
   `required_reviewers` as of 2026-08-08, where it previously had `protection_rules: []`. This
   repository is public, so environment protection rules are available on the Free plan.
   `prevent_self_review` is deliberately `false`: with one maintainer, `true` would mean nobody
   could ever approve a release.
2. **`deployment_branch_policy`**, still `null`, meaning any ref can deploy. **Deliberately left
   alone for now.** With the approval gate in place its marginal value is small — the only path to
   this environment is `cd.yml`'s publish job, which a `v*` tag triggers and a human now has to
   approve — while a mistyped tag pattern would block releases entirely, and would not be
   discovered until one was attempted. Worth adding once there is a way to verify it without
   spending a release on the experiment.
3. **`concurrency: { group: release, cancel-in-progress: false, queue: max }`** in `cd.yml`.
   `docs.yml` is the existing precedent for serializing this way; `ci.yml` has a concurrency block
   too but a different one (per-ref, `cancel-in-progress: true`), which supersedes rather than
   serializes. `queue: max` is not optional garnish: `cancel-in-progress: false` protects only the
   *running* release, and the default `queue: single` holds one pending run and cancels it when
   another arrives — so a third tag would silently drop the second release entirely. `max` queues
   up to 100 and runs them in order, and cannot be combined with `cancel-in-progress: true`.
4. **The three version assertions** — shape, changelog section, ordering. All three are useful
   under the current trigger, which is why they land now rather than with the redesign.

## What A must carry before it can replace the current pipeline

1. **Close the window between the tag appearing and its asset existing.** The naive order
   (tag → release → upload) reopens a resolution window of exactly the kind §Context describes:
   for as long as the upload takes, `vX.Y.Z` resolves to a manifest naming an asset that is not
   there yet.

   The intended shape is: push the checksum commit to `main` → create a **draft** release with
   `--target <that commit's SHA>` → upload the asset to the draft → publish, on the premise that
   a draft release does not create its git tag until it is published.

   **That premise is contested and must be measured before it is designed against.** CodeRabbit's
   review of this document asserts the opposite — that `gh release create` creates the tag
   whenever it does not exist, drafts included — and GitHub's REST documentation for releases does
   not state either way. If the draft does create the tag immediately, this ordering buys nothing
   and the window has to be closed differently: upload the asset to a release created against a
   tag that is pushed only after the upload succeeds, or accept the window and document it. **This
   is the single most important thing to settle before A is built**, because the rest of this
   section's ordering depends on the answer. The dry run that exists today does not settle it — it
   creates no tag and no release; see point 7.
2. **Maven publish goes last, and the claim about what that buys must be stated accurately.**
   Today it runs first, so a later failure leaves a version on Maven Central that cannot be
   re-published and cannot be reproduced. Moving it last means the *unrecoverable* step
   is last. It does **not** mean earlier failures publish nothing — by then the commit, tag,
   release and asset are all public. It means an earlier failure is recoverable by cutting the
   next patch version, and a failure at Maven itself is resumable.
3. **Resume must branch before the build, not after.** A re-run after a Maven failure would
   otherwise rebuild, get a different checksum from Kotlin/Native, and produce a second rewrite
   commit — at which point the "tag must point at the expected commit" assertion in point 4 fires
   and the resume can never succeed. So the resume branch runs only the Maven publish, and it must
   establish that **everything before Maven genuinely completed** before deciding that: the tag
   exists, `Package.swift` at that tag names this version, the release exists and is *published*
   rather than still a draft, and it carries the `Autograph.xcframework.zip` asset. Anything short
   of all four is a failure somewhere earlier, and the resume path is the wrong one for it.
   Skipping on the first two alone would treat "the asset upload failed" as "only Maven is left".

   Running Maven from a later build is legitimate despite Kotlin/Native's non-determinism, because
   the Maven artifacts are not byte-coupled to the xcframework zip — the checksum contract is
   SwiftPM's alone. That is the non-obvious part, and the recovery doc should say it.
4. **Every write step idempotent and asserted, not inferred.** Tag creation skips if the tag
   exists *and points at the expected commit*, and fails otherwise. Never re-upload an existing
   asset: a rebuild's zip has a different checksum, and `--clobber` would break a previously-good
   release. Keep #166's explicit `--notes-start-tag`: ancestry-based resolution stays wrong until
   the orphaned `v0.1.1`–`v0.4.0` fall out of range.

   The threat `--verify-tag` guards against is real and documented: GitHub's release API says of
   `target_commitish` that it is "Unused if the Git tag already exists", with the default being
   the repository's default branch — so a release call that does not pin the commit can silently
   attach itself to `main`'s tip. But **`--verify-tag` is mutually exclusive with the draft flow
   of point 1**, since it aborts unless the tag already exists. In that flow the same protection
   comes from `--target <the built commit's SHA>` plus an assertion after publish that the tag now
   exists and points at that SHA. Use `--verify-tag` only in a design that pushes the tag itself
   before creating the release — and note that point 1's shape is not settled yet.
5. **A written manual recovery path** for "Maven published, everything else failed", produced
   before cutover rather than during one.
6. **A merge freeze during a release run.** The fast-forward-only push is fail-closed, which is
   correct, but a Kotlin/Native build is long and `main` moves often here. Without a freeze the
   realistic failure is a long build discarded because a PR merged during it.
7. **Validate on a dry run first**, before the first real cutover release.

   **A dry-run path now exists**: `release-dry-run.yml`, `workflow_dispatch` with a version input,
   running the same validation script `cd.yml` runs — not a copy — then a publish of every module
   to a local repository, the xcframework build and the checksum computation, and stopping there.
   It holds no publishing credential — no Maven Central login and no release signing key — does
   not use the `release` environment, and uploads nothing; its only credential is the automatic
   `github.token` at `contents: read`, which is what lets the validation list existing tags. The
   local publish signs with a key generated inside the job, which adds no repository secret and no
   publish target, because `signAllPublications()` is unconditional and measurement showed no
   keyless publish to fall back on (#176). That is what makes it safe to run at any time. It is a
   local publish, not the release's: the Central-repository tasks, the release key's id/passphrase
   paths and Central's own POM validation still first run during a release.

   It does **not** yet cover the parts of A that do not exist: the fast-forward push to `main`,
   tag creation, and the release/draft sequence of point 1. Those get added to it as A is built,
   and the draft-release premise §"What A must carry" flags as contested is the first thing they
   should settle. The build and validation halves are covered today, which is the half that would
   otherwise first run during an irreversible release.

**One premise above is GitHub's behavior rather than this repository's, and it is unverified and
actively contested:** whether a draft release defers git tag creation until publish (point 1).
GitHub's release documentation does not say, and a review of this document asserted the opposite.
It is **still unmeasured**: the dry run that now exists stops after validation, the local publish,
the build and the checksum, so it never creates a tag or a release and cannot answer this. Do not
design against the premise before it is settled — either by the probe below, or by the dry run
once it grows the release sequence point 7 leaves for later. The other external premise, that
`target_commitish` is unused once the tag exists, *is* documented, and is quoted at point 4.

Both are cheap to settle, and settling them does not require a real release — creating a draft
release against a throwaway tag name and checking whether `refs/tags/<name>` appears answers point
1 in one call, and deleting the draft afterwards leaves nothing behind. That was not done here
because it writes to the repository's releases, which is the owner's call, not a reviewer's.

## Consequences

- `cd.yml`'s "Sync Package.swift's binary target onto main" step, its retry loop, and its
  version-ordering tie-break, are deleted rather than modified. The class of bug they defend
  against stops existing.
- `Package.swift:19-28`'s comment, which currently explains the tag-move to readers as intended
  behavior, is rewritten. So is the comment on its "Publish Autograph.xcframework binary target" step.
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
