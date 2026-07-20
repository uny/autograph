import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The Apple umbrella. It has no source of its own: its only job is to aggregate the Kotlin surface an
// iOS app consumes — the tracker core, the ambient scope/screen stack, the UIKit capture, and the
// Segment transport bridge — into ONE `Autograph.xcframework`.
//
// **Why one framework and not several.** Kotlin/Native embeds all reachable Kotlin code into each
// framework and prefixes every exported Objective-C class with that framework's name. Two frameworks
// that both reach `autograph-core` would therefore expose two *distinct* ObjC types (`AutographTracker`
// vs `SomethingElseTracker`), and a `Tracker` made through one could not be handed to the other. A
// hybrid app uses Segment transport *and* UIKit capture together, so both must come from a single
// framework for their `Tracker`/`ScopeStack` to be the same type.
//
// **Not published to Maven.** Kotlin/JVM/Android consumers depend on the individual `autograph-*`
// modules directly; this module exists solely to emit the xcframework, so it carries neither the
// publish plugin nor ABI validation (it declares no API of its own).
kotlin {
    // iOS-only: the umbrella ships only iOS device/simulator slices, matching the framework it emits.
    val xcf = XCFramework("Autograph")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Autograph"
            xcf.add(this)
            // Each module whose public API must appear in the framework header is exported explicitly —
            // `export` is not transitive, so re-exporting `autograph-uikit` (whose install functions are
            // the point of adding this umbrella) also requires exporting the `autograph-core` /
            // `autograph-context` types those signatures name.
            export(projects.autographCore)
            export(projects.autographContext)
            export(projects.autographUikit)
            export(projects.autographSegment)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.autographCore)
            api(projects.autographContext)
            api(projects.autographUikit)
            api(projects.autographSegment)
        }
    }
}
