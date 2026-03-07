package com.cheroliv.slider

import com.cheroliv.slider.SliderManager.deckFile
import com.cheroliv.slider.Slides.RevealJsSlides.GROUP_TASK_SLIDER
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_ASCIIDOCTOR_REVEALJS
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_SERVE_SLIDES
import com.cheroliv.slider.Slides.Serve.SERVE_DEP
import com.github.gradle.node.npm.task.NpxTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import java.util.*

object SliderManager {
    fun Project.deckFile(key: String): String = buildString {
        append("build/docs/asciidocRevealJs/")
        append(Properties().apply {
            // TODO changer par une reference au path de sliderConfig a integrer dans le model de données
            layout.projectDirectory.asFile
                .resolve("slides")
                .resolve("misc")
                .resolve("deck.properties")
                .inputStream()
                .use(::load)
        }[key].toString())
    }

}

object Slides {
    object RevealJsSlides {
        const val GROUP_TASK_SLIDER = "slider"
        const val TASK_ASCIIDOCTOR_REVEALJS = "asciidoctorRevealJs"
        const val TASK_CLEAN_SLIDES_BUILD = "cleanSlidesBuild"
        const val TASK_DASHBOARD_SLIDES_BUILD = "dashSlidesBuild"
        const val TASK_PUBLISH_SLIDES = "publishSlides"
        const val BUILD_GRADLE_KEY = "build-gradle"
        const val ENDPOINT_URL_KEY = "endpoint-url"
        const val SOURCE_HIGHLIGHTER_KEY = "source-highlighter"
        const val CODERAY_CSS_KEY = "coderay-css"
        const val IMAGEDIR_KEY = "imagesdir"
        const val TOC_KEY = "toc"
        const val ICONS_KEY = "icons"
        const val SETANCHORS_KEY = "setanchors"
        const val IDPREFIX_KEY = "idprefix"
        const val IDSEPARATOR_KEY = "idseparator"
        const val DOCINFO_KEY = "docinfo"
        const val REVEALJS_THEME_KEY = "revealjs_theme"
        const val REVEALJS_TRANSITION_KEY = "revealjs_transition"
        const val REVEALJS_HISTORY_KEY = "revealjs_history"
        const val REVEALJS_SLIDENUMBER_KEY = "revealjs_slideNumber"
        const val TASK_SERVE_SLIDES = "serveSlides"
    }

    object Serve {
        const val PACKAGE_NAME = "serve"
        const val VERSION = "14.2.4"
        const val SERVE_DEP = "$PACKAGE_NAME@$VERSION"
    }

    object Slide {
        const val SLIDES_FOLDER = "slides"
        const val IMAGES = "images"
        const val DEFAULT_SLIDES_FOLDER = "misc"
        //TODO: construct path from config file in yaml format
    }
}

class SliderPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("com.github.node-gradle.node")
        val sliderExtension = project.extensions.create(
            GROUP_TASK_SLIDER,
            SliderExtension::class.java
        )
        project.tasks.register(
            TASK_SERVE_SLIDES,
            NpxTask::class.java
        ) {
            it.group = GROUP_TASK_SLIDER
            it.description = "Serve slides using the serve package executed via npx"
            it.dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
            it.command.set(SERVE_DEP)
            project.layout.buildDirectory.get().asFile
                .resolve("docs")
                .resolve("asciidocRevealJs")
                .absolutePath
                .run(::listOf)
                .run(it.args::set)
            it.workingDir.set(project.layout.projectDirectory.asFile)
            it.doFirst { println("Serve slides using the serve package executed via npx") }
        }
        project.tasks.register("asciidocCapsule", Exec::class.java) {
            it.group = "capsule"
            it.dependsOn("asciidoctor")
            it.commandLine("chromium", project.deckFile("asciidoc.capsule.deck.file"))
            it.workingDir = project.layout.projectDirectory.asFile
        }

        project.tasks.register("reportTests", Exec::class.java) {
            it.group = "verification"
            it.description = "Check slider project then show report in firefox"
            it.dependsOn("check")
            it.commandLine(
                "firefox",
                "--new-tab",
                project.layout.buildDirectory.asFile.get()
                    .resolve("reports")
                    .resolve("tests")
                    .resolve("test")
                    .resolve("index.html")
                    .absolutePath,
            )
        }

        project.tasks.register("reportFunctionalTests", Exec::class.java) {
            it.group = "verification"
            it.description = "Check slider project then show report in firefox"
            it.dependsOn("check")
            it.commandLine(
                "firefox",
                "--new-tab",
                project.layout.buildDirectory.get().asFile
                    .resolve("reports")
                    .resolve("tests")
                    .resolve("functionalTest")
                    .resolve("index.html")
                    .absolutePath,
            )
        }

    }
}