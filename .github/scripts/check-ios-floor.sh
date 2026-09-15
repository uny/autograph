#!/usr/bin/env bash
#
# Refuse an Autograph.xcframework whose iOS deployment target disagrees with Package.swift.
#
# The manifest's `.iOS(.vN)` is what SwiftPM enforces on consumers; the binary's LC_BUILD_VERSION
# `minos` is what the linker enforces. Nothing ties them together: the manifest is hand-edited and
# the binary's value comes from the Gradle build (pinned there since #208 — before that it was the
# Kotlin/Native toolchain's default, which is how it moved without anyone noticing). #195 was the
# unsafe shape, a manifest below the binary, and no job in this repo could see it because every
# simulator CI runs on is far above either number (#197). This is the assertion that was missing.
#
# It fails on ANY difference, not only the unsafe direction. A manifest above the binary is harmless
# to link but turns consumers away for nothing, and — the reason it matters here — it is the
# signature of exactly the silent drift this exists to catch: the build stopped honouring the pin.
#
# Both the Mach-O load command and the framework's Info.plist `MinimumOSVersion` are checked, for
# every slice in the xcframework. They agreed when measured; nothing guarantees either keeps doing so.
#
# Usage:  check-ios-floor.sh <path/to/Autograph.xcframework> [path/to/Package.swift]
#
# Needs `vtool` and `plutil` (Xcode command line tools), so it only ever runs on macOS. Tested by
# check-ios-floor.test.sh, which stubs both and runs on every PR.

set -euo pipefail

xcframework="${1:-}"
manifest="${2:-Package.swift}"
if [ -z "$xcframework" ] || [ ! -d "$xcframework" ]; then
  echo "usage: $(basename "$0") <path/to/Name.xcframework> [Package.swift] (got '$xcframework')" >&2
  exit 2
fi
if [ ! -f "$manifest" ]; then
  echo "$(basename "$0"): manifest not found: $manifest" >&2
  exit 2
fi

# `::error::` is a GitHub Actions annotation and plain text everywhere else, so the message has to
# read on its own.
fail() {
  echo "::error::$1" >&2
  exit 1
}

# "15" -> "15.0", "15.1" -> "15.1". SwiftPM's `.v15` and vtool's `15.0` name the same floor.
normalize() {
  case "$1" in
    *.*) printf '%s\n' "$1" ;;
    *) printf '%s.0\n' "$1" ;;
  esac
}

# Accept the two spellings SwiftPM allows: `.iOS(.v15)` and `.iOS("15.1")`. Anything else — including
# a manifest with no iOS platform at all — is a failure rather than a pass, because "nothing declared"
# is not the same as "declared correctly". Comments are dropped first, both `// …` and `/* … */`, so
# that prose recounting an old floor (`// was .iOS(.v13)`) is never the value compared. perl, because
# a block comment can span lines and neither BSD nor GNU sed handles that in one readable expression.
declared=$(perl -0pe 's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' "$manifest" | grep -oE '\.iOS\((\.v[0-9]+|"[0-9]+(\.[0-9]+)?")\)' | head -n 1 || true)
case "$declared" in
  '.iOS(.v'*) manifest_floor=$(normalize "$(printf '%s' "$declared" | sed 's/^\.iOS(\.v//; s/)$//')") ;;
  '.iOS("'*) manifest_floor=$(normalize "$(printf '%s' "$declared" | sed 's/^\.iOS("//; s/")$//')") ;;
  *) fail "$manifest declares no iOS floor in a form this check understands (.iOS(.vN) or .iOS(\"N.M\"))" ;;
esac

checked=0
for slice in "$xcframework"/*/; do
  slice="${slice%/}"
  # An unmatched glob comes through literally (nullglob is off by default).
  [ -d "$slice" ] || continue
  # The only non-slice directory an xcframework root can hold is a code signature.
  case "$(basename "$slice")" in _*) continue ;; esac
  # A slice holds one .framework; the binary inside carries the framework's name. A slice without
  # one is not "nothing to check" — skipping it would let a half-built xcframework pass on the
  # strength of its other slice.
  framework=$(find "$slice" -maxdepth 1 -name '*.framework' -type d | head -n 1)
  [ -n "$framework" ] || fail "$(basename "$slice"): no .framework bundle"
  name=$(basename "$framework" .framework)
  binary="$framework/$name"
  [ -f "$binary" ] || fail "$slice: no binary at $binary"

  # vtool prints one `minos X.Y` line per architecture. Every one is compared, not only the first:
  # a fat slice (lipo of two targets) is exactly where one architecture can miss the pin while the
  # other carries it.
  minos_lines=$(vtool -show-build "$binary" | awk '$1 == "minos" { print $2 }')
  [ -n "$minos_lines" ] || fail "$binary: vtool reported no minos (no LC_BUILD_VERSION?)"
  binary_floor=""
  for arch_floor in $minos_lines; do
    arch_floor=$(normalize "$arch_floor")
    if [ -n "$binary_floor" ] && [ "$arch_floor" != "$binary_floor" ]; then
      fail "$(basename "$slice"): architectures disagree on minos ($binary_floor vs $arch_floor) — one of them missed the pin"
    fi
    binary_floor="$arch_floor"
  done

  plist_floor=$(plutil -extract MinimumOSVersion raw -o - "$framework/Info.plist" 2>/dev/null || true)
  [ -n "$plist_floor" ] || fail "$framework/Info.plist has no MinimumOSVersion"
  plist_floor=$(normalize "$plist_floor")

  if [ "$binary_floor" != "$manifest_floor" ]; then
    fail "$(basename "$slice"): binary minos $binary_floor but $manifest declares iOS $manifest_floor — change Package.swift's .iOS(...) and gradle/libs.versions.toml's ios-deploymentTarget together"
  fi
  if [ "$plist_floor" != "$manifest_floor" ]; then
    fail "$(basename "$slice"): Info.plist MinimumOSVersion $plist_floor but $manifest declares iOS $manifest_floor"
  fi
  echo "$(basename "$slice"): minos $binary_floor, MinimumOSVersion $plist_floor, manifest iOS $manifest_floor — ok"
  checked=$((checked + 1))
done

# An empty xcframework passing would be the vacuous green this check must never give.
[ "$checked" -gt 0 ] || fail "$xcframework contains no framework slices"
