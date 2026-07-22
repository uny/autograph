import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.publish) apply false
    alias(libs.plugins.dokka)
}

// The published library modules whose API reference is aggregated into one Dokka site. The Apple
// umbrella (autograph-apple) carries no Kotlin API of its own, and the samples are apps — neither is
// documented.
val documentedModules = listOf(
    "autograph-core", "autograph-context", "autograph-compose", "autograph-segment",
    "autograph-uikit", "autograph-android", "autograph-test", "autograph-schema",
)

dependencies {
    documentedModules.forEach { dokka(project(":$it")) }
}

dokka {
    moduleName.set("Autograph")
}

subprojects {
    group = "dev.ynagai.autograph"
    version = (findProperty("version") as String?) ?: "0.0.0-SNAPSHOT"

    if (name in listOf(
            "autograph-core", "autograph-context", "autograph-compose", "autograph-segment",
            "autograph-uikit", "autograph-android", "autograph-test", "autograph-schema",
        )
    ) {
        apply(plugin = "org.jetbrains.dokka")
    }

    // Kotlin's SwiftPM support attaches an artifact with classifier "swiftpm-metadata" and an
    // empty extension (trailing dot, no file type) that Maven Central rejects. This registers
    // regardless of whether a module actually declares swiftPMDependencies (autograph-segment's
    // XCFramework export alone is enough to trigger it). Removing from publication.artifacts
    // alone breaks GenerateModuleMetadata because it derives componentArtifacts from the
    // outgoing configuration.
    // TODO: Remove when fixed upstream (https://youtrack.jetbrains.com/issue/KT-85476)
    gradle.projectsEvaluated {
        fun isSwiftpmMetadata(classifier: String?, extension: String) =
            classifier == "swiftpm-metadata" && extension.isEmpty()
        configurations
            .findByName("swiftPMDependenciesMetadataElements")
            ?.outgoing?.artifacts?.removeIf {
                isSwiftpmMetadata(it.classifier, it.extension)
            }
        extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublication>()?.all {
            artifacts.removeAll {
                isSwiftpmMetadata(it.classifier, it.extension)
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            pom {
                name.set(project.name)
                description.set("Autograph - ${project.name}")
                url.set("https://github.com/uny/autograph")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("uny")
                        name.set("Yuki Nagai")
                        url.set("https://github.com/uny")
                    }
                }
                scm {
                    url.set("https://github.com/uny/autograph")
                    connection.set("scm:git:https://github.com/uny/autograph.git")
                    developerConnection.set("scm:git:https://github.com/uny/autograph.git")
                }
            }
        }
    }
}
