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
        namespace = "dev.ynagai.autograph.test"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.autographCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
