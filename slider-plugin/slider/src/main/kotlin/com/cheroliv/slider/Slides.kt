package com.cheroliv.slider

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
        const val SLIDES_CONTEXT_YML = "slides-context.yml"
        //TODO: construct path from config file in yaml format

    }
}