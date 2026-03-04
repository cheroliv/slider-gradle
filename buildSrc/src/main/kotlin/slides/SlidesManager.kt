package slides

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import git.FileOperationResult
import git.GitPushConfiguration
import git.RepositoryConfiguration
import git.RepositoryCredentials
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.gradle.api.Project
import workspace.WorkspaceManager
import workspace.WorkspaceManager.CVS_ORIGIN
import workspace.WorkspaceManager.CVS_REMOTE
import workspace.WorkspaceUtils.sep
import workspace.WorkspaceUtils.yamlMapper
import java.io.File
import java.io.IOException
import java.util.*


object SlidesManager {
    const val CONFIG_PATH_KEY = "managed_config_path"

    fun Project.readSlidesConfigurationFile(
        configPath: () -> String
    ): SlidesConfiguration = try {
        configPath()
            .run(::File)
            .run(yamlMapper::readValue)
    } catch (e: Exception) {
        // Handle exception or log error
        SlidesConfiguration(
            "",
            GitPushConfiguration(
                "",
                "",
                RepositoryConfiguration(
                    "",
                    "",
                    RepositoryCredentials(
                        "",
                        ""
                    )
                ),
                "",
                ""
            )
        )
    }


    val Project.localConf: SlidesConfiguration
        get() = readSlidesConfigurationFile { "$rootDir$sep${properties[CONFIG_PATH_KEY]}" }

    val Project.slideSrcPath: String get() = "${layout.buildDirectory.get().asFile.absolutePath}/${localConf.srcPath}/"
    val Project.slideDestDirPath: String get() = localConf.pushSlides?.to!!


    fun Project.deckFile(key: String): String = buildString {
        append("build/docs/asciidocRevealJs/")
        append(Properties().apply {
            // TODO changer par une reference au path de office a integrer dans le model de données
            buildString {
                append(System.getProperty("user.home"))
                append(sep)
                append("workspace")
                append(sep)
                append("office")
                append(sep)
                append("slides")
                append(sep)
                append("misc")
                append(sep)
                append("deck.properties")
            }.let(::File)
                .inputStream()
                .use(::load)
        }[key].toString())
    }

    fun Project.pushSlides(
        slidesDirPath: () -> String,
        pathTo: () -> String
    ) = pathTo()
        .run(WorkspaceManager::createRepoDir)
        .let { it: File ->
            copySlideFilesToRepo(slidesDirPath(), it)
                .takeIf { it is FileOperationResult.Success }
                ?.run {
                    initAddCommitToSlides(it, localConf)
                    pushSlide(
                        it,
                        "${project.rootDir}${sep}${project.properties[CONFIG_PATH_KEY]}"
                            .run(::File)
                            .readText()
                            .trimIndent()
                            .run(YAMLMapper()::readValue)
                    )
                    it.deleteRecursively()
                    slidesDirPath()
                        .let(::File)
                        .deleteRecursively()
                }
        }

    @Throws(IOException::class)
    fun Project.pushSlide(
        repoDir: File,
        conf: SlidesConfiguration,
    ): MutableIterable<PushResult>? = FileRepositoryBuilder()
        .setInitialBranch("main")
        .setGitDir("${repoDir.absolutePath}$sep.git".let(::File))
        .readEnvironment()
        .findGitDir()
        .setMustExist(true)
        .build()
        .apply {
            config.apply {
                getString(
                    CVS_REMOTE,
                    CVS_ORIGIN,
                    conf.pushSlides?.repo?.repository
                )
            }.save()
            if (isBare) throw IOException("Repo dir should not be bare")
        }.let(::Git)
        .run {
            // push to remote:
            return push().setCredentialsProvider(
                UsernamePasswordCredentialsProvider(
                    conf.pushSlides?.repo?.credentials?.username,
                    conf.pushSlides?.repo?.credentials?.password
                )
            ).apply {
                //you can add more settings here if needed
                remote = CVS_ORIGIN
                isForce = true

            }.call()
        }

    fun Project.initAddCommitToSlides(
        repoDir: File,
        conf: SlidesConfiguration,
    ): RevCommit {
        //3) initialiser un repo dans le dossier cvs
        Git.init().setInitialBranch(conf.pushSlides?.branch)
            .setDirectory(repoDir).call().run {
                assert(!repository.isBare)
                assert(repository.directory.isDirectory)
                // add remote repo:
                remoteAdd().apply {
                    setName(CVS_ORIGIN)
                    setUri(URIish(conf.pushSlides?.repo?.repository))
                    // you can add more settings here if needed

                }.call()
                //4) ajouter les fichiers du dossier cvs à l'index
                add().addFilepattern(".").call()

                //5) commit
                return commit().setMessage(conf.pushSlides?.message).call()
            }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun copySlideFilesToRepo(
        slidesDirPath: String,
        repoDir: File
    ): FileOperationResult = try {
        slidesDirPath
            .let(::File)
            .apply {
                when {
                    !copyRecursively(
                        repoDir,
                        true
                    ) -> throw Exception("Unable to copy slides directory to build directory")
                }
            }.deleteRecursively()
        FileOperationResult.Success
    } catch (e: Exception) {
        FileOperationResult.Failure(e.message ?: "An error occurred during file copy.")
    }
}