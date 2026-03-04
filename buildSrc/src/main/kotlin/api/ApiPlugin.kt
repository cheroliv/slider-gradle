@file:Suppress("unused")

package api

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import workspace.WorkspacePlugin.School.LOCAL_PROFILE
import workspace.WorkspacePlugin.School.SPRING_PROFILE_KEY

class ApiPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.register<Exec>("reportTestApi") {
            group = "api"
            commandLine("./gradlew", "-q", "-s", "-p", "../api", ":reportTests")
        }

        project.tasks.register<Exec>("testApi") {
            group = "api"
            commandLine("./gradlew", "-q", "-s", "-p", "../api", ":check", "--rerun-tasks")
        }

        project.tasks.register<Exec>("runInstaller") {
            group = "installer"
            commandLine(
                "java", "-jar",
                "../api/installer/build/libs/installer-${project.properties["artifact.version"]}.jar"
            )
        }

        project.tasks.register<Exec>("runApi") {
            group = "api"
            dependsOn("buildApi")
            commandLine(
                "java", "-jar",
                "../api/build/libs/api-${project.properties["artifact.version"]}.jar"
            )
        }

        project.tasks.register<Exec>("runLocalApi") {
            group = "api"
            dependsOn("buildApi")
            commandLine(
                "java", "-D$SPRING_PROFILE_KEY=$LOCAL_PROFILE",
                "-jar", "../api/build/libs/api-${project.properties["artifact.version"]}.jar"
            )
        }

        project.tasks.register<Exec>("buildApi") {
            group = "api"
            commandLine(
                "./gradlew", "-q", "-s", "-p", "../api", ":build"
            )
        }

        //TODO: Create another module in api to get cli its own archive(task jar)
        project.tasks.register<Exec>("runCli") {
            group = "api"
            dependsOn("buildApi")
            commandLine("./gradlew", "-q", "-s", "-p", "../api", ":cli")
        }
    }
}