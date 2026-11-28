#!/usr/bin/env bash
#
# Compare the Swift package products' API (AutographUI, AutographSegmentSwift) against the last
# release, with SwiftPM's API digester (ADR 0001 §1, #341).
#
# The baseline is `v<releaseVersion>` from Package.swift. On main and on a release prep branch that
# is the most recent release, because cd.yml rewrites it after each one. The baseline is checked
# out without a local framework, so it takes Package.swift's download branch and compiles against
# that release's asset. The current tree compiles against `autograph-apple/build`, so build the
# framework from this tree first: a stale or missing one makes this compare the wrong binary.
#
# The digester builds for the host by default, and Autograph.xcframework has only iOS slices, so
# that run fails. It runs only with an iOS simulator triple and SDK, at the manifest's iOS floor.
#
# `--build-system swiftbuild` is required, not a preference. Measured on the macos-26 runner (Xcode
# 26.6, Swift 6.3.3, #341): SwiftPM's default native build system builds both trees and then writes
# empty API baselines, so the comparison dies with "Unable to parse empty data". Swift Build writes
# real ones, and reports a deliberate rename. Swift 6.4 already defaults to Swift Build.
#
# Outcomes:
# - no breakage: exit 0.
# - breakage, baseline 1.0.0 or later: exit 1. ADR 0001 forbids it within a major version.
# - breakage, baseline before 1.0.0: a warning and exit 0, since a pre-1.0 break is allowed with a
#   CHANGELOG entry. AUTOGRAPH_SWIFT_API_ENFORCE=1 makes it fail anyway (used to mutation-test).
# - anything else (the build failed, the tag could not be fetched): exit 1, always. A check that
#   cannot run must not read as a pass.
#
# An intended break in a major release goes through SwiftPM's own allowlist: pass
# `--breakage-allowlist-path` in the invocation below for that release, and remove it afterwards.
#
# A local run uses a tag already present as is. cd.yml moves a release tag after creating it, so a
# tag fetched before that move is stale: delete it (`git tag -d vX.Y.Z`) before running by hand.
#
# Usage:  check-swift-api.sh [path/to/Package.swift]
#
# Needs `swift`, `xcrun` and `git` (`xcodebuild` only to print its version). Tested by
# check-swift-api.test.sh, which stubs all four.

set -euo pipefail

manifest="${1:-Package.swift}"
if [ ! -f "$manifest" ]; then
  echo "usage: $(basename "$0") [Package.swift] (no file at '$manifest')" >&2
  exit 2
fi

version=$(sed -nE 's/^private let releaseVersion = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p' "$manifest")
floor=$(sed -nE 's/.*\.iOS\(\.v([0-9]+)\).*/\1/p' "$manifest" | head -n 1)
if [ -z "$version" ] || [ -z "$floor" ]; then
  echo "::error::could not read releaseVersion and the iOS floor from $manifest (got '$version', '$floor')" >&2
  exit 2
fi
tag="v$version"

# git and swift act on the package that owns the manifest, not on whatever directory this ran from.
cd "$(dirname "$manifest")"

# actions/checkout fetches one commit and no tags.
if ! git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  if ! git fetch --no-tags --depth=1 origin "refs/tags/$tag:refs/tags/$tag"; then
    echo "::error::could not fetch the baseline tag $tag" >&2
    exit 1
  fi
fi

enforce=0
if [ "${version%%.*}" -ge 1 ] || [ "${AUTOGRAPH_SWIFT_API_ENFORCE:-0}" = "1" ]; then
  enforce=1
fi

# The digester's behaviour has differed between toolchains (#341), so say which one ran.
swift --version 2>&1 | head -n 1 || true
xcodebuild -version 2>/dev/null | head -n 1 || true

sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
set +e
output=$(swift package --build-system swiftbuild --triple "arm64-apple-ios$floor.0-simulator" --sdk "$sdk" \
  diagnose-api-breaking-changes "$tag" 2>&1)
status=$?
set -e
printf '%s\n' "$output"

# Every product must have reached a verdict. An exit code of 0 alone is not proof that anything was
# compared.
products="AutographUI AutographSegmentSwift"
missing=""
for product in $products; do
  grep -qE "(No breaking changes detected|breaking changes? detected) in $product:?[[:space:]]*\$" <<<"$output" ||
    missing="$missing $product"
done

if [ "$status" -eq 0 ]; then
  if [ -n "$missing" ]; then
    echo "::error::the API digester exited 0 but reported no verdict for:$missing" >&2
    exit 1
  fi
  echo "No Swift API break against $tag."
  exit 0
fi
# Here-strings, not `printf | grep -q`: under pipefail an early match kills printf with SIGPIPE on a
# long build log, and the pipeline would read as no match.
# A build error anywhere means at least one module was not compared, even when another reported a
# breakage, so it is checked first.
# Anchored so that a benign SwiftPM warning that merely contains the word ("skipping cache due to an
# error: ...", seen on the runner) does not count: an error line starts with `error:` or follows a
# `file:line:col: ` location.
if grep -qE '(^|: )error:|fatalError' <<<"$output"; then
  echo "::error::the API digester did not run to a result (exit $status); see the output above" >&2
  exit 1
fi
if grep -q 'API breakage:' <<<"$output"; then
  if [ -n "$missing" ]; then
    echo "::error::the API digester reported a breakage but no verdict for:$missing" >&2
    exit 1
  fi
  if [ "$enforce" -eq 1 ]; then
    echo "::error::the Swift package products break their API against $tag (ADR 0001 §1)" >&2
    exit 1
  fi
  echo "::warning::the Swift package products break their API against $tag. Allowed before 1.0; record it in the CHANGELOG."
  exit 0
fi
echo "::error::the API digester did not run to a result (exit $status); see the output above" >&2
exit 1
