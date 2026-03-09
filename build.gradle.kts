plugins { alias(libs.plugins.slider) }

repositories {
    "https://maven.xillio.com/artifactory/libs-release/"
        .run(::uri)
        .run(::maven)
}

slider {
    configPath = "slides-context.yml"
        .run(::file)
        .absolutePath
}

