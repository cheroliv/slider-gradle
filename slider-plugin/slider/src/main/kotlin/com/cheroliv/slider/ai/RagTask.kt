package com.cheroliv.slider.ai

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.work.DisableCachingByDefault

/**
 * Base class for all RAG Gradle tasks.
 *
 * ## Why @ServiceReference and not @Internal?
 *
 * `@Internal` tells Gradle the property doesn't affect task outputs — it says
 * nothing about service lifecycle. Gradle can therefore call [PgVectorService.close]
 * at any point, including mid-task, causing "Failed to execute 'init'" JDBC errors.
 *
 * `@ServiceReference` (Gradle 7.4+) is the correct annotation for BuildService
 * properties. It tells Gradle's build service manager:
 *   1. Instantiate the service before the task starts
 *   2. Keep it alive for the FULL duration of the task action
 *   3. Call close() only AFTER all tasks that declare this reference have finished
 *
 * Combined with `usesService()` in the registration block and
 * `maxParallelUsages(1)` on the service spec, this is the only approach
 * that fully prevents premature container shutdown.
 */
@DisableCachingByDefault(because = "jruby")
abstract class RagTask : DefaultTask() {

    @get:ServiceReference
    abstract val pgVectorService: Property<PgVectorService>

    /**
     * Starts the container on first call (idempotent), then returns the live
     * [PgVectorService] instance. Safe to call multiple times in a task action.
     */
    protected fun service(): PgVectorService =
        pgVectorService.get().also { it.start() }
}