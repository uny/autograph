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
# Each stub reads the number it should report from the file it is pointed at — the "binary" holds
# its minos, the Info.plist holds its MinimumOSVersion — so a case is set up by writing those files.
# That is also what makes this runnable on ubuntu, where neither tool exists.
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
# Real output has more lines (platform, sdk); only the shape of the minos line matters.
minos=$(cat "${@: -1}")
[ -n "$minos" ] || exit 0
printf 'Load command 10\n      cmd LC_BUILD_VERSION\n platform IOS\n    minos %s\n      sdk 26.0\n' "$minos"
EOF
cat >"$stub_dir/plutil" <<'EOF'
#!/usr/bin/env bash
cat "${@: -1}"
EOF
chmod +x "$stub_dir/vtool" "$stub_dir/plutil"
export PATH="$stub_dir:$PATH"

# $1 = xcframework dir, $2 = slice name, $3 = binary minos, $4 = plist MinimumOSVersion ($3 if omitted).
add_slice() {
  local fw="$1/$2/Autograph.framework"
  mkdir -p "$fw"
  printf '%s' "$3" >"$fw/Autograph"
  printf '%s' "${4:-$3}" >"$fw/Info.plist"
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

# --- no vacuous green -----------------------------------------------------------------------

xcf=$(fresh empty)
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "empty xcframework fails" 1 "$xcf" "$workdir/m.swift" "no framework slices"

xcf=$(fresh nofloor); add_slice "$xcf" ios-arm64 15.0
write_manifest "$workdir/m.swift" '.macOS(.v10_15)'
expect "manifest without an iOS floor fails" 1 "$xcf" "$workdir/m.swift" "declares no iOS floor"

xcf=$(fresh nominos); add_slice "$xcf" ios-arm64 ""
write_manifest "$workdir/m.swift" '.iOS(.v15)'
expect "binary without LC_BUILD_VERSION fails" 1 "$xcf" "$workdir/m.swift" "no minos"

expect "missing xcframework is a usage error" 2 "$workdir/nope.xcframework" "$workdir/m.swift"
expect "missing manifest is a usage error" 2 "$xcf" "$workdir/nope.swift"

# ----------------------------------------------------------------------------------------------

if [ "$failures" -ne 0 ]; then
  echo "$failures failing case(s)"
  exit 1
fi
echo "all cases passed"
