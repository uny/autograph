// swift-tools-version:5.9
import Foundation
import PackageDescription

// Autograph.xcframework is built by Gradle
// (:autograph-apple:assembleAutographReleaseXCFramework), not committed to git. It is the single
// umbrella framework that carries the whole Kotlin iOS surface — tracker core, ambient scope/screen
// stack, UIKit capture, and the Segment transport bridge — so that a `Tracker`/`ScopeStack` is one
// ObjC type across every Swift product below (see autograph-apple/build.gradle.kts for why one
// framework, not several). Two consumption modes need two different binaryTarget shapes:
//  - Monorepo/CI dev: the local build output exists on disk — use it directly, so this package
//    always reflects whatever the Kotlin side currently builds, uncommitted changes included.
//  - An external app adding this package via `.package(url: "https://github.com/uny/autograph.git",
//    from: "…")`: SwiftPM clones only the repo's git content at that tag, so the local build
//    output doesn't exist — fall back to a checksummed download from that version's GitHub
//    Release asset.
private let localXCFrameworkPath = "autograph-apple/build/XCFrameworks/release/Autograph.xcframework"

// Resolved against THIS FILE, never the current directory. `binaryTarget(path:)` takes a
// package-relative path, but `fileExists` takes a process-relative one, and those are the same thing
// only when the manifest happens to be evaluated from the package root. The SwiftPM CLI chdirs there,
// so `swift build` at the root always agreed; Xcode resolving an `XCLocalSwiftPackageReference` does
// not, so `sample-ios/iosApp.xcodeproj` opened in Xcode.app silently took the download branch and
// failed to compile against the last *released* umbrella — while the same build from the repo root
// succeeded, which is why CI could never see it. Measured, both directions.
private let localXCFrameworkAbsolutePath = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .appendingPathComponent(localXCFrameworkPath)
    .path

// Don't hand-edit these ahead of a release — CD owns them. Kotlin/Native's build output isn't
// reproducible across separate builds, so a release's checksum is only knowable from the build
// that produces the zip actually being released; there is nothing to pre-compute and no mismatch
// to fail on. Instead cd.yml rewrites both values with what it just built, moves the tag onto
// that commit, and pushes the same rewrite to main. On a tag they therefore describe that tag's
// own release asset; on main they describe the most recent release.
//
// That main-side sync is a freshness fix, not an endorsement of `branch: "main"` as a way to
// consume this package: Sources/ would come from main's HEAD while this binary target is the last
// *released* Kotlin build, so the Swift and Kotlin halves can be out of step. Depend on a version.
private let releaseVersion = "0.11.0"
private let releaseChecksum = "0720e32b548bd2e91748799ac3c8940e9dcbe69769020d29bd117a053be4d376"

private let autographTarget: Target = FileManager.default.fileExists(atPath: localXCFrameworkAbsolutePath)
    ? .binaryTarget(name: "Autograph", path: localXCFrameworkPath)
    : .binaryTarget(
        name: "Autograph",
        url: "https://github.com/uny/autograph/releases/download/v\(releaseVersion)/Autograph.xcframework.zip",
        checksum: releaseChecksum
    )

let package = Package(
    name: "Autograph",
    // The iOS floor. The binary's `minos` is pinned to the same value in the Gradle build
    // (`ios-deploymentTarget` in gradle/libs.versions.toml) rather than left to Kotlin/Native's
    // default, which moves with the toolchain (15.0 under 2.4.x, 14.0 under 2.3.x) and did so
    // silently when #205 lowered Kotlin (#208). CI reads the built slices' LC_BUILD_VERSION back
    // and fails when they disagree with this line in either direction
    // (.github/scripts/check-ios-floor.sh, #197), so change the two together.
    //
    // 15 is a decision, not a default: lowering it needs evidence the framework runs on an iOS 14
    // device — nothing in this repo runs one — and the README's `role:` note relies on 15 being the
    // floor. An over-declared floor would be the safe direction (SwiftPM refusing a consumer the
    // binary could run) but the check refuses it anyway, because that is also what the pin
    // silently failing looks like.
    //
    // Autograph.xcframework only ships iOS device/simulator slices, so this package is iOS-only in
    // practice; macOS/tvOS/watchOS minimums are declared solely to satisfy SwiftPM's manifest-level
    // compatibility check against the analytics-swift dependency's own minimums, and describe no
    // binary of ours.
    platforms: [.iOS(.v15), .macOS(.v10_15), .tvOS(.v13), .watchOS(.v7)],
    products: [
        .library(name: "AutographSegmentSwift", targets: ["AutographSegmentSwift"]),
        // The Swift-side UI sugar over the Kotlin capture — `.autographScreen("Name")` for SwiftUI.
        // Tracker-agnostic (imports only the Autograph umbrella, no Segment), so a non-Segment app can
        // use it too.
        .library(name: "AutographUI", targets: ["AutographUI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/segmentio/analytics-swift", from: "1.9.0"),
    ],
    targets: [
        autographTarget,
        .target(
            name: "AutographSegmentSwift",
            dependencies: [
                "Autograph",
                .product(name: "Segment", package: "analytics-swift"),
            ]
        ),
        .testTarget(
            name: "AutographSegmentSwiftTests",
            dependencies: [
                "AutographSegmentSwift",
                .product(name: "Segment", package: "analytics-swift"),
            ]
        ),
        .target(
            name: "AutographUI",
            dependencies: ["Autograph"]
        ),
        .testTarget(
            name: "AutographUITests",
            dependencies: ["AutographUI", "Autograph"]
        ),
    ]
)
