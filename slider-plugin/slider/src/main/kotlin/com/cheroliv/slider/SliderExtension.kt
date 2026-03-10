package com.cheroliv.slider

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * DSL extension for the slider plugin.
 *
 * Usage in build.gradle.kts:
 * ```
 * slider {
 *     configPath = file("slides-context.yml").absolutePath
 * }
 * ```
 */
open class SliderExtension @Inject constructor(objects: ObjectFactory) {
    val configPath: Property<String> = objects.property(String::class.java)
}