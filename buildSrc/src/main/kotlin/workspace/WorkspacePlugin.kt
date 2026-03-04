@file:Suppress("unused")

package workspace

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.withType
import workspace.WorkspaceManager.privateProps
import workspace.WorkspaceUtils.purchaseArtifact

class WorkspacePlugin : Plugin<Project> {

    object School {
        const val GROUP_KEY = "artifact.group"
        const val VERSION_KEY = "artifact.version"
        const val SPRING_PROFILE_KEY = "spring.profiles.active"
        const val LOCAL_PROFILE = "local"
    }

    override fun apply(project: Project) {
        project.plugins.apply("idea")
        project.purchaseArtifact()
        project.tasks.getByName<Wrapper>("wrapper").gradleVersion = "8.14.2"

        project.tasks.withType<JavaExec> {
            jvmArgs = listOf(
                "--add-modules=jdk.incubator.vector",
                "--enable-native-access=ALL-UNNAMED",
                "--enable-preview"
            )
        }

        project.tasks.register("displayPrivateProperties") {
            group = "school"
            description = "Display the key/value pairs stored in private.properties"
            doFirst { println("PrivateProperties : ${project.privateProps}") }
        }
    }
}