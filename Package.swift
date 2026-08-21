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
private let releaseVersion = "0.7.0"
private let releaseChecksum = "ee98ba96edb54bd10dd6964cddbcaf5379d7016ef816702f21fc54f4d34ba318"

private let autographTarget: Target = FileManager.default.fileExists(atPath: localXCFrameworkAbsolutePath)
    ? .binaryTarget(name: "Autograph", path: localXCFrameworkPath)
    : .binaryTarget(
        name: "Autograph",
        url: "https://github.com/uny/autograph/releases/download/v\(releaseVersion)/Autograph.xcframework.zip",
        checksum: releaseChecksum
    )

let package = Package(
    name: "Autograph",
    // `minos` is whatever Kotlin/Native defaults to — nothing in the Gradle build sets a deployment
    // target — so it moves when the compiler moves, and the compiler just did. `minVersion.ios` in
    // the toolchain's own `konan.properties` is 15.0 for 2.4.10 but **14.0 for the 2.3.21 this
    // library now builds with** (#205), so releases from here on ship a 14.0 binary where 0.7.0
    // shipped a 15.0 one.
    //
    // `.v15` is kept deliberately rather than lowered with it. Over-declaring is the safe direction
    // — SwiftPM refuses a consumer the binary would have run, rather than accepting one it cannot,
    // which is what this line used to do at `.v13`. Lowering it to `.v14` is a real decision, not
    // bookkeeping: `AutographUI` uses iOS 15 APIs with no `@available` gate precisely because this
    // floor made gating unnecessary (see README's `role:` note), so `.v14` needs those audited
    // first. Until then this declares a floor above the binary's, on purpose.
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
