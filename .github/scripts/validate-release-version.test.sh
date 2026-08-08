#!/usr/bin/env bash
#
# Tests for validate-release-version.sh.
#
# The point of these is that cd.yml runs only on a release tag, so the gate it depends on would
# otherwise first execute during an irreversible publish. These run on every PR instead.
#
# `gh` is stubbed by putting a fake one first on PATH rather than by adding a test seam to the
# script. That keeps the script free of test-only branches, and — the reason it matters here —
# lets the API-failure case exercise the real `if ! all_tags=$(gh ...)` line instead of a
# simulation of it. That line is the one that was wrong when this logic was first written.
#
# Usage: .github/scripts/validate-release-version.test.sh

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
subject="$script_dir/validate-release-version.sh"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

stub_dir="$workdir/bin"
mkdir -p "$stub_dir"

# Every case runs against this changelog, so a version is "in the changelog" iff it appears here.
cat >"$workdir/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

## [0.4.0] - 2026-08-06

## [0.3.0] - 2026-08-02

## [1.0.0] - 2030-01-01

## [0.4.1] - 2030-01-02

## [0.10.0] - 2030-01-03

## [01.2.3] - 2030-01-04

## [0.5.0] - 2030-01-05

## [0.7.0]
EOF

# $1 = "ok" or "fail". The ok stub ignores its arguments and prints the tag list; the fail stub
# reproduces what a rate-limited or unauthorized `gh api` does — stderr and a non-zero exit.
set_gh_stub() {
  if [ "$1" = ok ]; then
    cat >"$stub_dir/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' $GH_STUB_TAGS
EOF
  else
    cat >"$stub_dir/gh" <<'EOF'
#!/usr/bin/env bash
echo "gh: HTTP 403: API rate limit exceeded" >&2
exit 1
EOF
  fi
  chmod +x "$stub_dir/gh"
}

failures=0
pass_count=0

# expect <expected: pass|fail> <tag> <gh: ok|down> <tags> <description>
expect() {
  local expected="$1" tag="$2" gh_state="$3" tags="$4" description="$5"
  set_gh_stub "$gh_state"

  local output status
  set +e
  output=$(cd "$workdir" && PATH="$stub_dir:$PATH" GH_STUB_TAGS="$tags" \
    GITHUB_REPOSITORY="uny/autograph" bash "$subject" "$tag" 2>&1)
  status=$?
  set -e

  local actual=pass
  [ "$status" -eq 0 ] || actual=fail

  if [ "$actual" = "$expected" ]; then
    pass_count=$((pass_count + 1))
    printf '  ok    %-34s %s\n' "$tag" "$description"
  else
    failures=$((failures + 1))
    printf '  FAIL  %-34s %s\n' "$tag" "$description"
    printf '        expected to %s, but it %sed (exit %d)\n' "$expected" "$actual" "$status"
    printf '        output: %s\n' "$output"
  fi
}

existing="v0.1.0 v0.1.1 v0.2.0 v0.3.0 v0.4.0"

echo "validate-release-version.sh"

expect pass v0.5.0    ok   "$existing"          "a normal next release"
expect pass v0.5.0    ok   "v0.5.0"             "the only tag in the repository is this one"
expect pass v0.10.0   ok   "$existing"          "double-digit minor sorts above v0.4.0, not below"

expect fail v0.4      ok   "$existing"          "not three components"
expect fail v0.4.0.1  ok   "$existing"          "four components"
expect fail vX.Y.Z    ok   "$existing"          "not numeric"
expect fail v0.5.0-rc1 ok  "$existing"          "prerelease suffix"
expect fail v01.2.3   ok   "$existing"          "leading zero, though the changelog has a section"

expect fail v0.41.0   ok   "$existing"          "transposed v0.4.1; well-formed, absent from changelog"
expect fail v0.6.0    ok   "$existing"          "no changelog section at all"
expect fail v0.7.0    ok   "$existing"          "changelog heading present but still undated"

expect fail v0.3.0    ok   "$existing"          "already released, sorts below v0.4.0"
expect fail v0.4.1    ok   "v1.0.0 $existing"   "old-series patch after a newer major"

# The regression this file exists for. Before the fetch was separated from the filter, a failing
# `gh` produced an empty tag list that read as "no other tags", and the ordering check was skipped
# in silence — so this exact case PASSED, and would have published v0.3.0 over v0.4.0.
expect fail v0.3.0    down "$existing"          "API down must fail closed, not skip the check"
expect fail v0.5.0    down "$existing"          "API down fails even a version that is otherwise fine"

echo
if [ "$failures" -ne 0 ]; then
  echo "$failures failed, $pass_count passed"
  exit 1
fi
echo "all $pass_count passed"
