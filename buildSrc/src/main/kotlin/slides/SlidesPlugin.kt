package slides

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.gradle.node.npm.task.NpxTask
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import slides.SlidesManager.CONFIG_PATH_KEY
import slides.SlidesManager.deckFile
import slides.SlidesManager.pushSlides
import slides.SlidesPlugin.RevealJsSlides.GROUP_TASK_SLIDER
import slides.SlidesPlugin.RevealJsSlides.TASK_ASCIIDOCTOR_REVEALJS
import slides.SlidesPlugin.RevealJsSlides.TASK_DASHBOARD_SLIDES_BUILD
import slides.SlidesPlugin.RevealJsSlides.TASK_PUBLISH_SLIDES
import slides.SlidesPlugin.RevealJsSlides.TASK_SERVE_SLIDES
import slides.SlidesPlugin.Serve.SERVE_DEP
import workspace.WorkspaceUtils.sep
import java.io.File

class SlidesPlugin : Plugin<Project> {
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

    override fun apply(project: Project) {
        project.plugins.apply("com.github.node-gradle.node")
        project.plugins.apply("org.asciidoctor.jvm.revealjs")

        project.repositories {
            mavenCentral()
            gradlePluginPortal()
        }


        project.tasks.register<AsciidoctorTask>("asciidoctor") {
            group = GROUP_TASK_SLIDER
            dependsOn(project.tasks.findByPath("asciidoctorRevealJs"))
        }

        project.tasks.register<DefaultTask>("cleanSlidesBuild") {
            group = GROUP_TASK_SLIDER
            description = "Delete generated presentation in build directory."
            doFirst {
                "${project.layout.buildDirectory}/docs/asciidocRevealJs".run {
                    "$this/images"
                        .let(::File)
                        .deleteRecursively()
                    let(::File)
                        .listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".html") }
                        ?.forEach { it.delete() }
                }
            }
        }

        project.tasks.register<Exec>("openFirefox") {
            group = GROUP_TASK_SLIDER
            description = "Open the presentation dashboard in firefox"
            dependsOn("asciidoctor")
            commandLine("firefox", project.deckFile("default.deck.file"))
            workingDir = project.layout.projectDirectory.asFile
        }

        project.tasks.register<Exec>("openChromium") {
            group = GROUP_TASK_SLIDER
            description = "Open the default.deck.file presentation in chromium"
            dependsOn("asciidoctor")
            commandLine("chromium", project.deckFile("default.deck.file"))
            workingDir = project.layout.projectDirectory.asFile
        }

        project.tasks.register(TASK_DASHBOARD_SLIDES_BUILD) {
            group = "documentation"
            description = "Génère un index.html listant toutes les présentations Reveal.js"

            doLast {
                //TODO: passer cette adresse a la configuration du slide pour indiquer sa source
                val slidesDir = listOf(
                    System.getProperty("user.home"),
                    "workspace", "office", "slides", "misc"
                ).reduce { acc, part -> File(acc, part).path }
                    .let(::File)
                    .apply {
                        listFiles().find {
                            it.name == "index.html"
                        }!!.readText().trimIndent()
                            .run { "index.html:\n$this" }
                            .run(project.logger::info)
                    }

                val outputDir = project.layout.buildDirectory.get().asFile
                    .run { "$this/docs/asciidocRevealJs" }
                    .run(::File)
                    .apply {
                        "output dir path: $this"
                            .run(project.logger::info)
                    }

                val indexFile: File = "$slidesDir/index.html"
                    .run(::File)
                    .apply {
                        readText().trimIndent()
                            .run { "index.html:\n$this" }
                            .run(project.logger::info)
                    }

                val slidesJsonFile = File("$outputDir/slides.json")


                // Créer le dossier de sortie s'il n'existe pas
                outputDir.mkdirs()

                // Scanner les fichiers .adoc dans le dossier slides
                val adocFiles = slidesDir.listFiles { file ->
                    file.isFile && file.extension == "adoc"
                }?.map { file ->
                    mapOf(
                        "name" to file.nameWithoutExtension,
                        "filename" to "${file.nameWithoutExtension}.html"
                    )
                }.apply { println(this) } ?: emptyList()

                // Générer le fichier slides.json
                val jsonContent = buildString {
                    appendLine("[")
                    adocFiles.forEachIndexed { index, slide ->
                        append("  {")
                        append("\"name\": \"${slide["name"]}\", ")
                        append("\"filename\": \"${slide["filename"]}\"")
                        append("}")
                        if (index < adocFiles.size - 1) append(",")
                        appendLine()
                    }
                    appendLine("]")
                }

                slidesJsonFile.writeText(jsonContent)

                // Générer le fichier index.html
                slidesDir.listFiles()
                    .find { it.name == "index.html" }!!
                    .copyTo(File("${outputDir}/index.html"), true)

                println("✅ Dashboard généré avec succès !")
                println("📁 Fichiers générés :")
                println("   - ${indexFile.absolutePath}")
                println("   - ${slidesJsonFile.absolutePath}")
                println("📊 ${adocFiles.size} présentation(s) trouvée(s)")
            }
        }

        project.tasks.register<DefaultTask>(TASK_PUBLISH_SLIDES) {
            group = GROUP_TASK_SLIDER
            description = "Deploy sliders to remote repository"
            dependsOn("asciidoctor")
            doFirst { "Task description :\n\t$description".run(project.logger::info) }
            doLast {
                val localConf: SlidesConfiguration =
                    "${project.rootDir}${sep}${project.properties[CONFIG_PATH_KEY]}"
                        .run(::File)
                        .readText()
                        .trimIndent()
                        .run(YAMLMapper()::readValue)

                val repoDir = "${project.layout.buildDirectory.get().asFile}$sep${localConf.pushSlides?.to}"
                    .run(::File)

                project.pushSlides({
                    "${project.layout.buildDirectory.get().asFile}$sep${localConf.srcPath}"
                        .run(::File).absolutePath
                }, { repoDir.absolutePath })
            }
        }

        project.tasks.register<Exec>("asciidocCapsule") {
            group = "capsule"
            dependsOn("asciidoctor")
            commandLine("chromium", project.deckFile("asciidoc.capsule.deck.file"))
            workingDir = project.layout.projectDirectory.asFile
        }


        project.tasks.register<Exec>("execServeSlides") {
            group = GROUP_TASK_SLIDER
            description = "Serve slides using the serve package executed via command line"
            commandLine("npx", SERVE_DEP, "build/docs/asciidocRevealJs/")
            workingDir = project.layout.projectDirectory.asFile
        }

        project.tasks.register<NpxTask>(TASK_SERVE_SLIDES) {
            group = GROUP_TASK_SLIDER
            description = "Serve slides using the serve package executed via npx"
            dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
            command.set(SERVE_DEP)
            args.set(listOf("build/docs/asciidocRevealJs/"))
            workingDir.set(project.layout.projectDirectory.asFile)
            doFirst { println("Serve slides using the serve package executed via npx") }
        }
    }

    object Serve {
        const val PACKAGE_NAME = "serve"
        const val VERSION = "14.2.4"
        const val SERVE_DEP = "$PACKAGE_NAME@$VERSION"
    }

    object Slide {
        const val OFFICE_FOLDER = "office"
        const val SLIDES_FOLDER = "slides"
        const val WORKSPACE_FOLDER = "workspace"
        const val IMAGES = "images"
        const val DEFAULT_SLIDES_FOLDER = "misc"

        //TODO: construct path from config file in yaml format
        val officeDir: String
            get() = "${System.getProperty("user.home")}$sep$WORKSPACE_FOLDER$sep$OFFICE_FOLDER"
        val DEFAULT_SLIDES_FOLDER_PATH = "$officeDir$sep$SLIDES_FOLDER$sep$DEFAULT_SLIDES_FOLDER"
    }

}