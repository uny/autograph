package dev.ynagai.autograph.schema

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates typed `Tracker.track<EventName>(...)` extension functions from a [schemaFile]
 * tracking-plan document (see [parseTrackingPlan] for the supported shape) into [outputDirectory].
 *
 * This is a plain [org.gradle.api.Task], registered and wired manually — a convenience Gradle
 * plugin that applies and wires this automatically is a planned follow-up, not yet shipped by this
 * module:
 *
 * ```kotlin
 * val generateEvents = tasks.register<GenerateAutographEventsTask>("generateAutographEvents") {
 *     schemaFile.set(layout.projectDirectory.file("tracking-plan.json"))
 *     packageName.set("com.example.analytics.generated")
 *     outputDirectory.set(layout.buildDirectory.dir("generated/autographSchema"))
 * }
 * kotlin.sourceSets.commonMain {
 *     kotlin.srcDir(generateEvents.map { it.outputDirectory })
 * }
 * ```
 */
public abstract class GenerateAutographEventsTask : DefaultTask() {

    /** The tracking-plan JSON Schema document to generate from. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val schemaFile: RegularFileProperty

    /** The package the generated extension functions are declared under. */
    @get:Input
    public abstract val packageName: Property<String>

    /** Where the generated Kotlin source file is written; wire this into a Kotlin source set. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    init {
        packageName.convention("dev.ynagai.autograph.schema.generated")
    }

    @TaskAction
    public fun generate() {
        val events = parseTrackingPlan(schemaFile.get().asFile.readText())
        val source = generateTrackerExtensions(events, packageName.get())

        val packagePath = packageName.get().replace('.', '/')
        val outputFile = outputDirectory.get().asFile.resolve(packagePath).resolve("AutographEvents.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(source)
    }
}
