#!/usr/bin/env bash
#
# Tests for ios-cold-check.sh's `verify`.
#
# `prepare` needs Xcode and a simulator, so only `verify` runs here: it is the half that decides
# pass or fail, and the failure shapes it must catch (a dropped tap, a double report, a warm device)
# are expensive to produce on a real simulator. Each case writes a log in the shape `log stream
# --style compact` prints LoggingTracker's lines in.
#
# Usage: .github/scripts/ios-cold-check.test.sh

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
subject="$script_dir/ios-cold-check.sh"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

prefix='2026-10-19 13:47:47.415 Df iosApp[99863:4e85c6] (sample_shared) AutographSample:'
screen_line="$prefix screen name=Sample properties={\"article_id\":\"42\"}"
track() { printf '%s track name=%s target=%s properties={"screen":"Sample"}\n' "$prefix" "$1" "$2"; }
probe() { printf '%s probe hostAxFrame=%s\n' "$prefix" "$1"; }

# Every expected event of a passing run, without probe lines.
passing_events() {
  echo "$screen_line"
  track "Recipe Viewed" recipe_card
  track "Recipe Viewed" impression_inner
  track "Element Clicked" plain_button
  track "Element Clicked" inner_button
  track "Element Clicked" outer_container
  track "Recipe Saved" explicit_tracked_button
  track "Recipe Saved" explicit_tracked_small
  track "Element Clicked" clamped_inner_host
}

failures=0
# $1 = name, $2 = expected exit (0 or 1), $3 = substring the output must contain, stdin = log.
check() {
  local log="$workdir/$1.log" out status=0
  cat >"$log"
  out=$("$subject" verify "$log" 2>&1) || status=$?
  if [ "$status" -ne "$2" ] || [[ "$out" != *"$3"* ]]; then
    echo "not ok - $1 (exit $status, want $2; want output containing '$3')"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok - $1"
  fi
}

passing_events | check "pass-without-probe" 0 "cold NOT proven"

{ passing_events; probe 0.0x0.0; probe 0.0x0.0; } | check "pass-with-cold-probe" 0 "cold proven: 2/2 pointer events"

{ passing_events; probe 0.0x0.0; probe 402.0x874.0; } | check "warm-probe-fails" 1 "device was warm"

passing_events | grep -v "target=inner_button " | check "dropped-tap-fails" 1 "FAIL inner_button"

{ passing_events; track "Element Clicked" explicit_tracked_small; } \
  | check "double-report-fails" 1 "FAIL explicit_tracked_small: also reported"

# The strip tap reported as the inner's explicit event instead of the host's own.
{ passing_events | grep -v "target=clamped_inner_host "; track "Recipe Saved" clamped_inner; } \
  | check "strip-tap-given-to-inner-fails" 1 "FAIL clamped_inner_host: no"

passing_events | grep -v "screen name=Sample" | check "no-launch-fails" 1 "FAIL launch"

check "empty-log-fails" 1 "FAIL launch" </dev/null

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all ios-cold-check tests passed"
