package ai

import ai.AssistantManager.createChatTasks
import org.gradle.api.Plugin
import org.gradle.api.Project

class AssistantPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register("displayE3POPrompt") {
            group = "school-ai"
            description = "Dislpay on console AI prompt assistant"
            doFirst { AssistantManager.PromptManager.userMessageFr.let(::println) }
        }
        // Creating tasks for each model
        project.createChatTasks()
    }
}
