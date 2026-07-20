package dev.ynagai.autograph.apple

/**
 * Marker giving Kotlin/Native a source to compile, so `Autograph.xcframework` is actually linked.
 *
 * This module carries no API of its own — its whole job is to aggregate and re-`export`
 * core/context/uikit/segment into one framework (see `build.gradle.kts`). But a Kotlin/Native module
 * with no source of its own links as `NO-SOURCE` and produces no framework at all, so one internal
 * declaration has to exist for the exported modules to be assembled into anything.
 */
internal const val AUTOGRAPH_APPLE_UMBRELLA: String = "autograph-apple"
