plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary {
        namespace = "dev.ynagai.autograph.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // Regular (non-XCFramework) framework export: sample-ios/ embeds this directly from the local
    // build output via the standard KMP "embedAndSignAppleFrameworkForXcode" Gradle task, run from
    // an Xcode "Run Script" build phase — unlike autograph-segment's XCFramework export, this
    // isn't published for external consumption, so there's no need to bundle both device/simulator
    // slices into one distributable artifact.
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "sample_shared"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.autographCore)
            implementation(projects.autographCompose)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        iosMain.dependencies {
            // The native (non-Compose) tap capture the SwiftUI sample screen exercises. iOS-only, so
            // it cannot live in commonMain alongside the rest.
            implementation(projects.autographUikit)
            implementation(projects.autographContext)
        }
    }
}
