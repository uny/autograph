plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
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

    androidLibrary {
        namespace = "dev.ynagai.autograph.segment"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    // iOS targets carry this module's iosMain (SegmentBridge, SegmentTransport). The Swift-consumable
    // framework itself is emitted by the `autograph-apple` umbrella, which exports this module along
    // with core/context/uikit into one `Autograph.xcframework` — see that module for why a single
    // framework is required.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.autographCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            api(libs.segment.analytics.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            // testEnvelope(): the plugin test asserts on known envelope field values, and
            // Envelope's constructor is internal to core (ADR 0001 §2a).
            implementation(projects.autographTest)
        }
    }
}
