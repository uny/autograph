plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "fixture"
version = "1.1"

kotlin {
    macosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                // Compiled against core 1.1, and uses members that exist only there. Linking it
                // against core 1.0 is the downgrade case.
                api("fixture:core:1.1")
            }
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
