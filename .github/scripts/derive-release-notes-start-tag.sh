#!/usr/bin/env bash
#
# Print the release tag that precedes the one being released, for `gh release create
# --notes-start-tag`.
#
# `--generate-notes` with no explicit start tag picked v0.1.0 as the "previous" release for every
# tag so far (v0.2.0, v0.3.0, v0.4.0) instead of the actual preceding one, so cd.yml derives it
# here rather than trusting gh's resolution. It lives in a script for the same reasons
# validate-release-version.sh does: cd.yml only ever runs on a release tag, so this code can never
# be exercised in CI, and a script can be tested — see derive-release-notes-start-tag.test.sh,
# which runs on every PR. That matters more than it sounds: the derivation this replaced was wrong,
# and its replacement has never executed in a real release either (it landed after v0.4.0 shipped).
#
# Usage:  derive-release-notes-start-tag.sh vX.Y.Z
#
# Prints the preceding release tag on stdout, or nothing at all when this is the first release —
# in which case the caller should fall back to a plain `--generate-notes`. Exits non-zero if the
# tag list could not be obtained, so the caller can tell "there is no previous tag" apart from
# "we never found out".
#
# Needs `gh` authenticated, and $GITHUB_REPOSITORY (set automatically in Actions; derived from the
# `origin` remote otherwise).

set -euo pipefail

tag="${1:-}"
if [ -z "$tag" ]; then
  echo "usage: $(basename "$0") vX.Y.Z" >&2
  exit 2
fi

# The shape is checked here even though cd.yml runs validate-release-version.sh first, because
# everything below compares $tag against real tag names: a bare `0.5.0` matches none of them and
# sorts before all of them, and `grep -vFx "$tag"` then fails to exclude this release from the
# list. Both produce a wrong start tag rather than an error, so reject the shape up front. Same
# grammar as the validator's, minus the changelog and ordering checks that are its job, not this
# script's.
if ! printf '%s' "$tag" | grep -qE '^v(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*)){2}$'; then
  echo "usage: $(basename "$0") vX.Y.Z (got '$tag')" >&2
  exit 2
fi

repo="${GITHUB_REPOSITORY:-}"
if [ -z "$repo" ]; then
  # Unreachable under Actions, which always sets $GITHUB_REPOSITORY. This branch is for running the
  # script by hand, which is also where the awkward remote shapes live — so it handles `scp`-style
  # and URL remotes alike, stripping any scheme, `user[:password]@` and `:port`. Dropping the
  # userinfo matters beyond tidiness: a credential-bearing origin is a thing people have (cd.yml
  # sets one on itself later in the job), and leaving it in would splice the token into the request
  # path and into gh's error output. The result is then checked rather than trusted, because a URL
  # shape none of this matches would otherwise reach `gh api` whole, come back 404, and report a
  # parsing bug as "the tag list could not be read".
  repo=$(git remote get-url origin \
    | sed -E 's#\.git$##; s#^[a-z+]+://##; s#^[^/@]*@##; s#^[^:/]*(:[0-9]+)?[:/]##')
  if ! printf '%s' "$repo" | grep -qE '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'; then
    echo "::error::Could not read owner/name out of the 'origin' remote; set \$GITHUB_REPOSITORY." >&2
    exit 1
  fi
fi

# The whole point of this file. Collapsed into one pipeline — which is how this was written inline
# in cd.yml — a failed `gh` leaves the assignment exiting 0 with an empty value, because without
# `pipefail` the status is `awk`'s and `awk` always succeeds. The caller then reads "the API is
# down" as "there is no previous release" and falls through to the bare `--generate-notes` that
# #166 removed. Fetching separately makes the failure visible.
#
# `--paginate` can also die partway with earlier pages already written to stdout, so a non-empty
# result is not evidence of a complete one either. Checking the exit status covers both.
if ! all_tags=$(gh api "repos/${repo}/tags" --paginate --jq '.[].name'); then
  echo "::error::Could not list tags to derive --notes-start-tag." >&2
  exit 1
fi

# `|| true` because `grep` legitimately matches nothing when the tag being released is the only one
# that exists, and under `pipefail` that would otherwise abort. Safe only because the fetch above
# already proved the API answered.
#
# Same grammar as the gate on $tag above, rather than the looser `[0-9]+` one: a stray `v0.05.0`
# passes the loose filter, and `sort -V` places it *between* v0.4.0 and v0.5.0 rather than treating
# it as either — so it would be handed to --notes-start-tag for a v0.5.0 release, generating public
# notes over the wrong commit range. No such tag can ever be a release of this library, because
# validate-release-version.sh rejects that shape, so nothing legitimate is lost by matching the two.
others=$(printf '%s\n' "$all_tags" \
  | grep -E '^v(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*)){2}$' \
  | grep -vFx "$tag" || true)

# $tag is appended to the stream rather than assumed to be in it. It normally is — cd.yml runs
# because the tag was pushed — but `gh api` can answer from a cache that predates it by seconds,
# and a walk that stops only on an exact match prints the *highest* tag when the cursor never does.
# For a normal forward release that happens to be the right answer; the difference only shows up
# when it isn't, which is precisely when nobody is watching. Appending makes the walk stop where
# $tag sorts regardless of whether the API listed it.
#
# `awk` reads the stream to the end rather than `exit`ing at $tag, because exiting early closes the
# pipe while `sort` may still be writing into it: `sort` then takes SIGPIPE, and under `pipefail`
# the *correct* answer is printed with exit 141. Measured — 20k tags above the one being released
# reproduces it exactly. Fail-closed makes that a blocked release rather than wrong notes, but a
# release blocked by its own pipe is not the failure this script is for.
printf '%s\n%s\n' "$others" "$tag" \
  | sort -V \
  | awk -v cur="$tag" '$0==cur{seen=1} !seen{prev=$0} END{if (prev != "") print prev}'
