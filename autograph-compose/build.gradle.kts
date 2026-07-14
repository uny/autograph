plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    androidLibrary {
        namespace = "dev.ynagai.autograph.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.autographCore)
            // The ambient scope/screen-context stack that autocapture reads at capture time. Kept
            // internal here (LocalScopeStack), so it stays an implementation dependency.
            implementation(projects.autographContext)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmTest.dependencies {
            // Desktop runtime backing runComposeUiTest on the JVM target (skiko + the test rule).
            implementation(compose.desktop.currentOs)
        }
    }
}
