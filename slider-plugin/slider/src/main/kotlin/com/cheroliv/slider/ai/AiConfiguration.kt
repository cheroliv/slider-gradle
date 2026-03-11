package com.cheroliv.slider.ai

data class AiConfiguration(
    val gemini: List<String> = emptyList(),
    val huggingface: List<String> = emptyList(),
    val mistral: List<String> = emptyList(),
)