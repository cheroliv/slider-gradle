package com.cheroliv.slider

import arrow.integrations.jackson.module.registerArrowModule
import com.cheroliv.slider.FileOperationResult.Success
import com.cheroliv.slider.SliderManager.CONFIG_PATH_KEY
import com.cheroliv.slider.SliderManager.deckFile
import com.cheroliv.slider.SliderManager.pushSlides
import com.cheroliv.slider.Slides.RevealJsSlides
import com.cheroliv.slider.Slides.RevealJsSlides.BUILD_GRADLE_KEY
import com.cheroliv.slider.Slides.RevealJsSlides.CODERAY_CSS_KEY
import com.cheroliv.slider.Slides.RevealJsSlides.ENDPOINT_URL_KEY
import com.cheroliv.slider.Slides.RevealJsSlides.GROUP_TASK_SLIDER
import com.cheroliv.slider.Slides.RevealJsSlides.SOURCE_HIGHLIGHTER_KEY
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_ASCIIDOCTOR_REVEALJS
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_CLEAN_SLIDES_BUILD
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_DASHBOARD_SLIDES_BUILD
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_PUBLISH_SLIDES
import com.cheroliv.slider.Slides.RevealJsSlides.TASK_SERVE_SLIDES
import com.cheroliv.slider.Slides.Serve.SERVE_DEP
import com.cheroliv.slider.Slides.Slide.DEFAULT_SLIDES_FOLDER
import com.cheroliv.slider.Slides.Slide.IMAGES
import com.cheroliv.slider.Slides.Slide.SLIDES_FOLDER
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.gradle.node.npm.task.NpxTask
import org.asciidoctor.gradle.jvm.AbstractAsciidoctorTask.OUT_OF_PROCESS
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.asciidoctor.gradle.jvm.slides.AsciidoctorJRevealJSTask
import org.asciidoctor.gradle.jvm.slides.RevealJSExtension
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.util.*
import javax.inject.Inject

/**
 * DSL extension for the slider plugin.
 *
 * Usage in build.gradle.kts:
 * ```
 * slider {
 *     configPath = file("slides-context.yml").absolutePath
 * }
 * ```
 */
open class SliderExtension @Inject constructor(objects: ObjectFactory) {
    val configPath: Property<String> = objects.property(String::class.java)
}

object SlidesManager {
    const val CVS_ORIGIN: String = "origin"
    const val CVS_REMOTE: String = "remote"
    const val CONFIG_PATH_KEY = "managed_config_path"
    val sep: String get() = FileSystems.getDefault().separator

    val Project.yamlMapper: ObjectMapper
        get() = YAMLFactory()
            .let(::ObjectMapper)
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .registerKotlinModule()
            .registerArrowModule()

    fun Project.readSlidesConfigurationFile(
        configPath: () -> String
    ): SlidesConfiguration = try {
        configPath()
            .run(::File)
            .run(yamlMapper::readValue)
    } catch (e: Exception) {
        // Handle exception or log error
        SlidesConfiguration(
            "",
            GitPushConfiguration(
                "",
                "",
                RepositoryConfiguration(
                    "",
                    "",
                    RepositoryCredentials(
                        "",
                        ""
                    )
                ),
                "",
                ""
            )
        )
    }


    val Project.localConf: SlidesConfiguration
        get() = readSlidesConfigurationFile { "$rootDir$sep${properties[CONFIG_PATH_KEY]}" }

    val Project.slideSrcPath: String get() = "${layout.buildDirectory.get().asFile.absolutePath}/${localConf.srcPath}/"
    val Project.slideDestDirPath: String get() = localConf.pushSlides?.to!!


    fun Project.deckFile(key: String): String = buildString {
        append("build/docs/asciidocRevealJs/")
        append(Properties().apply {
            // TODO changer par une reference au path de office a integrer dans le model de données
            buildString {
                append(System.getProperty("user.home"))
                append(sep)
                append("workspace")
                append(sep)
                append("office")
                append(sep)
                append("slides")
                append(sep)
                append("misc")
                append(sep)
                append("deck.properties")
            }.let(::File)
                .inputStream()
                .use(::load)
        }[key].toString())
    }

    fun Project.pushSlides(
        slidesDirPath: () -> String,
        pathTo: () -> String
    ) = pathTo()
        .run(::createRepoDir)
        .let { it: File ->
            copySlideFilesToRepo(slidesDirPath(), it)
                .takeIf { it is Success }
                ?.run {
                    initAddCommitToSlides(it, localConf)
                    pushSlide(
                        it,
                        "${project.rootDir}${sep}${project.properties[CONFIG_PATH_KEY]}"
                            .run(::File)
                            .readText()
                            .trimIndent()
                            .run(YAMLMapper()::readValue)
                    )
                    it.deleteRecursively()
                    slidesDirPath()
                        .let(::File)
                        .deleteRecursively()
                }
        }

    @Throws(IOException::class)
    fun Project.pushSlide(
        repoDir: File,
        conf: SlidesConfiguration,
    ): MutableIterable<PushResult>? = FileRepositoryBuilder()
        .setInitialBranch("main")
        .setGitDir("${repoDir.absolutePath}$sep.git".let(::File))
        .readEnvironment()
        .findGitDir()
        .setMustExist(true)
        .build()
        .apply {
            config.apply {
                getString(
                    CVS_REMOTE,
                    CVS_ORIGIN,
                    conf.pushSlides?.repo?.repository
                )
            }.save()
            if (isBare) throw IOException("Repo dir should not be bare")
        }.let(::Git)
        .run {
            // push to remote:
            return push().setCredentialsProvider(
                UsernamePasswordCredentialsProvider(
                    conf.pushSlides?.repo?.credentials?.username,
                    conf.pushSlides?.repo?.credentials?.password
                )
            ).apply {
                //you can add more settings here if needed
                remote = CVS_ORIGIN
                isForce = true

            }.call()
        }

    fun Project.initAddCommitToSlides(
        repoDir: File,
        conf: SlidesConfiguration,
    ): RevCommit {
        //3) initialiser un repo dans le dossier cvs
        Git.init().setInitialBranch(conf.pushSlides?.branch)
            .setDirectory(repoDir).call().run {
                assert(!repository.isBare)
                assert(repository.directory.isDirectory)
                // add remote repo:
                remoteAdd().apply {
                    setName(CVS_ORIGIN)
                    setUri(URIish(conf.pushSlides?.repo?.repository))
                    // you can add more settings here if needed

                }.call()
                //4) ajouter les fichiers du dossier cvs à l'index
                add().addFilepattern(".").call()

                //5) commit
                return commit().setMessage(conf.pushSlides?.message).call()
            }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun copySlideFilesToRepo(
        slidesDirPath: String,
        repoDir: File
    ): FileOperationResult = try {
        slidesDirPath
            .let(::File)
            .apply {
                when {
                    !copyRecursively(
                        repoDir,
                        true
                    ) -> throw Exception("Unable to copy slides directory to build directory")
                }
            }.deleteRecursively()
        Success
    } catch (e: Exception) {
        FileOperationResult.Failure(e.message ?: "An error occurred during file copy.")
    }

    fun createRepoDir(path: String): File = path
        .let(::File)
        .apply {
            when {
                exists() && !isDirectory -> when {
                    !delete() -> throw Exception("Cant delete file named like repo dir")
                }
            }
            when {
                exists() -> when {
                    !deleteRecursively() -> throw Exception("Cant delete current repo dir")
                }
            }
            when {
                exists() -> throw Exception("Repo dir should not already exists")
                !exists() -> when {
                    !mkdir() -> throw Exception("Cant create repo dir")
                }
            }
        }

}

data class SlidesConfiguration(
    val srcPath: String? = null,
    val pushSlides: GitPushConfiguration? = null,
)

object RepositoryInfo {
    const val ORIGIN = "origin"
    const val CNAME = "CNAME"
    const val REMOTE = "remote"
}

@JvmRecord
data class RepositoryConfiguration(
    val name: String,
    val repository: String,
    val credentials: RepositoryCredentials,
)

@JvmRecord
data class RepositoryCredentials(
    val username: String,
    val password: String
)

@JvmRecord
data class GitPushConfiguration(
    val from: String,
    val to: String,
    val repo: RepositoryConfiguration,
    val branch: String,
    val message: String,
)

sealed class GitOperationResult {
    data class Success(
        val commit: RevCommit,
        val pushResults: MutableIterable<PushResult>?
    ) : GitOperationResult()

    data class Failure(val error: String) : GitOperationResult()
}

sealed class FileOperationResult {
    object Success : FileOperationResult()
    data class Failure(val error: String) : FileOperationResult()
}

sealed class WorkspaceError {
    object FileNotFound : WorkspaceError()
    data class ParsingError(val message: String) : WorkspaceError()
}

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

    const val CVS_ORIGIN: String = "origin"
    const val CVS_REMOTE: String = "remote"
    const val CONFIG_PATH_KEY = "managed_config_path"
    val sep: String get() = FileSystems.getDefault().separator

    val Project.yamlMapper: ObjectMapper
        get() = YAMLFactory()
            .let(::ObjectMapper)
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .registerKotlinModule()
            .registerArrowModule()

    fun Project.readSlidesConfigurationFile(
        configPath: () -> String
    ): SlidesConfiguration = try {
        configPath()
            .run(::File)
            .run(yamlMapper::readValue)
    } catch (e: Exception) {
        // Handle exception or log error
        SlidesConfiguration(
            srcPath = "",
            pushSlides = GitPushConfiguration(
                from = "",
                to = "",
                repo = RepositoryConfiguration(
                    name = "",
                    repository = "",
                    credentials = RepositoryCredentials(
                        username = "",
                        password = ""
                    )
                ),
                branch = "",
                message = ""
            )
        )
    }


    val Project.localConf: SlidesConfiguration
        get() = readSlidesConfigurationFile { "$rootDir$sep${properties[CONFIG_PATH_KEY]}" }

    val Project.slideSrcPath: String get() = "${layout.buildDirectory.get().asFile.absolutePath}/${localConf.srcPath}/"
    val Project.slideDestDirPath: String get() = localConf.pushSlides?.to!!

    fun Project.pushSlides(
        slidesDirPath: () -> String,
        pathTo: () -> String
    ) = pathTo()
        .run(::createRepoDir)
        .let { it: File ->
            copySlideFilesToRepo(slidesDirPath(), it)
                .takeIf { it is Success }
                ?.run {
                    initAddCommitToSlides(it, localConf)
                    pushSlide(
                        it,
                        "${project.rootDir}${sep}${project.properties[CONFIG_PATH_KEY]}"
                            .run(::File)
                            .readText()
                            .trimIndent()
                            .run(YAMLMapper()::readValue)
                    )
                    it.deleteRecursively()
                    slidesDirPath()
                        .let(::File)
                        .deleteRecursively()
                }
        }

    @Throws(IOException::class)
    fun Project.pushSlide(
        repoDir: File,
        conf: SlidesConfiguration,
    ): MutableIterable<PushResult>? = FileRepositoryBuilder()
        .setInitialBranch("main")
        .setGitDir("${repoDir.absolutePath}$sep.git".let(::File))
        .readEnvironment()
        .findGitDir()
        .setMustExist(true)
        .build()
        .apply {
            config.apply {
                getString(
                    CVS_REMOTE,
                    CVS_ORIGIN,
                    conf.pushSlides?.repo?.repository
                )
            }.save()
            if (isBare) throw IOException("Repo dir should not be bare")
        }.let(::Git)
        .run {
            // push to remote:
            return push().setCredentialsProvider(
                UsernamePasswordCredentialsProvider(
                    conf.pushSlides?.repo?.credentials?.username,
                    conf.pushSlides?.repo?.credentials?.password
                )
            ).apply {
                //you can add more settings here if needed
                remote = CVS_ORIGIN
                isForce = true

            }.call()
        }

    fun Project.initAddCommitToSlides(
        repoDir: File,
        conf: SlidesConfiguration,
    ): RevCommit {
        //3) initialiser un repo dans le dossier cvs
        Git.init().setInitialBranch(conf.pushSlides?.branch)
            .setDirectory(repoDir).call().run {
                assert(!repository.isBare)
                assert(repository.directory.isDirectory)
                // add remote repo:
                remoteAdd().apply {
                    setName(CVS_ORIGIN)
                    setUri(URIish(conf.pushSlides?.repo?.repository))
                    // you can add more settings here if needed

                }.call()
                //4) ajouter les fichiers du dossier cvs à l'index
                add().addFilepattern(".").call()

                //5) commit
                return commit().setMessage(conf.pushSlides?.message).call()
            }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun copySlideFilesToRepo(
        slidesDirPath: String,
        repoDir: File
    ): FileOperationResult = try {
        slidesDirPath
            .let(::File)
            .apply {
                when {
                    !copyRecursively(
                        repoDir,
                        true
                    ) -> throw Exception("Unable to copy slides directory to build directory")
                }
            }.deleteRecursively()
        Success
    } catch (e: Exception) {
        FileOperationResult.Failure(e.message ?: "An error occurred during file copy.")
    }

    fun createRepoDir(path: String): File = path
        .let(::File)
        .apply {
            when {
                exists() && !isDirectory -> when {
                    !delete() -> throw Exception("Cant delete file named like repo dir")
                }
            }
            when {
                exists() -> when {
                    !deleteRecursively() -> throw Exception("Cant delete current repo dir")
                }
            }
            when {
                exists() -> throw Exception("Repo dir should not already exists")
                !exists() -> when {
                    !mkdir() -> throw Exception("Cant create repo dir")
                }
            }
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
    @Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
    override fun apply(project: Project) {
        project.repositories.maven { repo ->
            repo.url = project.uri("https://plugins.gradle.org/m2/")
        }
        project.repositories.maven { repo ->
            repo.url = project.uri("https://repo.gradle.org/gradle/libs-releases/")
            repo.content { c -> c.excludeGroup("rubygems") }
        }

        project.repositories.maven { repo ->
            repo.url = project.uri("https://repo.gradle.org/ui/native/jcenter-backup/")
            repo.content { c -> c.includeGroup("rubygems") }
        }
        project.repositories.maven { repo ->
            repo.url = project.uri("https://maven.xillio.com/artifactory/libs-release/")
            repo.content { c -> c.includeGroup("rubygems") }
        }
        project.repositories.mavenCentral { repo ->
            repo.content { c -> c.excludeGroup("rubygems") }
        }
        project.repositories.ivy { repo ->
            repo.url = project.uri("https://rubygems.org/gems/")
            repo.patternLayout { layout -> layout.artifact("[module]-[revision].gem") }
            repo.metadataSources { s -> s.artifact() }
            repo.content { c -> c.includeGroup("rubygems") }
        }

        project.plugins.apply("com.github.node-gradle.node")
        project.plugins.apply("org.asciidoctor.jvm.gems.classic")
        project.plugins.apply("org.asciidoctor.jvm.revealjs.classic")

        project.dependencies.add(
            "asciidoctorGems",
            "rubygems:asciidoctor-revealjs:3.1.0@gem"
        )

        val sliderExtension = project.extensions.create(
            GROUP_TASK_SLIDER,
            SliderExtension::class.java
        )


        project.tasks.getByName<AsciidoctorJRevealJSTask>(TASK_ASCIIDOCTOR_REVEALJS) {
            group = GROUP_TASK_SLIDER
            description = "Slider settings and generation"
            setExecutionMode(OUT_OF_PROCESS)
            dependsOn(TASK_CLEAN_SLIDES_BUILD)
            finalizedBy(TASK_DASHBOARD_SLIDES_BUILD)
            revealjsOptions {
                project.layout.projectDirectory.asFile
                    .resolve(SLIDES_FOLDER)
                    .resolve(DEFAULT_SLIDES_FOLDER)
                    .apply { println("Slide source absolute path: $absolutePath") }
                    .let(::setSourceDir)
                baseDirFollowsSourceFile()
                resources { spec ->
                    spec.from(sourceDir.resolve(IMAGES)) { copy ->
                        copy.include("**")
                        copy.into(IMAGES)
                    }
                }
                attributes(mapOf(
                    BUILD_GRADLE_KEY to project.layout.projectDirectory.asFile.resolve("build.gradle.kts"),
                    ENDPOINT_URL_KEY to "https://github.com/pages-content/slides/",
                    SOURCE_HIGHLIGHTER_KEY to "coderay",
                    CODERAY_CSS_KEY to "style",
                    RevealJsSlides.IMAGEDIR_KEY to ".${SliderManager.sep}images",
                    RevealJsSlides.TOC_KEY to "left",
                    RevealJsSlides.ICONS_KEY to "font",
                    RevealJsSlides.SETANCHORS_KEY to "",
                    RevealJsSlides.IDPREFIX_KEY to "slide-",
                    RevealJsSlides.IDSEPARATOR_KEY to "-",
                    RevealJsSlides.DOCINFO_KEY to "shared",
                    RevealJsSlides.REVEALJS_THEME_KEY to "black",
                    RevealJsSlides.REVEALJS_TRANSITION_KEY to "linear",
                    RevealJsSlides.REVEALJS_HISTORY_KEY to "true",
                    RevealJsSlides.REVEALJS_SLIDENUMBER_KEY to "true"
                ))
            }
        }

        project.tasks.register<AsciidoctorTask>("asciidoctor") {
            group = GROUP_TASK_SLIDER
//            dependsOn(project.tasks.findByName(TASK_ASCIIDOCTOR_REVEALJS))
            dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
        }


        project.extensions.getByType(RevealJSExtension::class.java).apply {
            version = "3.1.0"
            templateGitHub { gh ->
                gh.setOrganisation("hakimel")
                gh.setRepository("reveal.js")
                gh.setTag("3.9.1")
            }
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

        project.tasks.register<Exec>("asciidocCapsule") {
            group = "capsule"
            dependsOn("asciidoctor")
            commandLine("chromium", project.deckFile("asciidoc.capsule.deck.file"))
            workingDir = project.layout.projectDirectory.asFile
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
                            ?.filter { file -> file.isFile && file.name.endsWith(".html") }
                            ?.forEach { file -> file.delete() }
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

        project.tasks.register<DefaultTask>(TASK_DASHBOARD_SLIDES_BUILD) {
            group = "documentation"
            description = "Génère un index.html listant toutes les présentations Reveal.js"

            doLast {
                //TODO: passer cette adresse a la configuration du slide pour indiquer sa source
                val slidesDir = project.layout.projectDirectory.asFile
                    .resolve("slides")
                    .resolve("misc")
                    .apply {
                        listFiles().find { file -> file.name == "index.html" }!!
                            .readText().trimIndent()
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
                    .find { file -> file.name == "index.html" }!!
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
            doFirst { task -> "Task description :\n\t${task.description}".run(project.logger::info) }
            doLast {
                val localConf: SlidesConfiguration = project.properties[CONFIG_PATH_KEY].toString()
                    .run(project.layout.projectDirectory.asFile::resolve)
                    .readText().trimIndent()
                    .run(YAMLMapper()::readValue)

                val repoDir = project.layout.buildDirectory.get().asFile.resolve(localConf.pushSlides!!.to)

                project.pushSlides(slidesDirPath = {
                    project.layout.buildDirectory.get().asFile
                        .resolve(localConf.srcPath!!)
                        .absolutePath
                }, pathTo = { repoDir.absolutePath })
            }
        }

    }
}