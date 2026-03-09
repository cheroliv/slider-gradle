plugins { alias(libs.plugins.slider) }
repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://maven.xillio.com/artifactory/libs-release/") }
}

slider {
    configPath = file("slides-context.yml").absolutePath
    forceDocker = false
}

