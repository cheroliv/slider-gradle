package com.cheroliv.slider

import com.cheroliv.slider.ai.AiConfiguration

data class SlidesConfiguration(
    val srcPath: String? = null,
    val pushSlides: GitPushConfiguration? = null,
    val ai: AiConfiguration? = null,
)