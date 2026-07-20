plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

// This module currently ships no production code — it exists to host the Android lifecycle test
// harness for #65 PR-E (Robolectric-driven Activity/Fragment lifecycle sentinels). PR-E adds the
// screen-capture install API, and with it publish/abiValidation and core+context wiring.
kotlin {
    explicitApi()

    // `android { }`, not the older `androidLibrary { }` that autograph-segment/-compose still use:
    // that block is deprecated in this AGP and emits a compiler warning. New module → current DSL.
    android {
        namespace = "dev.ynagai.autograph.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {
            // Robolectric needs the merged Android resources/manifest on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.fragment)
        }
    }
}
