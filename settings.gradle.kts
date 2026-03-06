pluginManagement.repositories{
    mavenLocal()
    gradlePluginPortal()
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version ("0.8.0") }
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}
rootProject.name = "slider-gradle"