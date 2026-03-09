package com.cheroliv.slider.translate

import com.cheroliv.slider.translate.TranslatorManager.createDisplaySupportedLanguagesTask
import com.cheroliv.slider.translate.TranslatorManager.createTranslationTasks
import org.gradle.api.Plugin
import org.gradle.api.Project

class TranslatorPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        createDisplaySupportedLanguagesTask()
        createTranslationTasks()
    }
}