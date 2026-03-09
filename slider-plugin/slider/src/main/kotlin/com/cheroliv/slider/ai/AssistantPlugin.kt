package com.cheroliv.slider.ai

import com.cheroliv.slider.ai.AssistantManager.createChatTasks
import org.gradle.api.Plugin
import org.gradle.api.Project

class AssistantPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register("displayE3POPrompt") {
            it.group = "school-ai"
            it.description = "Dislpay on console AI prompt assistant"
            it.doFirst { AssistantManager.PromptManager.userMessageFr.let(::println) }
        }
        // Creating tasks for each model
        project.createChatTasks()
    }
}
