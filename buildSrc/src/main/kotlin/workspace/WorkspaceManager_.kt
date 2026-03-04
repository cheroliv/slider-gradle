//@file:Suppress(
//    "unused",
//    "MemberVisibilityCanBePrivate"
//)
//
package workspace
//
//import app.workspace.Workspace.InstallationType.ALL_IN_ONE
//import app.workspace.Workspace.WorkspaceConfig
//import app.workspace.Workspace.WorkspaceEntry
//import app.workspace.Workspace.WorkspaceEntry.CollaborationEntry.Collaboration
//import app.workspace.Workspace.WorkspaceEntry.CommunicationEntry.Communication
//import app.workspace.Workspace.WorkspaceEntry.ConfigurationEntry.Configuration
//import app.workspace.Workspace.WorkspaceEntry.CoreEntry.Education
//import app.workspace.Workspace.WorkspaceEntry.CoreEntry.Education.EducationEntry.*
//import app.workspace.Workspace.WorkspaceEntry.DashboardEntry.Dashboard
//import app.workspace.Workspace.WorkspaceEntry.JobEntry.Job
//import app.workspace.Workspace.WorkspaceEntry.JobEntry.Job.HumanResourcesEntry.Position
//import app.workspace.Workspace.WorkspaceEntry.JobEntry.Job.HumanResourcesEntry.Resume
//import app.workspace.Workspace.WorkspaceEntry.OfficeEntry.Office
//import app.workspace.Workspace.WorkspaceEntry.OfficeEntry.Office.LibraryEntry.*
//import app.workspace.Workspace.WorkspaceEntry.OrganisationEntry.Organisation
//import app.workspace.Workspace.WorkspaceEntry.PortfolioEntry.Portfolio
//import app.workspace.Workspace.WorkspaceEntry.PortfolioEntry.Portfolio.PortfolioProject
//import app.workspace.Workspace.WorkspaceEntry.PortfolioEntry.Portfolio.PortfolioProject.ProjectBuild
//import app.workspace.WorkspaceManager.WorkspaceConstants.entries
//import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
//import java.io.File
//import java.nio.file.Path
//import kotlin.io.path.pathString
//
//object WorkspaceManager {
//
//    object WorkspaceConstants {
//        val entries = listOf(
//            "office",
//            "education",
//            "communication",
//            "configuration",
//            "job"
//        )
//    }
//    fun Workspace.displayWorkspaceStructure(): Unit = toYaml.run(::println)
//
//    val Workspace.toYaml: String
//        get() = run(YAMLMapper()::writeValueAsString)
//
//    fun createWorkspace(
//        config: WorkspaceConfig
//    ): WorkspaceConfig = config.createConfigFiles("config.yaml").run {
//        if (config.type == ALL_IN_ONE) {
//            config.allInOneDirectory(config.basePath)
//        }
//        return config
//    }
//
//    fun WorkspaceConfig.allInOneDirectory(basePath: Path)
//            : WorkspaceConfig = entries.forEach {
//        it.run(basePath::resolve).run(WorkspaceManager::createDirectory)
//    }.let { this@allInOneDirectory }
//
//    fun createDirectory(path: Path): File = path.toFile().apply {
//        when {
//            !exists() -> mkdirs()
//        }
//    }
//
//
//    fun WorkspaceConfig.createConfigFiles(configFileName: String) = File(
//        basePath.toFile(),
//        configFileName
//    ).apply {
//        when {
//            exists() -> delete()
//        }
//        createNewFile()
//        workspace.toYaml.trimIndent().run(::writeText)
//    }
//
//    val WorkspaceConfig.workspace: Workspace
//        get() = Workspace(
//            entries = WorkspaceEntry(
//                name = "workspace",
//                path = basePath.pathString,
//                office = Office(
//                    books = Books(name = "books-collection"),
//                    datas = Datas(name = "datas"),
//                    formations = TrainingCatalogue(catalogue = "formations"),
//                    bizness = Profession("bizness"),
//                    notebooks = Notebooks(notebooks = "notebooks"),
//                    pilotage = Pilotage(name = "pilotage"),
//                    schemas = Schemas(name = "schemas"),
//                    slides = Slides(path = "slides"),
//                    sites = Sites(name = "sites"),
//                    path = subPaths["office"]?.pathString ?: "office"
//                ),
//                cores = mapOf(
//                    "education" to Education(
//                        path = subPaths["education"]?.pathString ?: "education",
//                        school = School(name = "talaria"),
//                        student = Student(name = "olivier"),
//                        teacher = Teacher(name = "cheroliv"),
//                        educationTools = EducationTools(name = "edTools")
//                    ),
//                ),
//                job = Job(
//                    path = subPaths["job"]?.pathString ?: "job",
//                    position = Position("Teacher"),
//                    resume = Resume(name = "CV")
//                ),
//                configuration = Configuration(
//                    path = subPaths["configuration"]?.pathString ?: "configuration",
//                    configuration = "school-configuration"
//                ),
//                communication = Communication(
//                    path = subPaths["communication"]?.pathString ?: "communication",
//                    site = "static-website"
//                ),
//                organisation = Organisation(organisation = "organisation"),
//                collaboration = Collaboration(collaboration = "collaboration"),
//                dashboard = Dashboard(dashboard = "dashboard"),
//                portfolio = Portfolio(
//                    mutableMapOf(
//                        "school" to PortfolioProject(
//                            name = "name",
//                            cred = "credential",
//                            builds = mutableMapOf("training" to ProjectBuild(name = "training"))
//                        )
//                    )
//                ),
//            )
//        )
//}