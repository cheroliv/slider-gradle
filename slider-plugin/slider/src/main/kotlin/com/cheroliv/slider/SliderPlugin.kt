package com.cheroliv.slider

import com.cheroliv.slider.SliderManager.Dependencies.configureDependencies
import com.cheroliv.slider.SliderManager.Extensions.configureExtensions
import com.cheroliv.slider.SliderManager.Plugins.applyPlugins
import com.cheroliv.slider.SliderManager.Prerequisites.checkJavaVersion
import com.cheroliv.slider.SliderManager.Repositories.configureRepositories
import com.cheroliv.slider.SliderManager.Tasks.registerTasks
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Main entry point for the Slider Gradle plugin.
 *
 * Orchestrates the full plugin setup by delegating each responsibility
 * to a focused nested object inside [SliderManager]:
 * - [SliderManager.Prerequisites] — Java version guard
 * - [SliderManager.Repositories]  — Maven/Ivy repository configuration
 * - [SliderManager.Plugins]       — external plugin application
 * - [SliderManager.Dependencies]  — Ruby gem dependency declaration
 * - [SliderManager.Extensions]    — DSL extension + RevealJS configuration
 * - [SliderManager.Tasks]         — task registration
 *
 * All business logic lives in [SliderManager]. This class is intentionally
 * kept as a thin orchestrator so it remains readable and easy to test.
 */
class SliderPlugin : Plugin<Project> {

    /** Applies the plugin by delegating each setup phase to [SliderManager]. */
    override fun apply(project: Project) {
        with(project) {
            checkJavaVersion()
            configureRepositories()
            applyPlugins()
            configureDependencies()
            configureExtensions()
            registerTasks()
        }
    }
}