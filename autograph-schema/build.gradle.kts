plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.publish)
}

kotlin {
    explicitApi()
}

dependencies {
    compileOnly(gradleApi())
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    // For GenerateAutographEventsTaskTest.kt, which instantiates the real Task via ProjectBuilder.
    testImplementation(gradleApi())
    // Not a runtime dependency of this module — used only by GeneratedSampleTest.kt to prove a
    // hand-written stand-in for this generator's real output actually compiles and works against
    // the real Tracker/InMemoryTestTransport APIs, since this module has no Kotlin-compiler-driven
    // way to compile its own generated source as part of the test suite.
    testImplementation(projects.autographCore)
    testImplementation(projects.autographTest)
}

tasks.test {
    // ProjectBuilder (GenerateAutographEventsTaskTest) reflectively defines classes into the JVM's
    // bootstrap/platform class loaders, which the module system blocks by default on JDK 17+.
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}
