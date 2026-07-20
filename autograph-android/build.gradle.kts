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

    // Android-only by design, and deliberately Compose-free: a plain View/Fragment app must be able to
    // depend on this native screen capture without pulling Compose Multiplatform in — the same stance
    // autograph-uikit takes on iOS. The Compose-host exclusion needed to avoid double-counting a
    // Compose screen is done by *reflective* AbstractComposeView detection (see AndroidScreenCapture),
    // not a compile dependency on Compose.
    //
    // `android { }`, not the deprecated `androidLibrary { }` that autograph-segment/-compose still use.
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
        androidMain.dependencies {
            // Tracker (the events this reports) and ScopeStack (the ambient screen/scope stack it
            // pushes screen frames onto, plus the shared emitScreenView) are both named in the install
            // signature, so `api`, not `implementation`.
            api(projects.autographCore)
            api(projects.autographContext)
            // Fragment appears in the public fragmentScreenName predicate signature, so `api`.
            api(libs.androidx.fragment)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            // Test-only: a real AbstractComposeView + a runtime to compose it, so the reflective
            // Compose-host exclusion can be exercised. The main module stays Compose-free; these never
            // reach published artifacts.
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.runtime)
        }
    }
}
