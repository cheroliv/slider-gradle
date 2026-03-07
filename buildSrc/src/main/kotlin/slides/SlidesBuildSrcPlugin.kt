package slides

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.gradle.node.npm.task.NpxTask
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.asciidoctor.gradle.jvm.slides.AsciidoctorJRevealJSTask
import org.asciidoctor.gradle.jvm.slides.RevealJSExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.support.serviceOf
import slides.Slides.RevealJsSlides.BUILD_GRADLE_KEY
import slides.Slides.RevealJsSlides.CODERAY_CSS_KEY
import slides.Slides.RevealJsSlides.DOCINFO_KEY
import slides.Slides.RevealJsSlides.ENDPOINT_URL_KEY
import slides.Slides.RevealJsSlides.GROUP_TASK_SLIDER
import slides.Slides.RevealJsSlides.ICONS_KEY
import slides.Slides.RevealJsSlides.IDPREFIX_KEY
import slides.Slides.RevealJsSlides.IDSEPARATOR_KEY
import slides.Slides.RevealJsSlides.IMAGEDIR_KEY
import slides.Slides.RevealJsSlides.REVEALJS_HISTORY_KEY
import slides.Slides.RevealJsSlides.REVEALJS_SLIDENUMBER_KEY
import slides.Slides.RevealJsSlides.REVEALJS_THEME_KEY
import slides.Slides.RevealJsSlides.REVEALJS_TRANSITION_KEY
import slides.Slides.RevealJsSlides.SETANCHORS_KEY
import slides.Slides.RevealJsSlides.SOURCE_HIGHLIGHTER_KEY
import slides.Slides.RevealJsSlides.TASK_ASCIIDOCTOR_REVEALJS
import slides.Slides.RevealJsSlides.TASK_CLEAN_SLIDES_BUILD
import slides.Slides.RevealJsSlides.TASK_DASHBOARD_SLIDES_BUILD
import slides.Slides.RevealJsSlides.TASK_PUBLISH_SLIDES
import slides.Slides.RevealJsSlides.TASK_SERVE_SLIDES
import slides.Slides.RevealJsSlides.TOC_KEY
import slides.Slides.Serve.SERVE_DEP
import slides.Slides.Slide.DEFAULT_SLIDES_FOLDER
import slides.Slides.Slide.IMAGES
import slides.Slides.Slide.SLIDES_FOLDER
import slides.SlidesManager.CONFIG_PATH_KEY
import slides.SlidesManager.deckFile
import slides.SlidesManager.pushSlides
import workspace.WorkspaceUtils.sep
import java.io.File

class SlidesBuildSrcPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.plugins.apply("com.github.node-gradle.node")
        project.plugins.apply("org.asciidoctor.jvm.gems")
        project.plugins.apply("org.asciidoctor.jvm.revealjs")

        project.repositories {
            gradlePluginPortal(){ content { excludeGroup("rubygems") } }
            mavenCentral() { content { excludeGroup("rubygems") } }
            ivy {
                url = project.uri("https://rubygems.org/gems/")
                patternLayout { artifact("[module]-[revision].[ext]") }
                metadataSources { artifact() }
                content { includeGroup("rubygems") }
            }
        }

        project.dependencies {
            add("asciidoctorGems", "rubygems:asciidoctor-revealjs:3.1.0")
        }

        project.tasks.getByName<AsciidoctorJRevealJSTask>(TASK_ASCIIDOCTOR_REVEALJS) {
            group = GROUP_TASK_SLIDER
            description = "Slider settings and generation"
            project.repositories.mavenCentral() {
                content { excludeGroup("rubygems") }
            }
            project.repositories.ivy {
                url = project.uri("https://rubygems.org/gems/")
                patternLayout { artifact("[module]-[revision].gem") }
                metadataSources { artifact() }
                content { includeGroup("rubygems") }
            }
            setInProcess("JAVA_EXEC")

            forkOptions {
                executable(
                    project.serviceOf<JavaToolchainService>()
                        .launcherFor {
                            languageVersion.set(JavaLanguageVersion.of(17))
                            vendor.set(JvmVendorSpec.ADOPTIUM)
                        }
                        .get()
                        .executablePath
                        .asFile
                        .absolutePath
                )
            }
            dependsOn(TASK_CLEAN_SLIDES_BUILD)
            finalizedBy(TASK_DASHBOARD_SLIDES_BUILD)
            project.extensions.getByType<RevealJSExtension>().apply {
                version = "3.1.0"
                templateGitHub {
                    setOrganisation("hakimel")
                    setRepository("reveal.js")
                    setTag("3.9.1")
                }
            }
            revealjsOptions {
                project.layout.projectDirectory.asFile
                    .resolve(SLIDES_FOLDER)
                    .resolve(DEFAULT_SLIDES_FOLDER)
                    .apply { println("Slide source absolute path: $absolutePath") }
                    .let(::setSourceDir)
                baseDirFollowsSourceFile()
                resources {
                    from(sourceDir.resolve(IMAGES)) {
                        include("**")
                        into(IMAGES)
                    }
                }
                mapOf(
                    BUILD_GRADLE_KEY to project.layout.projectDirectory.asFile.resolve("build.gradle.kts"),
                    ENDPOINT_URL_KEY to "https://github.com/pages-content/slides/",
                    SOURCE_HIGHLIGHTER_KEY to "coderay",
                    CODERAY_CSS_KEY to "style",
                    IMAGEDIR_KEY to ".${sep}images",
                    TOC_KEY to "left",
                    ICONS_KEY to "font",
                    SETANCHORS_KEY to "",
                    IDPREFIX_KEY to "slide-",
                    IDSEPARATOR_KEY to "-",
                    DOCINFO_KEY to "shared",
                    REVEALJS_THEME_KEY to "black",
                    REVEALJS_TRANSITION_KEY to "linear",
                    REVEALJS_HISTORY_KEY to "true",
                    REVEALJS_SLIDENUMBER_KEY to "true"
                ).let(::attributes)
            }
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

    }
}