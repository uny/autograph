package dev.ynagai.autograph.schema

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class GenerateAutographEventsTaskTest {

    private fun newTask(projectDir: File): GenerateAutographEventsTask {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        return project.tasks.register("generateAutographEvents", GenerateAutographEventsTask::class.java).get()
    }

    /** Dispatches through Gradle's real task-action mechanism, not a direct method call — proves @TaskAction is wired. */
    private fun runTask(task: GenerateAutographEventsTask) {
        task.actions.forEach { it.execute(task) }
    }

    @Test
    fun writesGeneratedSourceToTheNestedPackagePath() {
        val projectDir = createTempDirectory().toFile()
        val schemaFile = File(projectDir, "tracking-plan.json").apply {
            writeText("""{ "events": [ { "name": "App Opened" } ] }""")
        }
        val outputDir = File(projectDir, "build/generated")

        val task = newTask(projectDir)
        task.schemaFile.set(schemaFile)
        task.packageName.set("com.example.generated")
        task.outputDirectory.set(outputDir)

        runTask(task)

        val outputFile = File(outputDir, "com/example/generated/AutographEvents.kt")
        assertTrue(outputFile.exists(), "expected $outputFile to exist")
        assertTrue(outputFile.readText().contains("public fun Tracker.trackAppOpened("))
    }

    @Test
    fun createsParentDirectoriesThatDoNotYetExist() {
        val projectDir = createTempDirectory().toFile()
        val schemaFile = File(projectDir, "tracking-plan.json").apply { writeText("""{ "events": [] }""") }
        // outputDirectory itself does not exist yet — generate() must create it (and the nested
        // package path under it) rather than fail.
        val outputDir = File(projectDir, "build/does/not/exist/yet")

        val task = newTask(projectDir)
        task.schemaFile.set(schemaFile)
        task.packageName.set("p")
        task.outputDirectory.set(outputDir)

        runTask(task)

        assertTrue(File(outputDir, "p/AutographEvents.kt").exists())
    }

    @Test
    fun usesTheConventionPackageNameWhenNotSet() {
        val projectDir = createTempDirectory().toFile()
        val schemaFile = File(projectDir, "tracking-plan.json").apply { writeText("""{ "events": [] }""") }
        val outputDir = File(projectDir, "build/generated")

        val task = newTask(projectDir)
        task.schemaFile.set(schemaFile)
        task.outputDirectory.set(outputDir)

        runTask(task)

        assertTrue(File(outputDir, "dev/ynagai/autograph/schema/generated/AutographEvents.kt").exists())
    }
}
