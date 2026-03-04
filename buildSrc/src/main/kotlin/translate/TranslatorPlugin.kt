package translate

import org.gradle.api.Plugin
import org.gradle.api.Project
import translate.TranslatorManager.createDisplaySupportedLanguagesTask
import translate.TranslatorManager.createTranslationTasks

class TranslatorPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        createDisplaySupportedLanguagesTask()
        createTranslationTasks()
    }
}