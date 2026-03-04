package projects

import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class ProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.register("startProjectManager") {
            group = "Project"
            description = "Start Project Manager."
            doFirst { println(":startProjectManager:doFirst : ") }
            doLast { println(":startProjectManager:doLast : ") }
        }
    }
}