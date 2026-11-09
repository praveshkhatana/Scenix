// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ScenixMac",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "ScenixMac", targets: ["ScenixMac"]),
    ],
    targets: [
        .executableTarget(
            name: "ScenixMac",
            path: "Sources/ScenixMac",
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("WebKit"),
            ],
        ),
        .testTarget(
            name: "ScenixMacTests",
            dependencies: ["ScenixMac"],
            path: "Tests/ScenixMacTests"
        ),
    ],
)
