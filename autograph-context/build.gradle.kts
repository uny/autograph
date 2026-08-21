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
        namespace = "dev.ynagai.autograph.context"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // JsonObject is part of this module's public API (scope properties / enrich), so `api`.
            api(libs.kotlinx.serialization.json)
            // Reuses core's EmptyJsonObject and shares its JSON conventions. core never depends on
            // this module, so core's ABI-freeze surface (#53) is unaffected by anything here.
            api(projects.autographCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
