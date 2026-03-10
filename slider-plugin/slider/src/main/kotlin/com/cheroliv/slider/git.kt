package com.cheroliv.slider

import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult

object RepositoryInfo {
    const val ORIGIN = "origin"
    const val CNAME = "CNAME"
    const val REMOTE = "remote"
}

@JvmRecord
data class RepositoryConfiguration(
    val name: String,
    val repository: String,
    val credentials: RepositoryCredentials,
)

@JvmRecord
data class RepositoryCredentials(
    val username: String,
    val password: String
)

@JvmRecord
data class GitPushConfiguration(
    val from: String,
    val to: String,
    val repo: RepositoryConfiguration,
    val branch: String,
    val message: String,
)

sealed class GitOperationResult {
    data class Success(
        val commit: RevCommit,
        val pushResults: MutableIterable<PushResult>?
    ) : GitOperationResult()

    data class Failure(val error: String) : GitOperationResult()
}

sealed class FileOperationResult {
    object Success : FileOperationResult()
    data class Failure(val error: String) : FileOperationResult()
}

sealed class WorkspaceError {
    object FileNotFound : WorkspaceError()
    data class ParsingError(val message: String) : WorkspaceError()
}