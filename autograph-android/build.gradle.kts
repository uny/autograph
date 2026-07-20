plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

// SPIKE (feat/65-android-lifecycle-harness): the sole purpose of this module right now is to
// answer one infra question before PR-E — can the AGP KMP `androidLibrary` host test run
// Robolectric and drive real Android lifecycle callbacks (ActivityScenario / FragmentScenario)?
// publish/abiValidation/core+context wiring are deliberately omitted to isolate that question.
kotlin {
    explicitApi()

    androidLibrary {
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
