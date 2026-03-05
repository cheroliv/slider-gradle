pluginManagement {
    repositories.gradlePluginPortal()
    plugins {
        kotlin("jvm").version(extra["kotlin.version"].toString())
        id("com.github.node-gradle.node").version(extra["node-gradle.version"].toString())
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement.repositories.mavenCentral()

pluginManagement.repositories{
    mavenLocal()
    gradlePluginPortal()
}


rootProject.name = "slider-gradle"