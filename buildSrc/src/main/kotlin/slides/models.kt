package slides

import git.GitPushConfiguration


data class SlidesConfiguration(
    val srcPath: String?=null,
    val pushSlides: GitPushConfiguration?=null,
)