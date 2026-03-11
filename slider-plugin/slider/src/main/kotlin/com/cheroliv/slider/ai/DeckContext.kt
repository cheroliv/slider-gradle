package com.cheroliv.slider.ai

data class DeckContext(
    val subject: String,
    val audience: String,
    val duration: Int,
    val language: String = "French",
    val outputFile: String,
    val author: AuthorContext,
    val revealjs: RevealJsContext = RevealJsContext(),
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