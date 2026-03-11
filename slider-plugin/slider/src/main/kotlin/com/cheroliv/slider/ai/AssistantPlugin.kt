package com.cheroliv.slider.ai

import com.cheroliv.slider.ai.AssistantManager.createChatTasks
import org.gradle.api.Plugin
import org.gradle.api.Project

class AssistantPlugin : Plugin<Project> {

    override fun apply(project: Project) =
        // Creating tasks for each model
        project.createChatTasks()
}
