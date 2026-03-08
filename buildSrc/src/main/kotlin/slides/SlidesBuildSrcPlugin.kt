package slides

import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.asciidoctor.gradle.jvm.slides.AsciidoctorJRevealJSTask
import org.asciidoctor.gradle.jvm.slides.RevealJSExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec.ADOPTIUM
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
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
import slides.Slides.RevealJsSlides.TOC_KEY
import slides.Slides.Slide.DEFAULT_SLIDES_FOLDER
import slides.Slides.Slide.IMAGES
import slides.Slides.Slide.SLIDES_FOLDER
import java.io.File.separator

class SlidesBuildSrcPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.plugins.apply("com.github.node-gradle.node")
        project.plugins.apply("org.asciidoctor.jvm.gems")
        project.plugins.apply("org.asciidoctor.jvm.revealjs")

        project.repositories {
            gradlePluginPortal { content { excludeGroup("rubygems") } }
            mavenCentral { content { excludeGroup("rubygems") } }
            ivy {
                url = project.uri("https://rubygems.org/gems/")
                patternLayout { artifact("[module]-[revision].[ext]") }
                metadataSources { artifact() }
                content { includeGroup("rubygems") }
            }
        }

        project.dependencies.add(
            "asciidoctorGems",
            "rubygems:asciidoctor-revealjs:3.1.0"
        )


        project.tasks.getByName<AsciidoctorJRevealJSTask>(TASK_ASCIIDOCTOR_REVEALJS) {
            group = GROUP_TASK_SLIDER
            description = "Slider settings and generation"
            project.repositories.mavenCentral {
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
                            vendor.set(ADOPTIUM)
                        }.get()
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
                    IMAGEDIR_KEY to ".${separator}images",
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
    }
}