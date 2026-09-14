plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ynagai.autograph.sample.android"
    compileSdk = libs.versions.android.sampleCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.ynagai.autograph.sample.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.sampleCompileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources/manifest on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.sampleShared)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    // For NativeTapsActivity's fixtures: a Compose island embedded in a View tree (the case the tap
    // capture must decline, leaving it to the Compose pipeline) and a RecyclerView row (the case it
    // must report by the row's own id, unlike a ListView).
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // The non-Compose native demos (NativeScreensActivity + fragments, NativeTapsActivity) that the
    // instrumented smokes drive on a real device — the coverage Robolectric cannot give.
    implementation(projects.autographAndroid)
    implementation(projects.autographCore)
    implementation(projects.autographContext)
    implementation(libs.androidx.fragment)

    // The hybrid host test (ComposeHostMaskTest) lives here rather than in autograph-android because
    // it needs BOTH halves at once: the native screen capture and a real AutographProvider /
    // TrackedScreen composition. autograph-android is deliberately Compose-free and has no Compose
    // compiler plugin, so `setContent {}` there fails at runtime with NoSuchMethodError. This module
    // is a Compose app that already depends on the native capture, and it is not published.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(projects.autographCompose)
    // Test-only: ComposeTapOriginTest drives a real NavHost, which is the only way to exercise the
    // route frame `NavController.TrackScreenViews` pushes against a native surface beside it. The
    // app's own code does not use navigation-compose; the debug variant also gets it below, but the
    // release unit tests (`./gradlew build` runs both) still need these.
    testImplementation(libs.jetbrains.navigation.compose)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.runner)
    // OriginOnDeviceTest's fixtures (src/debug): real AutographProvider / TrackedScreen compositions,
    // a NavHost route, and a real ViewPager2 (the demotion Robolectric never runs). Debug only — the
    // app's own code uses none of the three (autograph-compose already reaches the runtime classpath
    // through sample-shared, but not this module's compile classpath), and the fixtures must be in the
    // app APK, not the test APK, for ActivityScenario to launch them in the app's process.
    debugImplementation(projects.autographCompose)
    debugImplementation(libs.jetbrains.navigation.compose)
    debugImplementation("androidx.viewpager2:viewpager2:1.1.0")
}
