// Deliberately a standalone build: the diamond must resolve from published coordinates, not
// from project dependencies, or it would not be a diamond at all.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // The Kotlin version has to be pinned here rather than in the plugins {} block, which
    // cannot read Gradle properties. Varying it is how the fixture exercises the *compiler*
    // axis of the diamond.
    val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.10")
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// Publishes under the SAME artifactId as `dependent`, at a higher version — it stands in for a
// later release of the same module, not a different module.
rootProject.name = "dependent"
