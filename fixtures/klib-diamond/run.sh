#!/usr/bin/env bash
#
# Reproduces the mixed-version klib diamond described in ADR 0001 §4 and issue #104.
#
#   ./fixtures/klib-diamond/run.sh              # no compiler skew: the repo's Kotlin throughout
#   ./fixtures/klib-diamond/run.sh 2.2.20       # build the OLD half with an older Kotlin
#
# Publishes to the local Maven repository under the group `fixture`, which nothing else uses.
# Not wired into per-PR CI on purpose: Kotlin/Native link is already the CI critical path, and
# this answers a question that only changes when the toolchain does. Run it when bumping Kotlin
# or before a release that relaxes an ADR 0001 rule.
# Not `set -e`: two of the three arms are *expected* to fail, and a non-zero gradle exit is the
# measurement, not an error. Setup is a different matter — a failed publish would leave the
# consumer resolving stale artifacts from a previous run and quietly measure the wrong thing, so
# `publish` aborts explicitly below. That is not hypothetical: it happened while building this.
set -uo pipefail

cd "$(dirname "$0")" || exit 1
GRADLEW="$(cd ../.. && pwd)/gradlew"

# Read from the version catalog rather than pinned here. The instruction this fixture exists to
# support is "re-run it on a Kotlin bump" — with a hard-coded version, doing exactly that would
# have re-measured the old compiler and reported a reassuring green about nothing.
NEW_KOTLIN="$(sed -n 's/^kotlin = "\(.*\)"$/\1/p' ../../gradle/libs.versions.toml)"
if [ -z "$NEW_KOTLIN" ]; then
    echo "FATAL: could not read the kotlin version from gradle/libs.versions.toml" >&2
    exit 1
fi

OLD_KOTLIN="${1:-$NEW_KOTLIN}"

say() { printf '\n=== %s\n' "$*"; }

publish() { # <dir> <extra gradle args...>
    local dir="$1"; shift
    if ! "$GRADLEW" -p "$dir" publishAllPublicationsToMavenLocalRepository -q "$@"; then
        echo "FATAL: publishing '$dir' failed — aborting rather than measuring stale artifacts" >&2
        exit 1
    fi
}

say "old half built with Kotlin $OLD_KOTLIN; new core and consumer with $NEW_KOTLIN"

say "publishing core:1.0 (baseline API)"
publish core -PfixtureVersion=1.0 -PapiGen=v1 -PkotlinVersion="$OLD_KOTLIN"

say "publishing dependent:1.0 (compiled against core 1.0, never recompiled)"
publish dependent -PkotlinVersion="$OLD_KOTLIN"

say "publishing core:1.1 (adds the three ADR-permitted changes)"
publish core -PfixtureVersion=1.1 -PapiGen=v2 -PkotlinVersion="$NEW_KOTLIN"

say "publishing core:1.2 (negative control — a genuine ABI break)"
publish core -PfixtureVersion=1.2 -PapiGen=v3 -PkotlinVersion="$NEW_KOTLIN"

say "publishing dependent:1.1 (compiled against core 1.1)"
publish dependent-new -PkotlinVersion="$NEW_KOTLIN"

declare -a RESULTS=()

run_case() { # <case> <expected: pass|fail> <coordinates that must be in the graph...>
    local case="$1" expect="$2"; shift 2
    say "case=$case (expected: $expect)"

    local resolution
    resolution="$("$GRADLEW" -p consumer showResolution -q -Pcase="$case" 2>&1 | grep '^RESOLVED')"
    printf '%s\n' "$resolution"

    # Guard against a false red: an arm that "fails" because a coordinate did not resolve has
    # measured nothing about klib linkage, but still looks like the failure we expected. This
    # actually happened while building the fixture — the downgrade arm asked for an artifactId
    # that was never published, and reported a satisfying red.
    #
    # Both sides of the diamond are checked, not just the dependent: the `break` arm is the one
    # that makes this load-bearing. It expects a failure, so resolving core:1.1 instead of the
    # 1.2 under test would fail to compile and report exactly the red it was looking for.
    local coord
    for coord in "$@"; do
        if ! printf '%s\n' "$resolution" | grep -q "^RESOLVED $coord\$"; then
            RESULTS+=("$case: DID NOT RESOLVE $coord — arm is inert [UNEXPECTED]")
            return
        fi
    done

    rm -rf consumer/build

    # One invocation per target rather than one for both. Gradle stops at the first failing task,
    # so the combined form never ran iosSimulatorArm64Test on either failing arm — leaving the
    # "identical on both targets" result in the README asserted for macOS only.
    local target log actual verdict
    for target in macosArm64 iosSimulatorArm64; do
        log="/tmp/klib-diamond-$case-$target.log"
        if "$GRADLEW" -p consumer "${target}Test" -Pcase="$case" >"$log" 2>&1; then
            actual=pass
        else
            actual=fail
        fi

        if [ "$actual" != "$expect" ]; then
            verdict="UNEXPECTED"
        elif [ "$expect" = pass ]; then
            verdict="OK"
        elif grep -q 'IrLinkageError' "$log"; then
            verdict="OK"
            grep -o 'IrLinkageError[^<"]*' "$log" | head -1 || true
        else
            # A failing arm only counts if it failed for the reason under test. A compile error, an
            # unavailable simulator or a Gradle crash all exit non-zero and would otherwise be
            # accepted as the linkage failure the whole control depends on.
            verdict="UNEXPECTED (failed, but not with IrLinkageError)"
        fi
        RESULTS+=("$case/$target: expected=$expect actual=$actual [$verdict]")
    done
}

run_case upgrade   pass "fixture:dependent:1.0" "fixture:core:1.1"
run_case downgrade fail "fixture:dependent:1.1" "fixture:core:1.0"
run_case break     fail "fixture:dependent:1.0" "fixture:core:1.2"

say "summary (old half = Kotlin $OLD_KOTLIN)"
printf '%s\n' "${RESULTS[@]}"

for r in "${RESULTS[@]}"; do
    case "$r" in *UNEXPECTED*) exit 1 ;; esac
done
echo "all arms matched expectations"
