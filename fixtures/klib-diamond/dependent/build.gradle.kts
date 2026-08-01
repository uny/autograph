plugins {
    // Overridable so the fixture can also vary the *compiler* axis: a real
    // `autograph-segment:1.0` was built weeks earlier, by an older Kotlin than the one
    // linking it. That axis is what Kotlin's "no klib forward compatibility" refers to.
    kotlin("multiplatform")
    `maven-publish`
}

group = "fixture"
version = "1.0"

kotlin {
    macosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                // Compiled against core 1.0 and published as such. Never recompiled — that is
                // the whole point.
                api("fixture:core:1.0")
            }
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
