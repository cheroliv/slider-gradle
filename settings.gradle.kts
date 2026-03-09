pluginManagement.repositories {
    mavenLocal()
    gradlePluginPortal()
    "https://repo.gradle.org/gradle/libs-releases/"
        .run(::uri)
        .run(::maven)
}
rootProject.name = "slider-gradle"