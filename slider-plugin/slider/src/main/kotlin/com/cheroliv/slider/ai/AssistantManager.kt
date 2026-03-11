@file:Suppress("MemberVisibilityCanBePrivate")

package com.cheroliv.slider.ai

import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.cheroliv.slider.SliderManager.localConf
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gradle.api.Project
import java.io.File
import java.time.Duration.ofSeconds
import java.util.*
import kotlin.coroutines.resume

object AssistantManager {

    @JvmStatic
    val Project.privateProps: Properties
        get() = Properties().apply {
            "$projectDir/private.properties"
                .let(::File)
                .inputStream()
                .let(::load)
        }

    @JvmStatic
    fun main(args: Array<String>) {
        PromptManager.userMessageFr.run { "userMessageFr : $this" }.run(::println)
        println()
        PromptManager.userMessageEn.run { "userMessageEn : $this" }.run(::println)
    }

    // =========================================================================
    // Model catalogs
    // =========================================================================

    @JvmStatic
    val localModels
        get() = setOf(
            "smollm:135m" to "SmollM",
            "llama3.2:3b-instruct-q8_0" to "LlamaTiny",
            "smollm:135m-instruct-v0.2-q8_0" to "SmollMInstruct",
            "gemma3:1b-it-fp16" to "Gemma3Instruct",
        )

    @JvmStatic
    val geminiModels
        get() = setOf(
            "gemini-2.5-flash" to "GeminiFlash25",
            "gemini-2.0-flash" to "GeminiFlash20",
        )

    // =========================================================================
    // Task registration
    // =========================================================================

    @JvmStatic
    fun Project.createChatTasks() {
        // Ollama — local models
        localModels.forEach {
            createChatTask(it.first, "helloOllama${it.second}")
            createStreamingChatTask(it.first, "helloOllamaStream${it.second}")
        }
        // Gemini — Google AI
        geminiModels.forEach {
            createGeminiChatTask(it.first, "helloGemini${it.second}")
            createGeminiStreamingChatTask(it.first, "helloGeminiStream${it.second}")
        }
        // Display prompt
        tasks.register("displayE3POPrompt") {
            it.group = "slider-ai"
            it.description = "Display on console AI prompt assistant"
            it.doFirst { PromptManager.userMessageFr.let(::println) }
        }
    }

    // =========================================================================
    // Ollama model factories
    // =========================================================================

    fun Project.createOllamaChatModel(model: String = "smollm:135m"): OllamaChatModel =
        OllamaChatModel.builder().apply {
            baseUrl(findProperty("ollama.baseUrl") as? String ?: "http://localhost:11434")
            modelName(findProperty("ollama.modelName") as? String ?: model)
            temperature(findProperty("ollama.temperature") as? Double ?: 0.8)
            timeout(ofSeconds(findProperty("ollama.timeout") as? Long ?: 6_000))
            logRequests(true)
            logResponses(true)
        }.build()

    fun Project.createOllamaStreamingChatModel(model: String = "smollm:135m"): OllamaStreamingChatModel =
        OllamaStreamingChatModel.builder().apply {
            baseUrl(findProperty("ollama.baseUrl") as? String ?: "http://localhost:11434")
            modelName(findProperty("ollama.modelName") as? String ?: model)
            temperature(findProperty("ollama.temperature") as? Double ?: 0.8)
            timeout(ofSeconds(findProperty("ollama.timeout") as? Long ?: 6_000))
            logRequests(true)
            logResponses(true)
        }.build()

    // =========================================================================
    // Gemini model factories
    // =========================================================================

    fun Project.createGeminiChatModel(
        model: String = "gemini-2.5-flash"
    ): GoogleAiGeminiChatModel =
        GoogleAiGeminiChatModel.builder()
            .apiKey(
                localConf.ai?.gemini?.firstOrNull()
                    ?: error("No Gemini API key found in slides-context.yml under ai.gemini")
            )
            .modelName(model)
            .temperature(1.0)
            .logRequestsAndResponses(true)
            .build()

    fun Project.createGeminiStreamingChatModel(
        model: String = "gemini-2.5-flash"
    ): GoogleAiGeminiStreamingChatModel =
        GoogleAiGeminiStreamingChatModel.builder()
            .apiKey(
                localConf.ai?.gemini?.firstOrNull()
                    ?: error("No Gemini API key found in slides-context.yml under ai.gemini")
            )
            .modelName(model)
            .temperature(1.0)
            .logRequestsAndResponses(true)
            .build()

    // =========================================================================
    // Shared streaming coroutine bridge
    // =========================================================================

    suspend fun generateStreamingResponse(
        model: StreamingChatModel,
        promptMessage: String
    ): Either<Throwable, ChatResponse> = catch {
        suspendCancellableCoroutine { continuation ->
            model.chat(promptMessage, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) = print(partialResponse)
                override fun onCompleteResponse(response: ChatResponse) = continuation.resume(response)
                override fun onError(error: Throwable) {
                    continuation.cancel(error)
                }
            })
        }
    }

    // =========================================================================
    // Ollama runners
    // =========================================================================

    fun Project.runChat(model: String) {
        createOllamaChatModel(model = model)
            .run { PromptManager.userMessageFr.run(::chat).let(::println) }
    }

    fun Project.runStreamChat(model: String) {
        runBlocking {
            createOllamaStreamingChatModel(model).run {
                when (val answer = generateStreamingResponse(this, PromptManager.userMessageFr)) {
                    is Right -> "Complete response received:\n${answer.value.aiMessage().text()}".run(::println)
                    is Left -> "Error during response generation:\n${answer.value}".run(::println)
                }
            }
        }
    }

    // =========================================================================
    // Gemini runners
    // =========================================================================

    fun Project.runGeminiChat(model: String) {
        createGeminiChatModel(model = model)
            .run { PromptManager.userMessageFr.run(::chat).let(::println) }
    }

    fun Project.runGeminiStreamChat(model: String) {
        runBlocking {
            createGeminiStreamingChatModel(model).run {
                when (val answer = generateStreamingResponse(this, PromptManager.userMessageFr)) {
                    is Right -> "Complete response received:\n${answer.value.aiMessage().text()}".run(::println)
                    is Left -> "Error during response generation:\n${answer.value}".run(::println)
                }
            }
        }
    }

    // =========================================================================
    // Ollama task factories
    // =========================================================================

    fun Project.createChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the Ollama $model chat prompt request."
            it.doFirst { project.runChat(model) }
        }
    }

    fun Project.createStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the Ollama $model streaming chat prompt request."
            it.doFirst { runStreamChat(model) }
        }
    }

    // =========================================================================
    // Gemini task factories
    // =========================================================================

    fun Project.createGeminiChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the Gemini $model chat prompt request."
            it.doFirst { project.runGeminiChat(model) }
        }
    }

    fun Project.createGeminiStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the Gemini $model streaming chat prompt request."
            it.doFirst { runGeminiStreamChat(model) }
        }
    }

    // =========================================================================
    // Misc
    // =========================================================================

    @JvmStatic
    val Project.openAIapiKey: String
        get() = privateProps["OPENAI_API_KEY"] as String

    // =========================================================================
    // PromptManager
    // =========================================================================

    object PromptManager {
        @JvmStatic
        fun main(args: Array<String>) {
            userMessageFr.run(::println)
        }

        const val ASSISTANT_NAME = "E-3PO"
        val userName = System.getProperty("user.name")!!

        val userMessageFr = """config```--lang=fr;```.
            | Salut je suis $userName,
            | toi tu es $ASSISTANT_NAME, tu es mon assistant.
            | Le cœur de métier de ${System.getProperty("user.name")} est le développement logiciel dans l'EdTech
            | et la formation professionnelle pour adulte.
            | La spécialisation de ${System.getProperty("user.name")} est dans l'ingénierie de la pédagogie pour adulte,
            | et le software craftmanship avec les méthodes agiles.
            | $ASSISTANT_NAME ta mission est d'aider ${System.getProperty("user.name")} dans l'activité d'écriture de formation et génération de code.
            | Réponds moi à ce premier échange uniquement en maximum 120 mots""".trimMargin()

        val userMessageEn = """config```--lang=en;```.
        | You are E-3PO, an AI assistant specialized in EdTech and professional training.
        | Your primary user is cheroliv, a software craftsman and adult education expert
        | who focuses on EdTech development and agile methodologies.
        | Your base responsibilities:
        | 1. Assist with creating educational content for adult learners
        | 2. Help with code generation and software development tasks
        | 3. Support application of agile and software craftsmanship principles
        | 4. Provide guidance on instructional design for adult education
        | Please communicate clearly and concisely, focusing on practical solutions.
        | Keep initial responses under 120 words.""".trimMargin()
    }
}