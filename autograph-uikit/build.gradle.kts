plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    // Not redundant: measured on #205, `abiValidation {}` runs the checks under KGP 2.4.10 and
    // leaves them SKIPPED and unwired from `check` under 2.3.21. Setting `enabled` explicitly is
    // what makes the gate independent of the KGP version — a vacuously-passing check is worse
    // than none (ADR 0001), and keep it even if the floor rises again. See CONTRIBUTING.
    abiValidation { enabled.set(true) }

    withSourcesJar(publish = true)

    // iOS-only by design: every declaration here consults UIKit directly. Deliberately no Compose
    // dependency — a UIKit/SwiftUI-only app must be able to depend on this without pulling Compose
    // Multiplatform in. `autograph-compose` depends on THIS (iosMain only), never the reverse.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Tracker (the events this reports) and ScopeStack (the ambient screen/scope they carry)
            // both appear in installAutographNativeTapCapture's signature, so `api`, not
            // `implementation` — a consumer calling it has to name these types.
            api(projects.autographCore)
            api(projects.autographContext)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
