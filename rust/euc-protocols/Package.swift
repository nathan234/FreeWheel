// swift-tools-version:5.9
// Local Swift package exposing the Rust euc-protocols crate to the iOS app.
// Wraps the prebuilt XCFramework (FFI symbols) + the UniFFI-generated Swift
// bindings in their own module, so the Rust type names (WheelIdentity,
// BmsState, ...) don't shadow the KMP FreeWheelCore types in the app module.
import PackageDescription

let package = Package(
    name: "EucProtocols",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        .library(name: "EucProtocols", targets: ["EucProtocols"]),
    ],
    targets: [
        .binaryTarget(
            name: "euc_protocolsFFI",
            path: "EucProtocols.xcframework"
        ),
        .target(
            name: "EucProtocols",
            dependencies: ["euc_protocolsFFI"],
            path: "swift/EucProtocols"
        ),
    ]
)
