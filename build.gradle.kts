plugins { alias(libs.plugins.slider) }

slider {
    configPath = "slides-context.yml"
        .run(::file)
        .absolutePath
}

