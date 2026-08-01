#!/usr/bin/env bash
#
# Reproduces the mixed-version klib diamond described in ADR 0001 §4 and issue #104.
#
#   ./fixtures/klib-diamond/run.sh              # default compiler skew (2.4.10 throughout)
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

OLD_KOTLIN="${1:-2.4.10}"
NEW_KOTLIN=2.4.10

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

run_case() { # <case> <expected: pass|fail> <coordinate that must be in the graph>
    local case="$1" expect="$2" must_resolve="$3"
    say "case=$case (expected: $expect)"

    local resolution
    resolution="$("$GRADLEW" -p consumer showResolution -q -Pcase="$case" 2>&1 | grep '^RESOLVED')"
    printf '%s\n' "$resolution"

    # Guard against a false red: an arm that "fails" because a coordinate did not resolve has
    # measured nothing about klib linkage, but still looks like the failure we expected. This
    # actually happened while building the fixture — the downgrade arm asked for an artifactId
    # that was never published, and reported a satisfying red.
    if ! printf '%s\n' "$resolution" | grep -q "^RESOLVED $must_resolve\$"; then
        RESULTS+=("$case: DID NOT RESOLVE $must_resolve — arm is inert [UNEXPECTED]")
        return
    fi

    rm -rf consumer/build
    if "$GRADLEW" -p consumer macosArm64Test iosSimulatorArm64Test -Pcase="$case" >/tmp/klib-diamond-$case.log 2>&1; then
        actual=pass
    else
        actual=fail
        grep -o 'IrLinkageError[^<"]*' /tmp/klib-diamond-$case.log | head -1 || true
    fi
    if [ "$actual" = "$expect" ]; then verdict="OK"; else verdict="UNEXPECTED"; fi
    RESULTS+=("$case: expected=$expect actual=$actual [$verdict]")
}

run_case upgrade   pass "fixture:dependent:1.0"
run_case downgrade fail "fixture:dependent:1.1"
run_case break     fail "fixture:dependent:1.0"

say "summary (old half = Kotlin $OLD_KOTLIN)"
printf '%s\n' "${RESULTS[@]}"

for r in "${RESULTS[@]}"; do
    case "$r" in *UNEXPECTED*) exit 1 ;; esac
done
echo "all arms matched expectations"
