// swift-tools-version:5.9
import Foundation
import PackageDescription

// AutographSegment.xcframework is built by Gradle
// (:autograph-segment:assembleAutographSegmentReleaseXCFramework), not committed to git. Two
// consumption modes need two different binaryTarget shapes:
//  - Monorepo/CI dev: the local build output exists on disk — use it directly, so this package
//    always reflects whatever the Kotlin side currently builds, uncommitted changes included.
//  - An external app adding this package via `.package(url: "https://github.com/uny/autograph.git",
//    from: "…")`: SwiftPM clones only the repo's git content at that tag, so the local build
//    output doesn't exist — fall back to a checksummed download from that version's GitHub
//    Release asset.
private let localXCFrameworkPath = "autograph-segment/build/XCFrameworks/release/AutographSegment.xcframework"

// Bump both together before tagging a release: the CD workflow re-derives the checksum from the
// zip it builds and fails the release if it doesn't match this value, so the two can't drift.
private let releaseVersion = "0.1.0"
private let releaseChecksum = "f8ced5ae5d97e08b848b61a1705fb7e85fd675d8372e48495fc4918cb5939c44"

private let autographSegmentTarget: Target = FileManager.default.fileExists(atPath: localXCFrameworkPath)
    ? .binaryTarget(name: "AutographSegment", path: localXCFrameworkPath)
    : .binaryTarget(
        name: "AutographSegment",
        url: "https://github.com/uny/autograph/releases/download/v\(releaseVersion)/AutographSegment.xcframework.zip",
        checksum: releaseChecksum
    )

let package = Package(
    name: "AutographSegmentSwift",
    // AutographSegment.xcframework only ships iOS device/simulator slices, so this package is
    // iOS-only in practice; macOS/tvOS/watchOS minimums are declared solely to satisfy SwiftPM's
    // manifest-level compatibility check against the analytics-swift dependency's own minimums.
    platforms: [.iOS(.v13), .macOS(.v10_15), .tvOS(.v13), .watchOS(.v7)],
    products: [
        .library(name: "AutographSegmentSwift", targets: ["AutographSegmentSwift"]),
    ],
    dependencies: [
        .package(url: "https://github.com/segmentio/analytics-swift", from: "1.9.0"),
    ],
    targets: [
        autographSegmentTarget,
        .target(
            name: "AutographSegmentSwift",
            dependencies: [
                "AutographSegment",
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
    ]
)
