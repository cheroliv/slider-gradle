package school.training.content

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import java.io.File


class TrainingContentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register("pushTrainingCatalogue") {
            group = "trainings"
            description = "Push training catalogue content to remote repository"
            println("push training catalogue to remote repository")
        }

        project.tasks.register<Exec>("serveTrainingCatalogue") {
            group = "trainings"
            description = "Serve baked training catalogue locally."
            commandLine("./jbake.sh")
            //TODO: change path build over user.home property to project layout when adding gradle support to office repository
            workingDir = "${System.getProperty("user.home")}/workspace/office/formations".run(::File)
            doFirst { println("Serve baked training catalogue locally.") }
        }

        project.tasks.register("pushSchoolFrontend") {
            group = "trainings"
            description = "Push school frontend to remote repository"
            println("push school frontend to remote repository")
        }

        project.tasks.register("pushSchoolBackoffice") {
            group = "trainings"
            description = "Push school backoffice to remote repository"
            println("push school backoffice  to remote repository")
        }
    }
}