package com.cheroliv.slider

import arrow.integrations.jackson.module.registerArrowModule
import com.cheroliv.slider.SliderManager.Git.initAddCommitToSlides
import com.cheroliv.slider.SliderManager.Git.pushSlide
import com.cheroliv.slider.SliderManager.Git.pushSlides
import com.cheroliv.slider.SliderManager.Tasks.registerAsciidoctorRevealJsTask
import com.cheroliv.slider.SliderManager.Tasks.registerCleanSlidesBuildTask
import com.cheroliv.slider.SliderManager.Tasks.registerTasks
import com.cheroliv.slider.SliderManager.deckFile
import com.cheroliv.slider.SliderManager.yamlMapper
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
import com.cheroliv.slider.Slides.Slide.SLIDES_CONTEXT_YML
import com.cheroliv.slider.Slides.Slide.SLIDES_FOLDER
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
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
import org.eclipse.jgit.api.Git.init
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.File.separator
import java.io.IOException
import java.util.*
import java.util.zip.ZipInputStream

/**
 * Root manager object for the Slider plugin.
 *
 * Shared constants and Project-level extension properties live here.
 * All responsibilities are delegated to focused nested objects:
 * - [Prerequisites] — Java version guard
 * - [Repositories]  — Maven/Ivy repository configuration
 * - [Plugins]       — plugin application
 * - [Dependencies]  — gem dependency declaration
 * - [Extensions]    — DSL extension + RevealJS configuration
 * - [Tasks]         — task registration orchestration
 * - [Git]           — Git commit/push operations
 * - [FileOps]       — file copy and repo directory management
 */
object SliderManager {

    const val CVS_ORIGIN: String = "origin"
    const val CVS_REMOTE: String = "remote"
    const val CONFIG_PATH_KEY = "managed_config_path"

    // -------------------------------------------------------------------------
    // Shared Project extensions
    // -------------------------------------------------------------------------

    /** Reads and returns the slides YAML configuration bound to this project. */
    val Project.localConf: SlidesConfiguration
        get() = readSlidesConfigurationFile {
            "$rootDir${File.separator}${properties[CONFIG_PATH_KEY]}"
        }

    /** Absolute path to the generated slides source directory. */
    val Project.slideSrcPath: String
        get() = "${layout.buildDirectory.get().asFile.absolutePath}/${localConf.srcPath}/"

    /** Absolute path to the slides publish destination directory. */
    val Project.slideDestDirPath: String
        get() = localConf.pushSlides?.to!!

    /**
     * Resolves a deck file path from deck.properties for a given key.
     * Prepends the standard asciidocRevealJs output directory.
     *
     * TODO: replace hardcoded path with a reference to sliderConfig model.
     */
    fun Project.deckFile(key: String): String = buildString {
        append("build/docs/asciidocRevealJs/")
        append(
            Properties().apply {
                layout.projectDirectory.asFile
                    .resolve("slides")
                    .resolve("misc")
                    .resolve("deck.properties")
                    .inputStream()
                    .use(::load)
            }[key].toString()
        )
    }

    /** Jackson ObjectMapper configured for YAML, Kotlin, and Arrow support. */
    val Project.yamlMapper: ObjectMapper
        get() = YAMLFactory()
            .let(::ObjectMapper)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerKotlinModule()
            .registerArrowModule()

    /**
     * Reads and deserialises the slides configuration YAML file.
     * Returns an empty [SlidesConfiguration] on any parsing failure
     * to allow the build to continue with degraded behaviour.
     */
    fun Project.readSlidesConfigurationFile(
        configPath: () -> String
    ): SlidesConfiguration = try {
        configPath()
            .run(::File)
            .run(yamlMapper::readValue)
    } catch (_: Exception) {
        SlidesConfiguration(
            srcPath = "",
            pushSlides = GitPushConfiguration(
                from = "", to = "",
                repo = RepositoryConfiguration(
                    name = "", repository = "",
                    credentials = RepositoryCredentials(username = "", password = "")
                ),
                branch = "", message = ""
            )
        )
    }

    // =========================================================================
    // Prerequisites
    // =========================================================================

    /**
     * Guards the build against unsupported Java versions.
     * The plugin requires Java 23+ due to asciidoctor-gradle OUT_OF_PROCESS
     * behaviour and Gradle 9 compatibility requirements.
     */
    object Prerequisites {

        /**
         * Fails fast with a clear message if the current JVM is below Java 23.
         * Called as the very first step in [SliderPlugin.apply].
         */
        internal fun Project.checkJavaVersion() {
            val javaVersion = JavaVersion.current().majorVersion.toInt()
            require(javaVersion >= 23) {
                "com.cheroliv.slider requires Java 23+. Current: Java $javaVersion"
            }
        }
    }


    // =========================================================================
    // Scaffold
    // =========================================================================

    /**
     * Handles first-use initialisation of the consumer project's slides/ directory.
     *
     * The plugin bundles a default slides.zip in its classpath resources
     * (src/main/resources/slides.zip). On first use, if the slides/ directory
     * is absent or incomplete, the zip is extracted into the project directory
     * to provide a ready-to-use slide structure.
     *
     * This follows the scaffolding pattern used by plugins like Quarkus and
     * Spring Initializr — the consumer gets a working default without any
     * manual setup.
     */
    object Scaffold {

        private const val SLIDES_ZIP = "slides.zip"

        /**
         * A complete slides configuration requires all three of the following
         * to be present inside slides/misc/:
         * - deck.properties         — maps *.deck.file keys to HTML filenames
         * - index.html              — dashboard entry point
         * - at least one *-deck.adoc matching a *.deck.file key in deck.properties
         *
         * The deck file convention:
         * - Key pattern  : `*.deck.file`  (e.g. `example.deck.file`)
         * - Value pattern: `*-deck.html`  (e.g. `example-deck.html`)
         * - Source file  : `*-deck.adoc`  (e.g. `example-deck.adoc`)
         */
        private fun isSlidesConfigComplete(miscDir: File): Boolean {
            val deckProperties = miscDir.resolve("deck.properties")
            val indexHtml = miscDir.resolve("index.html")

            if (!deckProperties.exists() || !indexHtml.exists()) return false

            // At least one *.deck.file key must have a matching *-deck.adoc source file
            val hasAtLeastOneDeck = Properties()
                .apply { deckProperties.inputStream().use(::load) }
                .entries
                .filter { (key, _) -> key.toString().endsWith(".deck.file") }
                .any { (_, value) ->
                    value.toString()            // e.g. "example-deck.html"
                        .removeSuffix(".html")  // → "example-deck"
                        .let { miscDir.resolve("$it.adoc").exists() }
                }

            return hasAtLeastOneDeck
        }

        /**
         * Extracts the bundled slides.zip into the consumer project directory
         * if the slides/ directory is absent or its configuration is incomplete.
         *
         * - If slides/ exists and is complete : no-op — consumer content is never overwritten.
         * - If slides/ is absent or incomplete: extracts the bundled zip.
         * - If slides.zip is missing from the classpath: fails with a clear error.
         * - On successful extraction: prints a confirmation message.
         *
         * Must be called before [Tasks.registerTasks] so that the slides/
         * directory is in place when task source directories are resolved.
         */
        internal fun Project.scaffoldSlidesIfAbsent() {
            val slidesDir = layout.projectDirectory.asFile.resolve(SLIDES_FOLDER)
            val miscDir = slidesDir.resolve(DEFAULT_SLIDES_FOLDER)

            // slides/ exists and all required files are present — do nothing
            if (slidesDir.exists() && isSlidesConfigComplete(miscDir)) return

            val zip = SliderPlugin::class.java
                .classLoader
                .getResourceAsStream(SLIDES_ZIP)
                ?: error(
                    "slides.zip not found in plugin classpath. " +
                            "Please report this issue at https://github.com/cheroliv/slider-gradle"
                )

            // Extract all zip entries into the project directory
            zip.use { input ->
                ZipInputStream(input).use { zis ->
                    generateSequence { zis.nextEntry }
                        .filterNot { entry -> entry.isDirectory }
                        .forEach { entry ->
                            val target = layout.projectDirectory.asFile.resolve(entry.name)
                            target.parentFile.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                            zis.closeEntry()
                        }
                }
            }

            println("✅ slides/ directory initialised from plugin defaults.")
            println("📁 Edit slides/${DEFAULT_SLIDES_FOLDER}/*-deck.adoc to get started.")
        }


        /**
         * Generates a default slides-context.yml in the consumer project directory
         * if the file does not already exist.
         *
         * The default configuration is built from a typed [SlidesConfiguration] instance
         * and serialised to YAML via [SliderManager.yamlMapper], ensuring the output is
         * always structurally valid and consistent with the data model.
         *
         * The generated file contains placeholder values that the consumer must replace
         * with their actual Git repository URL, branch, credentials, and commit message.
         */
        internal fun Project.scaffoldSlidesContextIfAbsent() {
            val slidesContext = layout.projectDirectory.asFile.resolve(SLIDES_CONTEXT_YML)

            // slides-context.yml already exists — do nothing
            if (slidesContext.exists()) return

            // Build the default configuration from the typed model
            val default = SlidesConfiguration(
                srcPath = "docs/asciidocRevealJs",
                pushSlides = GitPushConfiguration(
                    from = "build/docs/asciidocRevealJs",
                    to = "build/slides-repo",
                    branch = "main",
                    message = "deploy slides",
                    repo = RepositoryConfiguration(
                        name = "slides",
                        repository = "https://github.com/your-org/your-slides-repo.git",
                        credentials = RepositoryCredentials(
                            username = "your-username",
                            password = "your-token"
                        )
                    )
                )
            )

            // Serialise to YAML using the shared mapper (Kotlin + Arrow modules enabled)
            yamlMapper.writeValue(slidesContext, default)

            println("✅ slides-context.yml generated with default values.")
            println("✏️  Edit slides-context.yml with your actual Git repository configuration.")
        }
    }


// =========================================================================
// Repositories
// =========================================================================

    /**
     * Configures all Maven and Ivy repositories required by the plugin.
     *
     * Repository routing strategy:
     * - rubygems group  → gem-capable mirrors only (xillio, jcenter-backup, rubygems Ivy)
     * - everything else → mavenCentral, plugins.gradle.org, repo.gradle.org
     */
    object Repositories {

        /**
         * Declares the full repository set needed to resolve:
         * - Gradle plugin artifacts (plugins.gradle.org)
         * - grolifant transitive dependency (repo.gradle.org/libs-releases)
         * - Ruby gems via Ivy layout (rubygems.org)
         * - Standard JVM artifacts (mavenCentral)
         *
         * The rubygems group is explicitly included/excluded per repository
         * to prevent resolution conflicts between gem and JVM artifact mirrors.
         */
        internal fun Project.configureRepositories() {
            // Gradle plugin artifacts
            repositories.maven { repo ->
                repo.url = uri("https://plugins.gradle.org/m2/")
            }
            // grolifant — transitive dep of jruby-gradle-core, only available here
            repositories.maven { repo ->
                repo.url = uri("https://repo.gradle.org/gradle/libs-releases/")
                repo.content { c -> c.excludeGroup("rubygems") }
            }
            // rubygems fallback mirror
            repositories.maven { repo ->
                repo.url = uri("https://repo.gradle.org/ui/native/jcenter-backup/")
                repo.content { c -> c.includeGroup("rubygems") }
            }
            // rubygems primary mirror
            repositories.maven { repo ->
                repo.url = uri("https://maven.xillio.com/artifactory/libs-release/")
                repo.content { c -> c.includeGroup("rubygems") }
            }
            // standard JVM artifacts — rubygems excluded to avoid interception
            repositories.mavenCentral { repo ->
                repo.content { c -> c.excludeGroup("rubygems") }
            }
            // actual .gem file resolution via Ivy artifact layout
            repositories.ivy { repo ->
                repo.url = uri("https://rubygems.org/gems/")
                repo.patternLayout { layout -> layout.artifact("[module]-[revision].gem") }
                repo.metadataSources { s -> s.artifact() }
                repo.content { c -> c.includeGroup("rubygems") }
            }
        }
    }

// =========================================================================
// Plugins
// =========================================================================

    /**
     * Applies the external Gradle plugins required for slide generation.
     *
     * The `.classic` suffix is mandatory since asciidoctor-gradle 5.0.0-alpha.1
     * renamed the plugin IDs as part of a breaking API change.
     */
    object Plugins {

        /**
         * Applies:
         * - `com.github.node-gradle.node`        → npx/Node.js for serveSlides
         * - `org.asciidoctor.jvm.gems.classic`   → JRuby gem lifecycle (5.x API)
         * - `org.asciidoctor.jvm.revealjs.classic` → AsciidoctorJRevealJSTask (5.x API)
         */
        internal fun Project.applyPlugins() {
            plugins.apply("com.github.node-gradle.node")
            plugins.apply("org.asciidoctor.jvm.gems.classic")
            plugins.apply("org.asciidoctor.jvm.revealjs.classic")
        }
    }

// =========================================================================
// Dependencies
// =========================================================================

    /**
     * Declares the Ruby gem dependencies required for Reveal.js slide generation.
     */
    object Dependencies {

        /**
         * Adds the asciidoctor-revealjs gem to the asciidoctorGems configuration.
         * The `@gem` classifier is mandatory for Ivy-based gem resolution.
         */
        internal fun Project.configureDependencies() {
            dependencies.add(
                "asciidoctorGems",
                "rubygems:asciidoctor-revealjs:3.1.0@gem"
            )
        }
    }

// =========================================================================
// Extensions
// =========================================================================

    /**
     * Registers and configures Gradle extensions consumed by the plugin and its tasks.
     */
    object Extensions {

        /**
         * Registers the `slider {}` DSL extension for consumer configuration,
         * and pins the RevealJS template to reveal.js 3.9.1 from the hakimel/reveal.js
         * GitHub repository.
         */
        internal fun Project.configureExtensions() {
            // Expose the slider {} DSL block to the consumer
            extensions.create(GROUP_TASK_SLIDER, SliderExtension::class.java)

            // Pin reveal.js version and GitHub template source
            extensions.getByType(RevealJSExtension::class.java).apply {
                version = "3.1.0"
                templateGitHub { gh ->
                    gh.setOrganisation("hakimel")
                    gh.setRepository("reveal.js")
                    gh.setTag("3.9.1")
                }
            }
        }
    }

// =========================================================================
// Tasks
// =========================================================================

    /**
     * Orchestrates the registration of all Slider plugin tasks.
     *
     * Task dependency graph:
     * ```
     * cleanSlidesBuild
     *   └── asciidoctorRevealJs ──┐
     *         └── asciidoctor    │ finalizedBy
     *         └── serveSlides    │
     *                            ▼
     *                    dashSlidesBuild
     * ```
     */
    object Tasks {

        /**
         * Registers all tasks in dependency order.
         * [registerCleanSlidesBuildTask] must come first so the task name
         * is resolvable when [registerAsciidoctorRevealJsTask] calls dependsOn.
         */
        internal fun Project.registerTasks() {
            registerCleanSlidesBuildTask()
            registerAsciidoctorRevealJsTask()
            registerAsciidoctorTask()
            registerServeSlidesTask()
            registerDashboardTask()
            registerPublishSlidesTask()
            registerOpenFirefoxTask()
            registerOpenChromiumTask()
            registerAsciidocCapsuleTask()
            registerReportTestsTask()
            registerReportFunctionalTestsTask()
        }

        /**
         * Deletes previously generated presentation artifacts from the build output:
         * - slides.json
         * - images/ directory
         * - all .html files
         *
         * Always runs before asciidoctorRevealJs to guarantee a clean output.
         */
        private fun Project.registerCleanSlidesBuildTask() {
            tasks.register<DefaultTask>(TASK_CLEAN_SLIDES_BUILD) {
                group = GROUP_TASK_SLIDER
                description = "Delete generated presentation artifacts from the build directory."
                doFirst {
                    layout.buildDirectory.get().asFile
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
        }

        /**
         * Core task — compiles AsciiDoc sources into a Reveal.js HTML presentation.
         *
         * Key decisions:
         * - OUT_OF_PROCESS: JAVA_EXEC was removed in asciidoctor-gradle 5.0.0-alpha.1
         *   due to Gradle closure serialisation changes. OUT_OF_PROCESS is the replacement.
         * - Source dir: resolved from slides/misc/ inside the consumer project.
         * - Images: copied alongside generated HTML under an images/ subdirectory.
         * - Attributes: configure syntax highlighting, Reveal.js theme, transitions,
         *   history, and slide numbering.
         */
        @Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
        private fun Project.registerAsciidoctorRevealJsTask() {
            tasks.getByName<AsciidoctorJRevealJSTask>(TASK_ASCIIDOCTOR_REVEALJS) {
                group = GROUP_TASK_SLIDER
                description = "Compile AsciiDoc sources into a Reveal.js HTML presentation."
                setExecutionMode(OUT_OF_PROCESS)
                dependsOn(TASK_CLEAN_SLIDES_BUILD)
                finalizedBy(TASK_DASHBOARD_SLIDES_BUILD)
                revealjsOptions {
                    // Resolve and log the AsciiDoc source directory
                    layout.projectDirectory.asFile
                        .resolve(SLIDES_FOLDER)
                        .resolve(DEFAULT_SLIDES_FOLDER)
                        .apply { println("Slide source absolute path: $absolutePath") }
                        .let(::setSourceDir)
                    // Output path mirrors the source file location
                    baseDirFollowsSourceFile()
                    // Copy images alongside the generated HTML
                    resources { spec ->
                        spec.from(sourceDir.resolve(IMAGES)) { copy ->
                            copy.include("**")
                            copy.into(IMAGES)
                        }
                    }
                    // Asciidoctor + Reveal.js rendering attributes
                    attributes(
                        mapOf(
                            BUILD_GRADLE_KEY to layout.projectDirectory.asFile.resolve("build.gradle.kts"),
                            ENDPOINT_URL_KEY to "https://github.com/pages-content/slides/",
                            SOURCE_HIGHLIGHTER_KEY to "coderay",
                            CODERAY_CSS_KEY to "style",
                            RevealJsSlides.IMAGEDIR_KEY to ".${separator}images",
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
                        )
                    )
                }
            }
        }

        /**
         * Standard Asciidoctor HTML conversion task.
         * Depends on asciidoctorRevealJs so both outputs are always in sync.
         */
        @Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
        private fun Project.registerAsciidoctorTask() {
            tasks.register<AsciidoctorTask>("asciidoctor") {
                group = GROUP_TASK_SLIDER
                dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
            }
        }

        /**
         * Serves the generated presentation locally via the `serve` npm package
         * executed through npx. No browser is launched automatically.
         * Depends on asciidoctorRevealJs to ensure slides are built first.
         */
        private fun Project.registerServeSlidesTask() {
            tasks.register<NpxTask>(TASK_SERVE_SLIDES) {
                group = GROUP_TASK_SLIDER
                description = "Serve slides using the serve package executed via npx."
                dependsOn(TASK_ASCIIDOCTOR_REVEALJS)
                command.set(SERVE_DEP)
                layout.buildDirectory.get().asFile
                    .resolve("docs")
                    .resolve("asciidocRevealJs")
                    .absolutePath
                    .run(::listOf)
                    .run(args::set)
                workingDir.set(layout.projectDirectory.asFile)
                doFirst { println("Serving slides via npx serve...") }
            }
        }

        /**
         * Generates the presentation dashboard in the build output directory:
         * - slides.json  → metadata list of all available presentations
         * - index.html   → copied from slides/misc/index.html
         *
         * Scans slides/misc/ for .adoc files to populate slides.json.
         * Finalises asciidoctorRevealJs so it always runs after generation.
         */
        private fun Project.registerDashboardTask() {
            tasks.register<DefaultTask>(TASK_DASHBOARD_SLIDES_BUILD) {
                group = "documentation"
                description = "Generate index.html and slides.json listing all Reveal.js presentations."
                doLast {
                    val slidesDir = layout.projectDirectory.asFile
                        .resolve(SLIDES_FOLDER)
                        .resolve(DEFAULT_SLIDES_FOLDER)
                        .apply {
                            // Log the source index.html content for traceability
                            listFiles()?.find { it.name == "index.html" }
                                ?.readText()?.trimIndent()
                                ?.run { "index.html:\n$this" }
                                ?.run(logger::info)
                        }

                    val outputDir = layout.buildDirectory.get().asFile
                        .resolve("docs")
                        .resolve("asciidocRevealJs")
                        .also { logger.info("output dir path: $it") }

                    val slidesJsonFile = outputDir.resolve("slides.json")

                    // Ensure the output directory exists before writing
                    outputDir.mkdirs()

                    // Scan .adoc files and build the slides metadata list
                    val adocFiles = slidesDir.listFiles { file ->
                        file.isFile && file.extension == "adoc"
                    }?.map { file ->
                        mapOf(
                            "name" to file.nameWithoutExtension,
                            "filename" to "${file.nameWithoutExtension}.html"
                        )
                    }.also { println(it) } ?: emptyList()

                    // Serialise slides metadata to JSON manually (no extra dependency)
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

                    // Copy index.html from source to build output
                    val indexFile = slidesDir.resolve("index.html")
                    indexFile.copyTo(outputDir.resolve("index.html"), overwrite = true)

                    println("✅ Dashboard generated successfully!")
                    println("📁 Generated files:")
                    println("   - ${indexFile.absolutePath}")
                    println("   - ${slidesJsonFile.absolutePath}")
                    println("📊 ${adocFiles.size} presentation(s) found")
                }
            }
        }

        /**
         * Deploys the generated slides to a remote Git repository.
         *
         * Reads target repository, branch, credentials, and commit message
         * from the YAML configuration file declared via the slider DSL (configPath).
         * Delegates the actual Git operations to [Git].
         */
        private fun Project.registerPublishSlidesTask() {
            tasks.register<DefaultTask>(TASK_PUBLISH_SLIDES) {
                group = GROUP_TASK_SLIDER
                description = "Deploy generated slides to the configured remote repository."
                dependsOn("asciidoctor")
                doFirst { task ->
                    logger.info("Task description :\n\t${task.description}")
                }
                doLast {
                    // Deserialise the YAML configuration from the path stored in project properties
                    val localConf: SlidesConfiguration = properties[CONFIG_PATH_KEY].toString()
                        .run(layout.projectDirectory.asFile::resolve)
                        .readText().trimIndent()
                        .run(YAMLMapper()::readValue)

                    val repoDir = layout.buildDirectory.get().asFile
                        .resolve(localConf.pushSlides!!.to)

                    // Delegate file copy + Git push to SliderManager.Git
                    with(Git) {
                        pushSlides(
                            slidesDirPath = {
                                layout.buildDirectory.get().asFile
                                    .resolve(localConf.srcPath!!)
                                    .absolutePath
                            },
                            pathTo = { repoDir.absolutePath }
                        )
                    }
                }
            }
        }

        /**
         * Opens the default presentation deck in Firefox.
         * Deck file path is resolved from deck.properties via [SliderManager.deckFile].
         */
        private fun Project.registerOpenFirefoxTask() {
            tasks.register<Exec>("openFirefox") {
                group = GROUP_TASK_SLIDER
                description = "Open the presentation dashboard in Firefox."
                dependsOn("asciidoctor")
                commandLine("firefox", deckFile("default.deck.file"))
                workingDir = layout.projectDirectory.asFile
            }
        }

        /**
         * Opens the default presentation deck in Chromium.
         * Deck file path is resolved from deck.properties via [SliderManager.deckFile].
         */
        private fun Project.registerOpenChromiumTask() {
            tasks.register<Exec>("openChromium") {
                group = GROUP_TASK_SLIDER
                description = "Open the default presentation in Chromium."
                dependsOn("asciidoctor")
                commandLine("chromium", deckFile("default.deck.file"))
                workingDir = layout.projectDirectory.asFile
            }
        }

        /**
         * Opens a capsule-style AsciiDoc deck in Chromium.
         * Uses a distinct deck.properties key from the default deck.
         */
        private fun Project.registerAsciidocCapsuleTask() {
            tasks.register<Exec>("asciidocCapsule") {
                group = "capsule"
                description = "Open the asciidoc capsule deck in Chromium."
                dependsOn("asciidoctor")
                commandLine("chromium", deckFile("asciidoc.capsule.deck.file"))
                workingDir = layout.projectDirectory.asFile
            }
        }

        /**
         * Runs all checks and opens the unit test HTML report in Firefox.
         * Useful for quick post-build test review without manual file navigation.
         */
        private fun Project.registerReportTestsTask() {
            tasks.register<Exec>("reportTests") {
                group = "verification"
                description = "Run checks and open the unit test report in Firefox."
                dependsOn("check")
                commandLine(
                    "firefox", "--new-tab",
                    layout.buildDirectory.asFile.get()
                        .resolve("reports")
                        .resolve("tests")
                        .resolve("test")
                        .resolve("index.html")
                        .absolutePath
                )
            }
        }

        /**
         * Runs all checks and opens the functional test HTML report in Firefox.
         * Useful for reviewing GradleTestKit functional test results.
         */
        private fun Project.registerReportFunctionalTestsTask() {
            tasks.register<Exec>("reportFunctionalTests") {
                group = "verification"
                description = "Run checks and open the functional test report in Firefox."
                dependsOn("check")
                commandLine(
                    "firefox", "--new-tab",
                    layout.buildDirectory.get().asFile
                        .resolve("reports")
                        .resolve("tests")
                        .resolve("functionalTest")
                        .resolve("index.html")
                        .absolutePath
                )
            }
        }
    }

// =========================================================================
// Git
// =========================================================================

    /**
     * Handles all Git operations required to publish slides to a remote repository.
     *
     * Workflow:
     * 1. [pushSlides]            → orchestrates copy + init + commit + push + cleanup
     * 2. [initAddCommitToSlides] → initialises local repo, adds remote, stages, commits
     * 3. [pushSlide]             → authenticates and force-pushes to the remote
     */
    object Git {

        /**
         * Full publish pipeline:
         * - Creates a clean temporary repo directory at [pathTo]
         * - Copies slides from [slidesDirPath] into it via [FileOps.copySlideFilesToRepo]
         * - Commits and pushes if the copy succeeds
         * - Cleans up both the repo dir and the source slides dir on success
         */
        fun Project.pushSlides(
            slidesDirPath: () -> String,
            pathTo: () -> String
        ) = pathTo()
            .run(FileOps::createRepoDir)
            .let { repoDir: File ->
                FileOps.copySlideFilesToRepo(slidesDirPath(), repoDir)
                    .takeIf { it is FileOperationResult.Success }
                    ?.run {
                        initAddCommitToSlides(repoDir, localConf)
                        pushSlide(
                            repoDir,
                            "${project.rootDir}${File.separator}${project.properties[CONFIG_PATH_KEY]}"
                                .run(::File)
                                .readText()
                                .trimIndent()
                                .run(YAMLMapper()::readValue)
                        )
                        // Cleanup: remove temporary repo dir and source slides dir
                        repoDir.deleteRecursively()
                        slidesDirPath().let(::File).deleteRecursively()
                    }
            }

        /**
         * Initialises a local Git repository in [repoDir], adds the remote origin,
         * stages all files, and creates an initial commit.
         *
         * @param repoDir  directory to initialise as a Git repository
         * @param conf     slides configuration carrying branch, remote URL, and commit message
         */
        fun Project.initAddCommitToSlides(
            repoDir: File,
            conf: SlidesConfiguration,
        ): RevCommit =
            init()
                .setInitialBranch(conf.pushSlides?.branch)
                .setDirectory(repoDir)
                .call()
                .run {
                    assert(!repository.isBare)
                    assert(repository.directory.isDirectory)
                    // Register the remote origin
                    remoteAdd().apply {
                        setName(CVS_ORIGIN)
                        setUri(URIish(conf.pushSlides?.repo?.repository))
                    }.call()
                    // Stage all files
                    add().addFilepattern(".").call()
                    // Commit with the configured message
                    commit().setMessage(conf.pushSlides?.message).call()
                }

        /**
         * Force-pushes the local repository at [repoDir] to the configured remote.
         *
         * @param repoDir  local repository directory to push from
         * @param conf     slides configuration carrying remote URL and credentials
         * @throws IOException if the repository is bare or the Git dir cannot be found
         */
        @Throws(IOException::class)
        fun Project.pushSlide(
            repoDir: File,
            conf: SlidesConfiguration,
        ): MutableIterable<PushResult>? =
            FileRepositoryBuilder()
                .setInitialBranch("main")
                .setGitDir("${repoDir.absolutePath}${File.separator}.git".let(::File))
                .readEnvironment()
                .findGitDir()
                .setMustExist(true)
                .build()
                .apply {
                    config.apply {
                        getString(CVS_REMOTE, CVS_ORIGIN, conf.pushSlides?.repo?.repository)
                    }.save()
                    if (isBare) throw IOException("Repo dir should not be bare")
                }
                .let(::Git)
                .push()
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(
                        conf.pushSlides?.repo?.credentials?.username,
                        conf.pushSlides?.repo?.credentials?.password
                    )
                )
                .apply {
                    remote = CVS_ORIGIN
                    isForce = true
                }
                .call()
    }

// =========================================================================
// FileOps
// =========================================================================

    /**
     * Low-level file system operations used by the Git publish pipeline.
     *
     * Kept separate from [Git] to allow independent unit testing of
     * file copy and directory management logic.
     */
    object FileOps {

        /**
         * Copies all files from [slidesDirPath] into [repoDir] recursively,
         * then deletes the source directory on success.
         *
         * @return [FileOperationResult.Success] on success,
         *         [FileOperationResult.Failure] with message on any exception
         */
        fun copySlideFilesToRepo(
            slidesDirPath: String,
            repoDir: File
        ): FileOperationResult = try {
            slidesDirPath
                .let(::File)
                .apply {
                    when {
                        !copyRecursively(repoDir, true) ->
                            throw Exception("Unable to copy slides directory to build directory")
                    }
                }.deleteRecursively()
            FileOperationResult.Success
        } catch (e: Exception) {
            FileOperationResult.Failure(e.message ?: "An error occurred during file copy.")
        }

        /**
         * Creates a clean directory at [path], removing any existing file or directory
         * at that location before creating the new one.
         *
         * @throws Exception if the existing file/directory cannot be deleted,
         *                   or if the new directory cannot be created
         */
        fun createRepoDir(path: String): File = path
            .let(::File)
            .apply {
                // Remove any existing file occupying the target path
                if (exists() && !isDirectory) {
                    if (!delete()) throw Exception("Cannot delete file at repo dir path")
                }
                // Remove any existing directory
                if (exists()) {
                    if (!deleteRecursively()) throw Exception("Cannot delete existing repo dir")
                }
                // Create the fresh directory
                if (exists()) throw Exception("Repo dir should not already exist")
                if (!mkdir()) throw Exception("Cannot create repo dir")
            }
    }
}