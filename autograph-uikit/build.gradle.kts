plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    // `enabled` defaults to false in KGP 2.3, so the block alone leaves every check task
    // SKIPPED and unwired from `check` — a vacuously-passing gate is worse than no gate
    // (ADR 0001). The 2.4 no-arg `abiValidation()` enabled it implicitly; 2.3 needs this.
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
