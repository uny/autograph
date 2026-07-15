plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    // iOS-only by design: every declaration here consults UIKit directly. Deliberately no Compose
    // dependency — a UIKit/SwiftUI-only app must be able to depend on this without pulling Compose
    // Multiplatform in. `autograph-compose` depends on THIS (iosMain only), never the reverse.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
