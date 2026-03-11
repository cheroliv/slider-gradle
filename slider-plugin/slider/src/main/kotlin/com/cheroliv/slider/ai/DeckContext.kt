package com.cheroliv.slider

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