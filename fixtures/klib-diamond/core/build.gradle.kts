plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "fixture"
version = providers.gradleProperty("fixtureVersion").getOrElse("1.0")

// Which generation of the API to compile. Published twice under different versions so the
// consumer can form a diamond:
//   v1 (as 1.0) — the baseline.
//   v2 (as 1.1) — adds the three changes ADR 0001 permits without a major bump.
//   v3 (as 1.2) — NEGATIVE CONTROL: a genuine ABI break, present only to prove the fixture
//                 can go red. Without it a green result would be worthless.
val apiGen = providers.gradleProperty("apiGen").getOrElse("v1")

kotlin {
    macosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf("src/$apiGen/kotlin"))
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
