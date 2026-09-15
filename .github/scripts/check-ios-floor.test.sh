#!/usr/bin/env bash
#
# Tests for check-ios-floor.sh.
#
# The check only ever executes after a Kotlin/Native xcframework build (swift-package, the release
# dry run, cd.yml), and a real mismatch takes a toolchain change to produce. These run on every PR
# instead, and cover the failure shapes a real build cannot cheaply reproduce.
#
# `vtool` and `plutil` are stubbed by putting fakes first on PATH rather than by adding a test seam
# to the script, so the real `vtool -show-build ... | awk` and `plutil -extract` lines are what run.
# Each stub refuses any argv other than the one the script is expected to use, so a typo in either
# invocation fails here rather than only on a macOS runner. Each reads the number it should report
# from the file it is pointed at — the "binary" holds its minos (one line per architecture), the
# Info.plist holds its MinimumOSVersion — so a case is set up by writing those files. That is also
# what makes this runnable on ubuntu, where neither tool exists.
#
# Usage: .github/scripts/check-ios-floor.test.sh

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
subject="$script_dir/check-ios-floor.sh"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

stub_dir="$workdir/bin"
mkdir -p "$stub_dir"
cat >"$stub_dir/vtool" <<'EOF'
#!/usr/bin/env bash
[ "$#" -eq 2 ] && [ "$1" = "-show-build" ] || { echo "vtool stub: unexpected argv: $*" >&2; exit 64; }
# Real output has more lines (platform, sdk); only the shape of the minos line matters. A fat binary
# prints one such block per architecture.
while IFS= read -r minos; do
  [ -n "$minos" ] || continue
  printf 'Load command 10\n      cmd LC_BUILD_VERSION\n platform IOS\n    minos %s\n      sdk 26.0\n' "$minos"
done <"$2"
EOF
cat >"$stub_dir/plutil" <<'EOF'
#!/usr/bin/env bash
[ "$#" -eq 6 ] && [ "$1 $2 $3 $4 $5" = "-extract MinimumOSVersion raw -o -" ] || { echo "plutil stub: unexpected argv: $*" >&2; exit 64; }
cat "$6"
EOF
chmod +x "$stub_dir/vtool" "$stub_dir/plutil"
export PATH="$stub_dir:$PATH"

# $1 = xcframework dir, $2 = slice name, $3 = binary minos (newline-separated for a fat binary),
# $4 = plist MinimumOSVersion ($3 if omitted; pass "" for a plist without the key).
add_slice() {
  local fw="$1/$2/Autograph.framework"
  mkdir -p "$fw"
  printf '%s\n' "$3" >"$fw/Autograph"
  printf '%s' "${4-$3}" >"$fw/Info.plist"
}

# $1 = manifest path, $2 = platforms line.
write_manifest() {
  printf 'let package = Package(\n    name: "Autograph",\n    platforms: [%s],\n)\n' "$2" >"$1"
}

failures=0
# $1 = case name, $2 = expected exit code, $3 = xcframework, $4 = manifest, $5 = expected stderr substring
expect() {
  local name="$1" want="$2" xcf="$3" manifest="$4" want_err="${5:-}"
  local got=0 err
  err=$("$subject" "$xcf" "$manifest" 2>&1 >/dev/null) || got=$?
  if [ "$got" != "$want" ]; then
    echo "FAIL $name: exit $got, wanted $want"; echo "  stderr: $err"; failures=$((failures + 1)); return
  fi
  if [ -n "$want_err" ] && [[ "$err" != *"$want_err"* ]]; then
    echo "FAIL $name: stderr lacks '$want_err'"; echo "  stderr: $err"; failures=$((failures + 1)); return
  fi
  echo "ok   $name"
}

fresh() {
  local dir="$workdir/$1.xcframework"
  rm -rf "$dir"; mkdir -p "$dir"
  printf '%s\n' "$dir"
}

# --- passes -----------------------------------------------------------------------------------

xcf=$(fresh match); add_slice "$xcf" ios-arm64 15.0; add_slice "$xcf" ios-arm64-simulator 15.0
write_manifest "$workdir/m.swift" '.iOS(.v15), .macOS(.v10_15)'
expect "both slices match .v15" 0 "$xcf" "$workdir/m.swift"

write_manifest "$workdir/m.swift" '.iOS("15.0")'
expect "string-form floor matches" 0 "$xcf" "$workdir/m.swift"

xcf=$(fresh minor); add_slice "$xcf" ios-arm64 15.1
write_manifest "$workdir/m.swift" '.iOS("15.1")'
expect "minor version matches" 0 "$xcf" "$workdir/m.swift"

# A comment naming an old floor above the real line must not be what gets compared.
xcf=$(fresh commented); add_slice "$xcf" ios-arm64 15.0
printf 'let package = Package(\n    // used to be .iOS(.v13), see #195\n    platforms: [.iOS(.v15)], // not .iOS(.v14)\n)\n' >"$workdir/m.swift"
expect "commented-out floors are ignored" 0 "$xcf" "$workdir/m.swift"

# --- the two directions of drift, both must fail -----------------------------------------------

xcf=$(fresh unsafe); add_slice "$xcf" ios-arm64 15.0; add_slice "$xcf" ios-arm64-simulator 15.0
write_manifest "$workdir/m.swift" '.iOS(.v13)'
expect "manifest below binary (#195) fails" 1 "$xcf" "$workdir/m.swift" "minos 15.0 but"

xcf=$(fresh over); add_slice "$xcf" ios-arm64 14.0; add_slice "$xcf" ios-arm64-simulator 14.0
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "manifest above binary (#208) fails" 1 "$xcf" "$workdir/m.swift" "minos 14.0 but"

# One slice drifting is enough — the second one is the one that must not be skipped.
xcf=$(fresh one); add_slice "$xcf" ios-arm64 15.0; add_slice "$xcf" ios-arm64-simulator 14.0
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "a single drifted slice fails" 1 "$xcf" "$workdir/m.swift" "ios-arm64-simulator: binary minos 14.0"

# The plist can disagree with the load command on its own.
xcf=$(fresh plist); add_slice "$xcf" ios-arm64 15.0 14.0
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "Info.plist drift fails" 1 "$xcf" "$workdir/m.swift" "MinimumOSVersion 14.0"

# A fat slice is two architectures in one binary; the pin can miss one of them.
xcf=$(fresh fat); add_slice "$xcf" ios-arm64_x86_64-simulator "$(printf '15.0\n14.0')" 15.0
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "a fat slice with one drifted architecture fails" 1 "$xcf" "$workdir/m.swift" "architectures disagree"

xcf=$(fresh fatok); add_slice "$xcf" ios-arm64_x86_64-simulator "$(printf '15.0\n15.0')" 15.0
expect "a fat slice with both architectures pinned passes" 0 "$xcf" "$workdir/m.swift"

# --- no vacuous green -----------------------------------------------------------------------

xcf=$(fresh empty)
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "empty xcframework fails" 1 "$xcf" "$workdir/m.swift" "no framework slices"

# A slice directory with nothing in it must not be skipped on the strength of its sibling.
xcf=$(fresh halfbuilt); add_slice "$xcf" ios-arm64 15.0; mkdir "$xcf/ios-arm64-simulator"
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "a slice without a framework fails" 1 "$xcf" "$workdir/m.swift" "ios-arm64-simulator: no .framework bundle"

# ...but a code-signature directory is not a slice.
xcf=$(fresh signed); add_slice "$xcf" ios-arm64 15.0; mkdir "$xcf/_CodeSignature"
expect "_CodeSignature is not a slice" 0 "$xcf" "$workdir/m.swift"

xcf=$(fresh nobinary); mkdir -p "$xcf/ios-arm64/Autograph.framework"; printf '15.0' >"$xcf/ios-arm64/Autograph.framework/Info.plist"
expect "framework without a binary fails" 1 "$xcf" "$workdir/m.swift" "no binary at"

xcf=$(fresh noplistkey); add_slice "$xcf" ios-arm64 15.0 ""
expect "Info.plist without MinimumOSVersion fails" 1 "$xcf" "$workdir/m.swift" "has no MinimumOSVersion"

xcf=$(fresh nofloor); add_slice "$xcf" ios-arm64 15.0
write_manifest "$workdir/m.swift" '.macOS(.v10_15)'
expect "manifest without an iOS floor fails" 1 "$xcf" "$workdir/m.swift" "declares no iOS floor"

xcf=$(fresh nominos); add_slice "$xcf" ios-arm64 ""
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "binary without LC_BUILD_VERSION fails" 1 "$xcf" "$workdir/m.swift" "no minos"

expect "missing xcframework is a usage error" 2 "$workdir/nope.xcframework" "$workdir/m.swift" "usage:"
expect "missing manifest is a usage error" 2 "$xcf" "$workdir/nope.swift" "manifest not found"

# ----------------------------------------------------------------------------------------------

if [ "$failures" -ne 0 ]; then
  echo "$failures failing case(s)"
  exit 1
fi
echo "all cases passed"
