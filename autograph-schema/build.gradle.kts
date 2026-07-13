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
    // Not a runtime dependency of this module — used only by GeneratedSampleTest.kt to prove a
    // hand-written stand-in for this generator's real output actually compiles and works against
    // the real Tracker/InMemoryTestTransport APIs, since this module has no Kotlin-compiler-driven
    // way to compile its own generated source as part of the test suite.
    testImplementation(projects.autographCore)
    testImplementation(projects.autographTest)
}
