pluginManagement {
    repositories.gradlePluginPortal()
    plugins {
        kotlin("jvm").version(extra["kotlin.version"].toString())
        id("com.github.node-gradle.node").version(extra["node-gradle.version"].toString())
//        id("org.gradle.toolchains.foojay-resolver-convention").version(extra["toolchains.version"].toString())
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement.repositories.mavenCentral()

pluginManagement.repositories{
    mavenLocal()
    gradlePluginPortal()
}


rootProject.name = "slider-gradle"