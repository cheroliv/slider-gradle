//plugins { alias(libs.plugins.slider) }
//
//slider { configPath = file("slides-context.yml").absolutePath }
import org.asciidoctor.gradle.jvm.slides.AsciidoctorJRevealJSTask
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
import workspace.WorkspaceUtils.sep

plugins { id("org.asciidoctor.jvm.revealjs") }

apply<slides.SlidesBuildSrcPlugin>()

project.tasks.getByName<AsciidoctorJRevealJSTask>(TASK_ASCIIDOCTOR_REVEALJS) {
    repositories { ruby { gems() } }
    group = GROUP_TASK_SLIDER
    description = "Slider settings and generation"
    dependsOn(TASK_CLEAN_SLIDES_BUILD)
    finalizedBy(TASK_DASHBOARD_SLIDES_BUILD)
    revealjs {
        version = "3.1.0"
        templateGitHub {
            setOrganisation("hakimel")
            setRepository("reveal.js")
            setTag("3.9.1")
        }
    }

    revealjsOptions {
        //TODO: passer cette adresse a la configuration du slide pour indiquer sa source,
        // creer une localConf de type slides.SlidesConfiguration
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
            BUILD_GRADLE_KEY to project.layout.projectDirectory
                .asFile.resolve("build.gradle.kts"),
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

