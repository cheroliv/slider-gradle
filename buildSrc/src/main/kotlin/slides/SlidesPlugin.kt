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
import slides.Slides.RevealJsSlides.GROUP_TASK_SLIDER
import slides.Slides.RevealJsSlides.TASK_ASCIIDOCTOR_REVEALJS
import slides.Slides.RevealJsSlides.TASK_DASHBOARD_SLIDES_BUILD
import slides.Slides.RevealJsSlides.TASK_PUBLISH_SLIDES
import slides.Slides.RevealJsSlides.TASK_SERVE_SLIDES
import slides.Slides.Serve.SERVE_DEP
import slides.SlidesManager.CONFIG_PATH_KEY
import slides.SlidesManager.deckFile
import slides.SlidesManager.pushSlides
import java.io.File

class SlidesPlugin : Plugin<Project> {
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
                project.layout.buildDirectory.get().asFile
                    .resolve("docs")
                    .resolve("asciidocRevealJs")
                    .run {
                        resolve("slides.json").run { if (exists()) delete() }
                        resolve("images").deleteRecursively()
                        listFiles()
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
                    .resolve("docs")
                    .resolve("asciidocRevealJs")
                    .also { "output dir path: $it".run(project.logger::info) }

                val indexFile: File = slidesDir.resolve("index.html").apply {
                    readText().trimIndent()
                        .run { "index.html:\n$this" }
                        .run(project.logger::info)
                }

                val slidesJsonFile = outputDir.resolve("slides.json")

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
                buildString {
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
                }.run(slidesJsonFile::writeText)

                // Générer le fichier index.html
                slidesDir.listFiles()
                    .find { it.name == "index.html" }!!
                    .copyTo(outputDir.resolve("index.html"), true)

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
                val localConf: SlidesConfiguration = project.properties[CONFIG_PATH_KEY].toString()
                    .run(project.layout.projectDirectory.asFile::resolve)
                    .readText().trimIndent()
                    .run(YAMLMapper()::readValue)

                val repoDir = project.layout.buildDirectory.get().asFile.resolve(localConf.pushSlides!!.to)

                project.pushSlides({
                    project.layout.buildDirectory.get().asFile
                        .resolve(localConf.srcPath!!)
                        .absolutePath
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
            commandLine(
                "npx",
                SERVE_DEP,
                project.layout.buildDirectory.asFile.get()
                    .resolve("docs")
                    .resolve("asciidocRevealJs")
                    .absolutePath
            )
            workingDir = project.layout.projectDirectory.asFile
        }

        project.tasks.register<NpxTask>(TASK_SERVE_SLIDES) {
            group = GROUP_TASK_SLIDER
            description = "Serve slides using the serve package executed via npx"
            dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
            command.set(SERVE_DEP)
            project.layout.buildDirectory.get().asFile
                .resolve("docs")
                .resolve("asciidocRevealJs")
                .absolutePath
                .run(::listOf)
                .run(args::set)
            workingDir.set(project.layout.projectDirectory.asFile)
            doFirst { println("Serve slides using the serve package executed via npx") }
        }

        project.tasks.register<Exec>("reportTests") {
            group = "verification"
            description = "Check slider project then show report in firefox"
            dependsOn("check")
            commandLine(
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

        project.tasks.register<Exec>("reportFunctionalTests") {
            group = "verification"
            description = "Check slider project then show report in firefox"
            dependsOn("check")
            commandLine(
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