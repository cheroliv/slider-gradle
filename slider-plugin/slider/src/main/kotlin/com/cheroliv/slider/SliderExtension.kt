package com.cheroliv.slider

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for the slider plugin.
 *
 * Usage in build.gradle.kts:
 * ```
 * slider {
 *     configPath = file("slides-context.yml").absolutePath
 *
 *     // Optional — defaults to "eclipse-temurin:17.0.18_8-jdk-ubi10-minimal"
 *     // Only used when jdk17 is not installed locally but Docker is available.
 *     java17TemurinDockerImage = "eclipse-temurin:17.0.18_8-jdk-ubi10-minimal"
 *
 *     // Optional — force Docker even if jdk17 is installed locally. Default: false
 *     forceDocker = true
 * }
 * ```
 */
open class SliderExtension @Inject constructor(objects: ObjectFactory) {
    val configPath: Property<String> = objects.property(String::class.java)

    /**
     * Docker image to use for jdk17 when jdk17 is not installed on the host.
     * Ignored if jdk17 is available locally AND [forceDocker] is false.
     * Defaults to [DEFAULT_JDK17_IMAGE].
     */
    val java17TemurinDockerImage: Property<String> = objects
        .property(String::class.java)
        .convention(DEFAULT_JDK17_IMAGE)

    /**
     * When true, skips the local jdk17 probe and goes straight to Docker,
     * even if `jdk17` is available on the system PATH.
     * Defaults to false.
     */
    val forceDocker: Property<Boolean> = objects
        .property(Boolean::class.java)
        .convention(false)

    companion object {
        const val DEFAULT_JDK17_IMAGE = "eclipse-temurin:17.0.18_8-jdk-ubi10-minimal"
    }
}