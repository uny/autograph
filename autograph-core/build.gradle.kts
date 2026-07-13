plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.publish)
}

// Generates AUTOGRAPH_VERSION from the project version so the envelope's `sdk` field always matches
// the published artifact — never a hand-maintained literal that can drift (see #50). Local/dev
// builds resolve to the project's default `0.0.0-SNAPSHOT`, which is a deliberate, filterable
// marker for unreleased builds.
val generateAutographVersion by tasks.registering {
    // Read the -Pversion / gradle.properties value directly (what CD passes for a release), falling
    // back to the SNAPSHOT marker for local/dev builds. Deliberately not project.version, whose
    // Gradle default is the string "unspecified" — that would leak "autograph/unspecified" into the
    // `sdk` field of dev builds.
    val version = providers.gradleProperty("version").getOrElse("0.0.0-SNAPSHOT")
    val outputDir = layout.buildDirectory.dir("generated/source/autographVersion")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/ynagai/autograph/AutographVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.ynagai.autograph
            |
            |/** The library version, generated from the Gradle project version. See build.gradle.kts. */
            |internal const val AUTOGRAPH_VERSION: String = "$version"
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    androidLibrary {
        namespace = "dev.ynagai.autograph"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAutographVersion)
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.startup)
        }
    }
}
