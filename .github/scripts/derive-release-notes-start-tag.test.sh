#!/usr/bin/env bash
#
# Tests for derive-release-notes-start-tag.sh.
#
# Same reasoning as validate-release-version.test.sh: cd.yml runs only on a release tag, so this
# derivation would otherwise first execute during a release. It already has — and got it wrong
# (#166), which is why the derivation exists at all; the version that replaced it has still never
# run, because it landed after v0.4.0 shipped. These run on every PR instead.
#
# `gh` is stubbed by putting a fake one first on PATH rather than by adding a test seam, so the
# API-failure case exercises the real `if ! all_tags=$(gh ...)` line instead of a simulation of it.
#
# Usage: .github/scripts/derive-release-notes-start-tag.test.sh

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
subject="$script_dir/derive-release-notes-start-tag.sh"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

stub_dir="$workdir/bin"
mkdir -p "$stub_dir"

# $1 = "ok", "down", "partial", or "flood".
#
#   ok       prints the tag list and exits 0
#   down     reproduces a rate-limited or unauthorized `gh api` — stderr and a non-zero exit
#   partial  reproduces `--paginate` dying after earlier pages have already reached stdout: output
#            *and* a non-zero exit. That combination is the only one that distinguishes the shipped
#            status check from a check on emptiness, which would otherwise pass every case here.
#   flood    ignores $GH_STUB_TAGS and prints more tags than a pipe buffer holds (see its case)
#
# Every stub records its argv, because a stub that only answers cannot pin the question: with the
# request unasserted, misspelling the endpoint or dropping `--jq` leaves the whole suite green and
# fails first during a release, which is the failure mode this file exists to remove.
#
# `set -f` because $GH_STUB_TAGS is deliberately word-split but must not also be globbed — the
# subject runs with cwd inside $workdir, so a future fixture containing `err*` would otherwise
# silently become the filename `err`.
set_gh_stub() {
  case "$1" in
    ok | partial)
      cat >"$stub_dir/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$GH_STUB_ARGV"
set -f
printf '%s\n' $GH_STUB_TAGS
EOF
      if [ "$1" = partial ]; then
        cat >>"$stub_dir/gh" <<'EOF'
echo "gh: HTTP 403: API rate limit exceeded" >&2
exit 1
EOF
      fi
      ;;
    flood)
      # More tags above the one being released than fit in a pipe buffer, so `sort` is still
      # writing when `awk` has already decided. Generated rather than passed in $GH_STUB_TAGS
      # because word-splitting 20k entries through the environment is not what is under test.
      cat >"$stub_dir/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$GH_STUB_ARGV"
printf 'v0.4.0\n'
i=1
while [ "$i" -le 20000 ]; do
  printf 'v1.%s.0\n' "$i"
  i=$((i + 1))
done
EOF
      ;;
    *)
      cat >"$stub_dir/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$GH_STUB_ARGV"
echo "gh: HTTP 403: API rate limit exceeded" >&2
exit 1
EOF
      ;;
  esac
  chmod +x "$stub_dir/gh"
}

failures=0
pass_count=0

run_subject() {
  local tag="$1" gh_state="$2" tags="$3"
  set_gh_stub "$gh_state"
  : >"$workdir/argv"
  set +e
  out=$(cd "$workdir" && PATH="$stub_dir:$PATH" GH_STUB_TAGS="$tags" \
    GH_STUB_ARGV="$workdir/argv" \
    GITHUB_REPOSITORY="uny/autograph" bash "$subject" "$tag" 2>"$workdir/err")
  status=$?
  err=$(cat "$workdir/err")
  argv=$(cat "$workdir/argv")
  set -e
}

report_failure() {
  failures=$((failures + 1))
  printf '  FAIL  %-30s %s\n' "$1" "$2"
  shift 2
  printf '        %s\n' "$@"
}

# expect_tag <expected stdout> <tag> <gh: ok|down> <tags> <description>
#
# The empty expectation is a real case, not a placeholder: the first release of a repository has no
# previous tag, and the caller falls back to a plain `--generate-notes` on exactly that.
expect_tag() {
  local expected="$1" tag="$2" gh_state="$3" tags="$4" description="$5"
  local out status err argv
  run_subject "$tag" "$gh_state" "$tags"

  if [ "$status" -ne 0 ]; then
    report_failure "$tag" "$description" "expected '${expected:-<nothing>}', but it exited $status" "stderr: $err"
    return
  fi
  if [ "$out" != "$expected" ]; then
    report_failure "$tag" "$description" "expected '${expected:-<nothing>}', got '${out:-<nothing>}'"
    return
  fi

  pass_count=$((pass_count + 1))
  printf '  ok    %-30s %s\n' "${expected:-<nothing>}" "$description"
}

# expect_error <exit status> <stderr substring> <tag> <gh: ok|down> <tags> <description>
#
# The status alone would not distinguish the usage errors from the API failure, and the difference
# is the whole point of this script: exit 1 means "we never found out", which the caller must not
# confuse with the empty-but-successful result above.
expect_error() {
  local expected_status="$1" reason="$2" tag="$3" gh_state="$4" tags="$5" description="$6"
  local out status err argv
  run_subject "$tag" "$gh_state" "$tags"

  if [ "$status" -ne "$expected_status" ]; then
    report_failure "${tag:-<empty>}" "$description" \
      "expected exit $expected_status, got $status" "stdout: '$out'" "stderr: $err"
    return
  fi
  case "$err" in
    *"$reason"*) ;;
    *)
      report_failure "${tag:-<empty>}" "$description" \
        "exited $status as expected, but for the wrong reason" \
        "wanted stderr containing: $reason" "stderr: $err"
      return
      ;;
  esac
  # An error path that also printed a start tag would be read by `prev_tag=$(...)` as a usable
  # value if the caller ever checked emptiness before status.
  if [ -n "$out" ]; then
    report_failure "${tag:-<empty>}" "$description" "failed as expected but still printed '$out' on stdout"
    return
  fi

  pass_count=$((pass_count + 1))
  printf '  ok    %-30s %s\n' "exit $expected_status" "$description"
}

# expect_request <exact argv> <tag> <gh: ok|down|partial> <tags> <description>
#
# Asserts on the request rather than the answer: the stub's reply is the same whatever it is asked,
# so nothing else here would notice the subject asking the wrong thing.
#
# Exact rather than a substring match, because every interesting way to get this wrong is additive
# or subtractive at the end: `--paginate=false` contains `--paginate` and reads one page, and
# dropping `--jq '.[].name'` leaves a prefix that still matches while real `gh` starts returning
# JSON objects that the anchored `grep` discards — an empty start tag, silently.
expect_request() {
  local expected="$1" tag="$2" gh_state="$3" tags="$4" description="$5"
  local out status err argv
  run_subject "$tag" "$gh_state" "$tags"

  if [ "$status" -ne 0 ]; then
    report_failure "$tag" "$description" "exited $status" "stderr: $err"
    return
  fi
  if [ "$argv" != "$expected" ]; then
    report_failure "$tag" "$description" \
      "wanted the gh call: $expected" "got: ${argv:-<gh was never called>}"
    return
  fi

  pass_count=$((pass_count + 1))
  printf '  ok    %-30s %s\n' "gh argv" "$description"
}

existing="v0.1.0 v0.1.1 v0.2.0 v0.3.0 v0.4.0"

echo "derive-release-notes-start-tag.sh"

expect_tag v0.4.0 v0.5.0 ok "$existing"          "the release preceding a normal next version"
expect_tag v0.4.0 v0.5.0 ok "$existing v0.5.0"   "the tag being released is already in the list"
expect_tag ""     v0.1.0 ok "v0.1.0"             "the first release has no previous tag"
expect_tag ""     v0.1.0 ok ""                   "the API lists no tags at all yet"

# `sort -V` rather than a lexical sort, which would answer v0.9.0 here.
expect_tag v0.10.0 v0.11.0 ok "v0.9.0 v0.10.0"   "double-digit minor precedes v0.11.0"

# The reason $tag is appended to the stream instead of being assumed present in it. The original
# inline walk stopped only on an exact match of the current tag, so with the cursor absent it ran
# to the end and printed the highest tag — v1.0.0 — as the "previous" release. It gets the right
# answer whenever the tag is listed or nothing sorts above it, which is every case but this one.
expect_tag v0.4.0 v0.5.0 ok "$existing v1.0.0"   "a higher tag exists and the API has not listed this one yet"

# The same shape at a size that changes the outcome. `awk` stopping at $tag used to close the pipe
# while `sort` was still writing into it, so `sort` took SIGPIPE and `pipefail` turned the right
# answer into exit 141 — a release blocked by its own plumbing. Below the pipe buffer (the case
# above) it never shows; the one-higher-tag fixture cannot reach it.
expect_tag v0.4.0 v0.5.0 flood ""                "20k tags above this one must not SIGPIPE the sort"

# Whatever else is in the repository is not a release of this library. `v0.05.0` is the one that
# costs something rather than merely being skipped: it passes a `[0-9]+`-style filter, and `sort -V`
# places it between v0.4.0 and v0.5.0 rather than alongside either, so a looser filter hands it to
# --notes-start-tag and the published notes cover the wrong range.
expect_tag v0.4.0 v0.5.0 ok "$existing v0.5.0-rc1 nightly-20260101 sdk-v9.9.9 v0.4 v0.4.0.1 v0.05.0" \
  "non-release tags are ignored"

# The regression this file exists for. Inline in cd.yml the fetch and the filter were one pipeline,
# so a failing `gh` exited 0 with an empty result — indistinguishable from "no previous release",
# and the caller silently fell back to the start-tag-less `--generate-notes` that #166 removed.
expect_error 1 "Could not list tags" v0.5.0 down "$existing" "API down must fail closed, not read as no previous tag"
expect_error 1 "Could not list tags" v0.1.0 down ""          "API down fails even where an empty answer would be legitimate"

# The half-answer, and the only case that separates checking `gh`'s exit status from checking
# whether it printed anything: `--paginate` can 403 on a later page with the earlier ones already on
# stdout. An emptiness check passes every other case in this file and truncates the list here —
# releasing v0.5.0 off a list that lost v0.4.0 yields v0.3.0, and notes spanning two releases with
# nothing anywhere reporting a problem.
expect_error 1 "Could not list tags" v0.5.0 partial "$existing" \
  "--paginate dying after earlier pages is a failure, not a short list"

# A stub that only answers cannot pin the question. Without this, misspelling the endpoint or
# dropping --jq leaves all of the above green and first fails during a release — the exact shape of
# the bug that put this derivation in a script.
expect_request "api repos/uny/autograph/tags --paginate --jq .[].name" v0.5.0 ok "$existing" \
  "the tag list is read from the repository's tags endpoint, paginated"

expect_error 2 "usage:" ""          ok "$existing" "no argument at all"
expect_error 2 "usage:" 0.5.0       ok "$existing" "no leading v"
expect_error 2 "usage:" v0.5        ok "$existing" "not three components"
expect_error 2 "usage:" v0.5.0-rc1  ok "$existing" "prerelease suffix"
expect_error 2 "usage:" v01.2.3     ok "$existing" "leading zero"

echo
if [ "$failures" -ne 0 ]; then
  echo "$failures failed, $pass_count passed"
  exit 1
fi
echo "all $pass_count passed"
