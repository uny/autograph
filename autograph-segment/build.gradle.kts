plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
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
