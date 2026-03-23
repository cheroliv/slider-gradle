plugins {
    alias(libs.plugins.slider)
    alias(libs.plugins.readme)
}

slider {
    configPath = "slides-context.yml"
        .run(::file)
        .absolutePath
}

