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
    // `enabled` defaults to false in KGP 2.3, so the block alone leaves every check task
    // SKIPPED and unwired from `check` — a vacuously-passing gate is worse than no gate
    // (ADR 0001). The 2.4 no-arg `abiValidation()` enabled it implicitly; 2.3 needs this.
    abiValidation { enabled.set(true) }

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
            // The ambient scope/screen-context stack that autocapture reads at capture time.
            // `api`, not `implementation`: AutographProvider takes a ScopeStack so a hybrid app can
            // share one stack with a native capture pipeline, which puts the type in this module's
            // public signature. LocalScopeStack itself stays internal.
            api(projects.autographContext)
            implementation(compose.runtime)
            // `api`, not `implementation`, for the reason autograph-context is above: these types are
            // in this module's public signature — `Modifier` on every tracking modifier, and `BoxScope`
            // as AutographElementScope's content receiver. An `implementation` dependency lands in
            // `runtimeElements` only (verified in the generated module metadata), so a consumer that
            // does not declare compose.foundation itself cannot resolve the receiver and the API is
            // uncallable. Almost every consumer declares it anyway; that is luck, not a contract.
            api(compose.foundation)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
        }
        iosMain.dependencies {
            // The UIKit accessibility-tree walk this module's iOS ElementResolver hit-tests. Lives
            // in its own module so the coming non-Compose iOS capture path can share it rather than
            // duplicate it, keeping the walk's hard-won coordinate handling in exactly one home.
            // `implementation`, so it stays out of this module's ABI.
            implementation(projects.autographUikit)
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
