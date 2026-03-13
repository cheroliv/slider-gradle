package com.cheroliv.slider.ai

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal

/**
 * Base class for all RAG Gradle tasks.
 *
 * Declares [pgVectorService] as an `@Internal` property so Gradle correctly
 * tracks the [PgVectorService] dependency and keeps the service alive for the
 * entire duration of the task execution — including `doFirst` and `doLast`
 * actions registered after the fact.
 *
 * ## Why a typed task class instead of `tasks.register { usesService() }`?
 *
 * `usesService()` called inside a `tasks.register { }` configuration block
 * is not always honoured by Gradle's build service lifecycle manager —
 * the service can be closed before the task action completes.
 *
 * Declaring the service as an `@Internal` [Property] on a typed [DefaultTask]
 * subclass is the only approach that guarantees Gradle will:
 *   1. Instantiate the service before the task runs
 *   2. Keep it alive for the full duration of the task (including doFirst/doLast)
 *   3. Call `close()` only after all tasks that reference it have finished
 */
abstract class RagTask : DefaultTask() {

    @get:Internal
    abstract val pgVectorService: Property<PgVectorService>

    /**
     * Convenience accessor — starts the container on first call (idempotent),
     * then returns the live [PgVectorService] instance.
     */
    protected fun service(): PgVectorService =
        pgVectorService.get().also { it.start() }
}