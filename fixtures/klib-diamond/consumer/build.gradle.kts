plugins {
    kotlin("multiplatform")
}

// Which arm of the matrix to run. Each pins a different (dependent, core) pair:
//
//   upgrade   — dependent:1.0 (built against core 1.0) + core:1.1. The case ADR 0001 §4 was
//               unsure about, and the one Gradle's default conflict resolution actually produces.
//   downgrade — dependent:1.1 (built against core 1.1) + core:1.0, forced. Gradle never picks
//               this on its own; it takes a deliberate `strictly` or `force`.
//   break     — dependent:1.0 + core:1.2, whose `Transport.send` gained a required parameter.
//               The negative control.
val case = providers.gradleProperty("case").getOrElse("upgrade")

val dependentVersion = if (case == "downgrade") "1.1" else "1.0"
val coreVersion = when (case) {
    "upgrade" -> "1.1"
    "downgrade" -> "1.0"
    "break" -> "1.2"
    else -> error("unknown -Pcase=$case (expected upgrade | downgrade | break)")
}

kotlin {
    macosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest {
            kotlin.setSrcDirs(listOf("src/$case/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("fixture:dependent:$dependentVersion")
                if (case == "downgrade") {
                    // Gradle's default is highest-wins, which is the *safe* direction. Getting
                    // the unsafe one requires saying so out loud.
                    implementation("fixture:core") { version { strictly(coreVersion) } }
                } else {
                    implementation("fixture:core:$coreVersion")
                }
            }
        }
    }
}

/** Prints the resolved graph, so the diamond is demonstrated rather than assumed. */
tasks.register("showResolution") {
    val cfg = configurations.named("macosArm64TestCompileKlibraries")
    doLast {
        cfg.get().incoming.resolutionResult.allComponents
            .map { it.id.displayName }
            .filter { it.startsWith("fixture:") }
            .sorted()
            .forEach { println("RESOLVED $it") }
    }
}
