#!/usr/bin/env bash
#
# Tests for check-swift-api.sh.
#
# The real check needs a built Autograph.xcframework and a macOS runner, and only the release after
# 1.0 can produce its enforcing branch for real. These run on every PR instead, on ubuntu as well as
# macOS, and cover how the script reads the manifest and turns the digester's result into an exit
# code.
#
# `swift`, `xcrun`, `xcodebuild` and `git` are stubbed by putting fakes first on PATH, so the script's real
# invocations are what run. Each stub refuses any argv other than the one the script is expected to
# use, so a typo fails here rather than only on a macOS runner. The `swift` stub prints what
# $SWIFT_STUB_OUTPUT holds, then the file $SWIFT_STUB_OUTPUT_FILE names (if any), and exits with
# $SWIFT_STUB_STATUS. The `git` stub knows the tags listed
# in $GIT_STUB_TAGS, fetches only $GIT_STUB_REMOTE_TAGS, and logs each fetch to $GIT_STUB_LOG.
#
# Usage: .github/scripts/check-swift-api.test.sh

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
subject="$script_dir/check-swift-api.sh"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

stub_dir="$workdir/bin"
mkdir -p "$stub_dir"
cat >"$stub_dir/xcrun" <<'EOF'
#!/usr/bin/env bash
[ "$*" = "--sdk iphonesimulator --show-sdk-path" ] || { echo "xcrun stub: unexpected argv: $*" >&2; exit 64; }
echo /stub/iPhoneSimulator.sdk
EOF
cat >"$stub_dir/swift" <<'EOF'
#!/usr/bin/env bash
[ "$*" = "--version" ] && { echo "Apple Swift version 0.0 (stub)"; exit 0; }
expected="package --build-system swiftbuild --triple arm64-apple-ios${SWIFT_STUB_FLOOR:-15}.0-simulator --sdk /stub/iPhoneSimulator.sdk diagnose-api-breaking-changes ${SWIFT_STUB_TAG:?}"
[ "$*" = "$expected" ] || { echo "swift stub: unexpected argv: $*" >&2; exit 64; }
printf '%s\n' "${SWIFT_STUB_OUTPUT:-}"
[ -z "${SWIFT_STUB_OUTPUT_FILE:-}" ] || cat "$SWIFT_STUB_OUTPUT_FILE"
exit "${SWIFT_STUB_STATUS:-0}"
EOF
cat >"$stub_dir/git" <<'EOF'
#!/usr/bin/env bash
[ -z "${GIT_STUB_PWD_LOG:-}" ] || pwd >>"$GIT_STUB_PWD_LOG"
case "$*" in
  "rev-parse -q --verify refs/tags/"*)
    tag=${4#refs/tags/}
    case " ${GIT_STUB_TAGS:-} " in *" $tag "*) echo deadbeef; exit 0 ;; esac
    exit 1 ;;
  "fetch --no-tags --depth=1 origin refs/tags/"*)
    tag=${5%%:*}; tag=${tag#refs/tags/}
    echo "$tag" >>"${GIT_STUB_LOG:?}"
    case " ${GIT_STUB_REMOTE_TAGS:-} " in *" $tag "*) exit 0 ;; esac
    echo "fatal: couldn't find remote ref refs/tags/$tag" >&2; exit 128 ;;
esac
echo "git stub: unexpected argv: $*" >&2; exit 64
EOF
cat >"$stub_dir/xcodebuild" <<'EOF'
#!/usr/bin/env bash
[ "$*" = "-version" ] || { echo "xcodebuild stub: unexpected argv: $*" >&2; exit 64; }
echo "Xcode 0.0 (stub)"
EOF
chmod +x "$stub_dir/xcrun" "$stub_dir/swift" "$stub_dir/git" "$stub_dir/xcodebuild"
export PATH="$stub_dir:$PATH"
export GIT_STUB_LOG="$workdir/fetches"

# $1 = manifest path, $2 = releaseVersion, $3 = iOS floor.
write_manifest() {
  printf 'private let releaseVersion = "%s"\nlet package = Package(\n    platforms: [.iOS(.v%s), .macOS(.v10_15)],\n)\n' "$2" "$3" >"$1"
}

failures=0
# $1 = name, $2 = expected exit code, $3 = text the output must contain ("" for none),
# then the command and its arguments.
expect() {
  local name="$1" want="$2" needle="$3" got out
  shift 3
  : >"$GIT_STUB_LOG"
  set +e
  out=$("$@" 2>&1)
  got=$?
  set -e
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $name: exit $got, want $want"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1)); return
  fi
  if [ -n "$needle" ] && ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL $name: output lacks '$needle'"; printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1)); return
  fi
  echo "ok   $name"
}

clean="No breaking changes detected in AutographSegmentSwift

No breaking changes detected in AutographUI"
breakage_ui="1 breaking change detected in AutographUI:
  💔 API breakage: func View.autographIgnore() has been renamed to func autographIgnored()"
benign="warning: 'analytics-swift': skipping cache due to an error: The file 'maintenance.lock' does not exist."
breakage="$benign
No breaking changes detected in AutographSegmentSwift

$breakage_ui"

pre="$workdir/pre/Package.swift"; mkdir -p "${pre%/*}"; write_manifest "$pre" 0.13.0 15
post="$workdir/post/Package.swift"; mkdir -p "${post%/*}"; write_manifest "$post" 1.2.0 16

export GIT_STUB_TAGS="v0.13.0 v1.2.0"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="$clean" \
  expect "no break passes" 0 "No Swift API break against v0.13.0" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage" \
  expect "a break before 1.0 warns and passes" 0 "::warning::" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="No breaking changes detected in AutographUI" \
  expect "exit 0 with a verdict for only one product fails" 1 "no verdict for: AutographSegmentSwift" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage_ui" \
  expect "a breakage with no verdict for the other product fails" 1 "no verdict for: AutographSegmentSwift" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="No breaking changes detected in AutographUIV2
No breaking changes detected in AutographSegmentSwift" \
  expect "a verdict for a longer module name does not count" 1 "no verdict for: AutographUI" "$subject" "$pre"

pwd_log="$workdir/pwd.log"; : >"$pwd_log"
cd "$workdir"
GIT_STUB_PWD_LOG="$pwd_log" SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="$clean" \
  expect "git runs in the manifest's directory" 0 "" "$subject" "$pre"
cd - >/dev/null
if [ "$(sort -u "$pwd_log")" != "$(cd "${pre%/*}" && pwd)" ]; then
  echo "FAIL git runs in the manifest's directory: ran in '$(sort -u "$pwd_log" | tr '\n' ' ')'"; failures=$((failures + 1))
fi

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="" \
  expect "exit 0 with no output fails" 1 "no verdict for: AutographUI AutographSegmentSwift" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage" AUTOGRAPH_SWIFT_API_ENFORCE=1 \
  expect "a break before 1.0 fails when enforced" 1 "::error::the Swift package products break" "$subject" "$pre"

SWIFT_STUB_TAG=v1.2.0 SWIFT_STUB_FLOOR=16 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage" \
  expect "a break from 1.0 on fails, at the manifest's floor" 1 "::error::the Swift package products break" "$subject" "$post"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage
/w/Sources/AutographUI/AutographButton.swift:103:14: error: no exact matches in call to initializer" \
  expect "a located compile error beside a breakage fails" 1 "did not run to a result" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="error: Build failed" \
  expect "a digester that did not finish fails even before 1.0" 1 "did not run to a result" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage
error: Build failed
error: fatalError" \
  expect "a breakage beside a build failure fails even before 1.0" 1 "did not run to a result" "$subject" "$pre"

# Larger than a pipe buffer, and passed through a file: Linux caps one environment string at 128 KiB.
long_log="$workdir/long.log"
for i in $(seq 1 4000); do echo "[$i/4000] Compiling Something.swift with a long enough line"; done >"$long_log"
SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_STATUS=1 SWIFT_STUB_OUTPUT="$breakage" SWIFT_STUB_OUTPUT_FILE="$long_log" \
  expect "a breakage early in a long log is still a breakage" 0 "::warning::" "$subject" "$pre"

SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="$clean" \
  expect "a tag already present is not fetched" 0 "" "$subject" "$pre"
if [ -s "$GIT_STUB_LOG" ]; then echo "FAIL a tag already present is not fetched: fetched $(cat "$GIT_STUB_LOG")"; failures=$((failures + 1)); fi

GIT_STUB_TAGS="" GIT_STUB_REMOTE_TAGS="v0.13.0" SWIFT_STUB_TAG=v0.13.0 SWIFT_STUB_OUTPUT="$clean" \
  expect "a missing tag is fetched" 0 "No Swift API break" "$subject" "$pre"
if [ "$(cat "$GIT_STUB_LOG")" != "v0.13.0" ]; then echo "FAIL a missing tag is fetched: fetch log '$(cat "$GIT_STUB_LOG")'"; failures=$((failures + 1)); fi

GIT_STUB_TAGS="" GIT_STUB_REMOTE_TAGS="" SWIFT_STUB_TAG=v0.13.0 \
  expect "an unfetchable tag fails" 1 "could not fetch the baseline tag v0.13.0" "$subject" "$pre"

bad="$workdir/bad/Package.swift"; mkdir -p "${bad%/*}"; printf 'let package = Package()\n' >"$bad"
expect "a manifest without releaseVersion is a usage error" 2 "could not read releaseVersion" "$subject" "$bad"
expect "a missing manifest is a usage error" 2 "usage:" "$subject" "$workdir/none/Package.swift"

if [ "$failures" -ne 0 ]; then
  echo "$failures case(s) failed"
  exit 1
fi
echo "all cases passed"
