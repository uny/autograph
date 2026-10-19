#!/usr/bin/env bash
#
# The cold-device iOS tap check CONTRIBUTING.md requires before a `composeMultiplatform` bump merges.
#
# iOS Compose autocapture resolves a tap through CMP's accessibility tree, and that tree is only
# populated on demand. CI's `xcodebuild test` is itself an accessibility client, so it warms exactly
# the state a real cold launch starts without, and a CMP bump that breaks cold-start tap resolution
# lands fully green (#135). The check has to run on a simulator nothing has warmed yet.
#
# Everything except the taps is scripted here. The taps go through the Claude Code iOS Simulator MCP
# tool, which injects touches without warming accessibility (measured 2026-09-12, again 2026-10-19).
# So does `simctl io screenshot`. A human tapping in Simulator.app works too. What must NOT touch the
# device before the taps: VoiceOver, Voice Control, the Accessibility Inspector, XCUITest, or any other
# accessibility client. `shutdown` + `boot` does not reset a warmed device, which is why every run
# creates a new one and teardown deletes it.
#
# Usage:
#   ios-cold-check.sh prepare [--probe] [--skip-xcframework]
#       Create and boot a fresh simulator, build sample-ios against the local Autograph.xcframework,
#       start log capture, install and launch the app, assert the launch-time events, take a
#       screenshot and print the taps to make. Leaves the simulator running for the taps.
#       --probe applies ios-cold-check-probe.patch first, so every press logs the host view's
#       accessibilityFrame: 0.0x0.0 proves the press was cold. Teardown reverts it. Never commit it.
#       --skip-xcframework reuses an existing autograph-apple/build/XCFrameworks/release output.
#   ios-cold-check.sh verify [log]
#       Check the captured log against the expected events. Exit 0 = pass.
#   ios-cold-check.sh teardown
#       Stop log capture, delete the simulator, revert the probe. The log is kept.
#
# State (udid, log, screenshot) lives in $AUTOGRAPH_COLD_CHECK_DIR, default
# ${TMPDIR:-/tmp}/autograph-cold-check. COLD_CHECK_DEVICE_TYPE picks the simulator model (default
# "iPhone 17 Pro"); the newest installed iOS runtime is used.
#
# `verify` is tested by ios-cold-check.test.sh. `prepare` needs Xcode and a simulator runtime, so it
# only ever runs on a developer's Mac.

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
state_dir="${AUTOGRAPH_COLD_CHECK_DIR:-${TMPDIR:-/tmp}/autograph-cold-check}"
state_dir="${state_dir%/}"
device_type="${COLD_CHECK_DEVICE_TYPE:-iPhone 17 Pro}"
bundle_id="dev.ynagai.autograph.sample.ios"
probe_patch="$script_dir/ios-cold-check-probe.patch"

# Each line: <target> <event that must appear> <event that must not appear, or ->.
# The order is the tap order. The first four are the cold check, the last two the trackClick ordering
# check CONTRIBUTING folds into the same session.
expectations=(
  "plain_button Element_Clicked -"
  "inner_button Element_Clicked -"
  "outer_container Element_Clicked -"
  "explicit_tracked_button Recipe_Saved Element_Clicked"
  "explicit_tracked_small Recipe_Saved Element_Clicked"
  "clamped_inner_host Element_Clicked -"
)

die() { echo "ios-cold-check: $*" >&2; exit 1; }

# $1 = log, $2 = event name, $3 = target. Matches the LoggingTracker line exactly, so `clamped_inner`
# never matches `clamped_inner_host`.
has_event() { grep -qF "AutographSample: track name=$2 target=$3 properties=" "$1"; }

cmd_verify() {
  local log="${1:-$state_dir/log.txt}"
  [ -f "$log" ] || die "no log at $log"
  local failures=0 line target want forbid

  if grep -qF "AutographSample: screen name=Sample " "$log"; then
    echo "ok   launch: screen name=Sample"
  else
    echo "FAIL launch: no 'screen name=Sample' — the app or the log capture never came up"
    failures=$((failures + 1))
  fi

  for line in "${expectations[@]}"; do
    read -r target want forbid <<<"$line"
    want="${want//_/ }"
    if has_event "$log" "$want" "$target"; then
      echo "ok   $target: $want"
    else
      echo "FAIL $target: no '$want' (not tapped, or the tap was dropped / misattributed)"
      failures=$((failures + 1))
    fi
    if [ "$forbid" != "-" ]; then
      forbid="${forbid//_/ }"
      if has_event "$log" "$forbid" "$target"; then
        echo "FAIL $target: also reported as '$forbid' (double-reported)"
        failures=$((failures + 1))
      fi
    fi
  done
  # A tap on clamped_inner_host's lower strip runs no trackClick, so the inner must stay silent.
  if has_event "$log" "Recipe Saved" "clamped_inner"; then
    echo "FAIL clamped_inner: 'Recipe Saved' logged — the strip tap was given to the inner, or the inner was tapped"
    failures=$((failures + 1))
  fi

  local probes warm cold_note
  probes=$(grep -c "AutographSample: probe hostAxFrame=" "$log" || true)
  warm=$(grep "AutographSample: probe hostAxFrame=" "$log" | grep -vc "hostAxFrame=0.0x0.0$" || true)
  if [ "$probes" -eq 0 ]; then
    cold_note="cold NOT proven: no probe lines (run prepare --probe to prove it)"
  elif [ "$warm" -gt 0 ]; then
    echo "FAIL probe: $warm of $probes pointer events saw a real accessibilityFrame — the device was warm, so this run proves nothing"
    failures=$((failures + 1))
  else
    cold_note="cold proven: $probes/$probes pointer events (down and up) read hostAxFrame=0.0x0.0"
  fi

  if [ "$failures" -gt 0 ]; then
    echo "ios-cold-check: FAIL ($failures)"
    return 1
  fi
  echo "ios-cold-check: PASS — $cold_note"
}

cmd_teardown() {
  if [ -f "$state_dir/logstream.pid" ]; then
    kill "$(cat "$state_dir/logstream.pid")" 2>/dev/null || true
    rm -f "$state_dir/logstream.pid"
  fi
  if [ -f "$state_dir/udid" ]; then
    local udid
    udid=$(cat "$state_dir/udid")
    xcrun simctl shutdown "$udid" 2>/dev/null || true
    xcrun simctl delete "$udid" && echo "deleted simulator $udid"
    rm -f "$state_dir/udid"
  fi
  if [ -f "$state_dir/probe-applied" ]; then
    git -C "$repo_root" apply -R "$probe_patch" && echo "reverted the probe"
    rm -f "$state_dir/probe-applied"
  fi
  [ -f "$state_dir/log.txt" ] && echo "log kept at $state_dir/log.txt"
  return 0
}

cmd_prepare() {
  local probe=0 build_xcframework=1 arg
  for arg in "$@"; do
    case "$arg" in
      --probe) probe=1 ;;
      --skip-xcframework) build_xcframework=0 ;;
      *) die "unknown prepare option: $arg" ;;
    esac
  done
  command -v xcrun >/dev/null || die "xcrun not found (needs Xcode)"
  command -v jq >/dev/null || die "jq not found"
  [ ! -f "$state_dir/udid" ] || die "a previous run is still set up ($(cat "$state_dir/udid")); run teardown first"
  mkdir -p "$state_dir"
  rm -f "$state_dir/log.txt" "$state_dir/screenshot.png"

  if [ "$probe" -eq 1 ]; then
    git -C "$repo_root" apply "$probe_patch" || die "the probe patch does not apply; is MainViewController.kt modified?"
    touch "$state_dir/probe-applied"
    echo "applied the probe (teardown reverts it; never commit it)"
  fi

  # The simulator comes first: the MCP tool asks for access once per device, and a fresh simulator
  # is a new device. Asking now lets the grant happen while the build runs; on 2026-10-19 an
  # unanswered prompt held the check for about an hour after the build had finished.
  local runtime udid
  runtime=$(xcrun simctl list runtimes -j \
    | jq -r '[.runtimes[] | select(.platform == "iOS" and .isAvailable)] | sort_by(.version | split(".") | map(tonumber)) | last | .identifier // empty')
  [ -n "$runtime" ] || die "no available iOS simulator runtime"
  udid=$(xcrun simctl create "autograph-cold-check" "$device_type" "$runtime")
  echo "$udid" >"$state_dir/udid"
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b >/dev/null
  cat <<EOF

================================================================================
Fresh simulator booted: $udid ($device_type, $runtime)
If the taps will go through the Claude Code iOS Simulator MCP tool, grant it access
to THIS device now (its 'attach' action asks), while the build runs.
Do not open VoiceOver, the Accessibility Inspector or XCUITest against it.
================================================================================

EOF

  if [ "$build_xcframework" -eq 1 ]; then
    # Without it Package.swift falls back to downloading the last released umbrella.
    (cd "$repo_root" && ./gradlew :autograph-apple:assembleAutographReleaseXCFramework --quiet)
  fi
  [ -d "$repo_root/autograph-apple/build/XCFrameworks/release/Autograph.xcframework" ] \
    || die "no local Autograph.xcframework; drop --skip-xcframework"

  # A generic destination keeps xcodebuild away from the cold device. It needs ARCHS pinned, or it
  # fails with "Unknown iOS simulator arch: 'x86_64'".
  xcodebuild build -quiet \
    -project "$repo_root/sample-ios/iosApp.xcodeproj" -scheme iosApp -configuration Debug \
    -destination 'generic/platform=iOS Simulator' ARCHS=arm64 ONLY_ACTIVE_ARCH=NO \
    -derivedDataPath "$state_dir/DerivedData" \
    -clonedSourcePackagesDirPath "$state_dir/SourcePackages"
  local app="$state_dir/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app"
  [ -d "$app" ] || die "build produced no app at $app"

  # Capture starts before launch, so nothing races the first event.
  nohup xcrun simctl spawn "$udid" log stream --style compact \
    --predicate 'composedMessage BEGINSWITH "AutographSample:"' >"$state_dir/log.txt" 2>&1 &
  echo $! >"$state_dir/logstream.pid"
  sleep 2

  xcrun simctl install "$udid" "$app"
  xcrun simctl launch "$udid" "$bundle_id" >/dev/null

  # The launch-time screen view and the two impressions are the liveness guard: they show the app,
  # its tracker and the capture are all up before anyone taps.
  local waited=0
  until grep -qF "AutographSample: screen name=Sample " "$state_dir/log.txt" \
    && [ "$(grep -cF "AutographSample: track name=Recipe Viewed " "$state_dir/log.txt")" -ge 2 ]; do
    [ "$waited" -lt 30 ] || die "no launch-time screen view and 2 impressions within 30s; see $state_dir/log.txt"
    sleep 1
    waited=$((waited + 1))
  done
  echo "launch ok: screen view and 2 impressions logged"

  # A framebuffer read; it does not warm accessibility.
  xcrun simctl io "$udid" screenshot "$state_dir/screenshot.png" >/dev/null 2>&1
  cat <<EOF

Screenshot: $state_dir/screenshot.png
Tap these, in this order, and nothing else first. MCP tap coordinates are in points:
screenshot pixels / 3 on an iPhone 17 Pro.

  1. plain_button             the "Autocaptured (testTag = plain_button)" button
                              -> Element Clicked target=plain_button
  2. inner_button             the dark square inside the tinted box below it
                              -> Element Clicked target=inner_button
  3. outer_container          the tinted box, to the right of that square
                              -> Element Clicked target=outer_container
  4. explicit_tracked_button  the full-width "Explicitly tracked (Modifier.trackClick)" bar
                              -> Recipe Saved only, no Element Clicked
  5. explicit_tracked_small   the "Explicitly tracked, small" text
                              -> Recipe Saved only, no Element Clicked
  6. clamped_inner_host       the lower strip of the box under "trackClick at the top of a
                              plain 48dp clickable", below that text
                              -> Element Clicked target=clamped_inner_host, no Recipe Saved

Then:
  $0 verify
  $0 teardown
EOF
}

case "${1:-}" in
  prepare) shift; cmd_prepare "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  teardown) cmd_teardown ;;
  *) echo "usage: $(basename "$0") prepare [--probe] [--skip-xcframework] | verify [log] | teardown" >&2; exit 2 ;;
esac
