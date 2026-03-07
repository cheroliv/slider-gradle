package com.cheroliv.slider

import com.cheroliv.slider.SliderManager.SLIDER_GROUP
import org.gradle.api.Plugin
import org.gradle.api.Project

object SliderManager {
    const val SLIDER_GROUP = "slider"

}

class SliderPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sliderExtension = project.extensions.create(
            SLIDER_GROUP,
            SliderExtension::class.java
        )
    }
}