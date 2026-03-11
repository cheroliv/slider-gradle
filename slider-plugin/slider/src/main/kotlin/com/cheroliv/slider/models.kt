package com.cheroliv.slider

import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult

data class SlidesConfiguration(
    val srcPath: String? = null,
    val pushSlides: GitPushConfiguration? = null,
    val ai: AiConfiguration? = null,
)

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

data class AiConfiguration(
    val gemini: List<String> = emptyList(),
    val huggingface: List<String> = emptyList(),
    val mistral: List<String> = emptyList(),
)

enum class PageNotesStyle {
    MINIMAL,        // just a reference
    DETAILED,       // deep content + references
    EXERCISES_ONLY  // practical exercises only
}

data class NotesConfiguration(
    val speakerNotes: Boolean = true,
    val pageNotes: Boolean = true,
    val pageNotesStyle: PageNotesStyle = PageNotesStyle.DETAILED,
)

data class SlideHint(
    val title: String,
    val speakerHint: String? = null,
    val pageNotesHint: String? = null,
)

data class AuthorContext(
    val name: String,
    val email: String,
)

data class RevealJsContext(
    val theme: String = "sky",
    val slideNumber: String = "c/t",
    val width: Int = 1408,
    val height: Int = 792,
    val controls: Boolean = true,
    val controlsLayout: String = "edges",
    val history: Boolean = true,
    val fragmentInURL: Boolean = true,
)

data class DeckContext(
    val subject: String,
    val audience: String,
    val duration: Int,
    val language: String = "French",
    val outputFile: String,
    val author: AuthorContext,
    val revealjs: RevealJsContext = RevealJsContext(),
    val notes: NotesConfiguration = NotesConfiguration(),
    // optional — LLM is free to decide slide structure if empty
    val slides: List<SlideHint> = emptyList(),
)