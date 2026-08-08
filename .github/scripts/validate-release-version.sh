#!/usr/bin/env bash
#
# Refuse a release version that should not be published.
#
# Publishing runs with `publishToMavenCentral(automaticRelease = true)`, which leaves no staging
# repository to inspect or drop, so by the time anything is wrong it is already permanent. This is
# the gate in front of that. It lives in a script rather than inline in cd.yml for three reasons:
# cd.yml only ever runs on a release tag and so can never be exercised in CI, the release dry-run
# workflow needs the identical checks rather than a copy that drifts, and a script can be tested —
# see validate-release-version.test.sh, which runs on every PR.
#
# Usage:  validate-release-version.sh vX.Y.Z
#
# Reads CHANGELOG.md from the current directory, so run it from the repository root.
# Needs `gh` authenticated, and $GITHUB_REPOSITORY (set automatically in Actions; derived from the
# `origin` remote otherwise).

set -euo pipefail

tag="${1:-}"
if [ -z "$tag" ]; then
  echo "usage: $(basename "$0") vX.Y.Z" >&2
  exit 2
fi

# The `v` is required rather than optional. Everything downstream compares $tag against real tag
# names — `grep -vFx` to exclude this release from the list, `sort -V` to order it against the rest
# — and a bare `0.5.0` matches none of them and sorts before all of them. It would still fail
# closed, but for the wrong reason and with a message that blames the version ordering. Reject the
# shape up front instead of explaining the wrong thing later.
if [ "${tag#v}" = "$tag" ]; then
  echo "usage: $(basename "$0") vX.Y.Z — the leading v is required (got '$tag')" >&2
  exit 2
fi
version="${tag#v}"

# `::error::` is a GitHub Actions annotation and plain text everywhere else, which is why the
# message has to read correctly on its own rather than relying on the annotation for context.
fail() {
  echo "::error::$1" >&2
  exit 1
}

# No leading zeros: v01.2.3 and v1.2.3 are different Maven coordinates for the same intent, and
# `sort -V` orders them apart. This also rejects a prerelease tag (v0.5.0-rc1) — cd.yml triggers on
# `v*`, so pushing one starts a release and it stops here rather than publishing something this
# project's single release line has no place for.
if ! printf '%s' "$version" | grep -qE '^(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*)){2}$'; then
  fail "$tag is not vMAJOR.MINOR.PATCH; refusing to publish it."
fi

# The check a regex cannot make. v0.41.0 for v0.4.1 is perfectly well-formed, and the only thing
# in the repository that knows which was meant is the changelog. The dots are escaped because they
# are pattern metacharacters here, unlike the -F match further down.
if ! grep -qE "^## \[${version//./\\.}\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" CHANGELOG.md; then
  fail "CHANGELOG.md has no released section for $version. Move [Unreleased] to ## [$version] - <date> before tagging."
fi

repo="${GITHUB_REPOSITORY:-}"
if [ -z "$repo" ]; then
  repo=$(git remote get-url origin | sed -E 's#(git@github\.com:|https://github\.com/)##; s#\.git$##')
fi

# Fetching and filtering are separate on purpose, and this is the subtlest part of the script.
# Collapsed into one pipeline, a failed `gh` would leave the assignment exiting 0 with an empty
# value — `pipefail` reports the *last* command's status, and `tail` always succeeds — so an
# emptiness test below would read "the API is down" as "there are no other tags" and wave the
# release through. Fail-open, on the one gate standing in front of an irreversible publish.
#
# `--paginate` can also die partway with earlier pages already written to stdout, so a non-empty
# result is not evidence of a complete one either. Checking the exit status covers both.
if ! all_tags=$(gh api "repos/${repo}/tags" --paginate --jq '.[].name'); then
  fail "Could not list existing tags; refusing to publish without the version-ordering check."
fi

# `|| true` because `grep` legitimately matches nothing when the tag being released is the only one
# that exists, and under `pipefail` that would otherwise abort the script. It is safe here, and
# only here, because the fetch above already proved the API answered — so an empty result now
# really does mean "no other tags" rather than "we never found out".
#
# Ordering is a separate statement so that `|| true` covers only the greps it is there for. With
# `sort -V | tail -1` inside the same pipeline it would swallow their failures too, and a `sort`
# that died would leave $highest empty — which reads as "no existing release" and skips the check
# below. No way to make that happen has been found, but a fail-open path on this particular gate
# is not worth keeping merely because it looks unreachable.
release_tags=$(printf '%s\n' "$all_tags" \
  | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' \
  | grep -vFx "$tag" || true)
highest=$(printf '%s\n' "$release_tags" | sort -V | tail -1)

# Deliberately forbids an old-series patch — a v0.4.1 cut after v1.0.0 shipped. This project has a
# single release line and no maintenance branch; ADR 0002 records the restriction so that the day
# a maintenance branch is wanted, this is a known thing to revisit rather than a surprise.
if [ -n "$highest" ] && [ "$(printf '%s\n%s\n' "$highest" "$tag" | sort -V | tail -1)" != "$tag" ]; then
  fail "$tag sorts below the existing $highest; refusing to publish it."
fi

echo "$tag validated: shape, changelog section, and ordering above ${highest:-no existing release}."
