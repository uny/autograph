plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

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
