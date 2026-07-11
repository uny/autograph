// swift-tools-version:5.9
import PackageDescription

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
        // Built by :autograph-segment:assembleAutographSegmentXCFramework — run the Gradle
        // build before `swift build`/`swift test` in this package.
        .binaryTarget(
            name: "AutographSegment",
            path: "../autograph-segment/build/XCFrameworks/release/AutographSegment.xcframework"
        ),
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
