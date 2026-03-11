@file:Suppress("MemberVisibilityCanBePrivate")

package com.cheroliv.slider.ai

import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.cheroliv.slider.SliderManager.localConf
import com.cheroliv.slider.SliderManager.yamlMapper
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gradle.api.Project
import java.io.File
import java.time.Duration.ofSeconds
import java.util.Properties
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

    @JvmStatic
    val huggingFaceModels
        get() = setOf(
            "meta-llama/Llama-3.1-8B-Instruct:sambanova" to "Llama31Sambanova",
            "Qwen/Qwen3.5-35B-A3B:novita" to "Qwen35Novita",
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
        // HuggingFace — via OpenAI-compatible router
        huggingFaceModels.forEach {
            createHuggingFaceChatTask(it.first, "helloHuggingFace${it.second}")
            createHuggingFaceStreamingChatTask(it.first, "helloHuggingFaceStream${it.second}")
        }
        // Display prompt
        tasks.register("displayE3POPrompt") {
            it.group = "slider-ai"
            it.description = "Display on console AI prompt assistant"
            it.doFirst { PromptManager.userMessageFr.let(::println) }
        }
        // Generate deck from *-deck-context.yml
        tasks.register("generateDeck") {
            it.group = "slider-ai"
            it.description = "Generate a complete AsciiDoc/Reveal.js deck from a *-deck-context.yml"
            it.doFirst {
                val contextPath = project.findProperty("deck.context") as? String
                    ?: error("Provide -Pdeck.context=slides/misc/my-deck-context.yml")
                val ctx: DeckContext = project.layout.projectDirectory.asFile
                    .resolve(contextPath)
                    .run { yamlMapper.readValue(this, DeckContext::class.java) }
                val output = project.layout.projectDirectory.asFile
                    .resolve("slides/misc/${ctx.outputFile}")
                createGeminiChatModel()
                    .chat(
                        SystemMessage.from(PromptManager.deckSystemPrompt),
                        UserMessage.from(PromptManager.deckUserMessage(ctx))
                    )
                    .let { response ->
                        output.writeText(response.aiMessage().text())
                        println("✅ Deck generated : ${output.absolutePath}")
                    }
            }
        }
    }

    // =========================================================================
    // Ollama model factories
    // =========================================================================

    fun Project.createOllamaChatModel(model: String = "smollm:135m"): OllamaChatModel =
        (findProperty("ollama.baseUrl") as? String ?: "http://localhost:11434")
            .run(OllamaChatModel.builder()::baseUrl)
            .modelName(findProperty("ollama.modelName") as? String ?: model)
            .temperature(findProperty("ollama.temperature") as? Double ?: 0.8)
            .timeout(ofSeconds(findProperty("ollama.timeout") as? Long ?: 6_000))
            .logRequests(true)
            .logResponses(true)
            .build()

    fun Project.createOllamaStreamingChatModel(model: String = "smollm:135m"): OllamaStreamingChatModel =
        (findProperty("ollama.baseUrl") as? String ?: "http://localhost:11434")
            .run(OllamaStreamingChatModel.builder()::baseUrl)
            .modelName(findProperty("ollama.modelName") as? String ?: model)
            .temperature(findProperty("ollama.temperature") as? Double ?: 0.8)
            .timeout(ofSeconds(findProperty("ollama.timeout") as? Long ?: 6_000))
            .logRequests(true)
            .logResponses(true)
            .build()

    // =========================================================================
    // Gemini model factories
    // =========================================================================

    fun Project.createGeminiChatModel(
        model: String = "gemini-2.5-flash"
    ): GoogleAiGeminiChatModel =
        (localConf.ai?.gemini?.firstOrNull()
            ?: error("No Gemini API key found in slides-context.yml under ai.gemini"))
            .run(GoogleAiGeminiChatModel.builder()::apiKey)
            .modelName(model)
            .temperature(1.0)
            .logRequestsAndResponses(true)
            .build()

    fun Project.createGeminiStreamingChatModel(
        model: String = "gemini-2.5-flash"
    ): GoogleAiGeminiStreamingChatModel =
        (localConf.ai?.gemini?.firstOrNull()
            ?: error("No Gemini API key found in slides-context.yml under ai.gemini"))
            .run(GoogleAiGeminiStreamingChatModel.builder()::apiKey)
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
                override fun onError(error: Throwable) { continuation.cancel(error) }
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
    // HuggingFace model factories (via OpenAI-compatible router)
    // =========================================================================

    fun Project.createHuggingFaceChatModel(
        model: String = "HuggingFaceTB/SmolLM3-3B:hf-inference"
    ): OpenAiChatModel =
        (localConf.ai?.huggingface?.firstOrNull()
            ?: error("No HuggingFace token found in slides-context.yml under ai.huggingface"))
            .run(OpenAiChatModel.builder()::apiKey)
            .baseUrl("https://router.huggingface.co/v1")
            .modelName(model)
            .logRequests(true)
            .logResponses(true)
            .build()

    fun Project.createHuggingFaceStreamingChatModel(
        model: String = "HuggingFaceTB/SmolLM3-3B:hf-inference"
    ): OpenAiStreamingChatModel =
        (localConf.ai?.huggingface?.firstOrNull()
            ?: error("No HuggingFace token found in slides-context.yml under ai.huggingface"))
            .run(OpenAiStreamingChatModel.builder()::apiKey)
            .baseUrl("https://router.huggingface.co/v1")
            .modelName(model)
            .logRequests(true)
            .logResponses(true)
            .build()

    // =========================================================================
    // HuggingFace runners
    // =========================================================================

    fun Project.runHuggingFaceChat(model: String) {
        createHuggingFaceChatModel(model = model)
            .run { PromptManager.userMessageFr.run(::chat).let(::println) }
    }

    fun Project.runHuggingFaceStreamChat(model: String) {
        runBlocking {
            createHuggingFaceStreamingChatModel(model).run {
                when (val answer = generateStreamingResponse(this, PromptManager.userMessageFr)) {
                    is Right -> "Complete response received:\n${answer.value.aiMessage().text()}".run(::println)
                    is Left -> "Error during response generation:\n${answer.value}".run(::println)
                }
            }
        }
    }

    // =========================================================================
    // HuggingFace task factories
    // =========================================================================

    fun Project.createHuggingFaceChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the HuggingFace $model chat prompt request."
            it.doFirst { project.runHuggingFaceChat(model) }
        }
    }

    fun Project.createHuggingFaceStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Display the HuggingFace $model streaming chat prompt request."
            it.doFirst { runHuggingFaceStreamChat(model) }
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

        val deckSystemPrompt = """
            | You are E-3PO, an expert in generating AsciiDoc/Reveal.js presentations for adult professional training.
            | You output ONLY raw AsciiDoc content — no markdown, no explanation, no wrapping code fences.
            |
            | === LEARN BY EXAMPLE ===
            | Below is a fully annotated deck. Each comment (// ...) explains the role of each block.
            | Reproduce this structure exactly for every deck you generate.
            |
            | ---------------- EXAMPLE START ----------------
            |
            | // MANDATORY HEADER: title, author, date, revealjs attributes
            | = Presentation Title
            | :author: First Last <email@example.com>
            | :date: 2024-01-01
            | :icons: font
            | :revealjs_theme: sky
            | :revealjs_history: true
            | :revealjs_slideNumber: c/t
            | :revealjs_controls: true
            | :revealjs_controlsLayout: edges
            | :revealjs_fragmentInURL: true
            | :revealjs_width: 1408
            | :revealjs_height: 792
            | :imagesdir: images
            | :source-highlighter: highlightjs
            | :example-caption!:
            |
            | // SLIDE 1: always an agenda slide with progressive reveal
            | // == creates a horizontal slide (left/right navigation)
            | == Agenda
            |
            | // [%step] reveals each bullet point one click at a time
            | [%step]
            | * First topic
            | * Second topic
            | * Third topic
            |
            | // [NOTE.speaker] : notes visible ONLY in speaker mode (press 's')
            | // Use for: timing hints, anecdotes, presenter tips
            | [NOTE.speaker]
            | --
            | Introduce the agenda in 2 minutes. Ask the audience what they already know.
            | --
            |
            | // [.notes] : page notes visible in PDF/HTML exports
            | // Use for: deeper content, references, suggested exercises
            | [.notes]
            | --
            | * Reference: Clean Code, Robert C. Martin
            | * Exercise: ask learners to list what they already know
            | --
            |
            | // SLIDE 2: standard content slide
            | == First Topic
            |
            | [%step]
            | * Key point 1
            | * Key point 2
            | * Key point 3
            |
            | [NOTE.speaker]
            | --
            | Emphasise key point 2 — often misunderstood. Allow 10 minutes.
            | --
            |
            | [.notes]
            | --
            | * Deep dive: link to official documentation
            | * Hands-on exercise: implement in pairs
            | --
            |
            | // VERTICAL SUB-SLIDE: === creates a child slide, reached by pressing the down arrow
            | // Use === to break down a complex concept across multiple screens
            | === First Topic — Detail
            |
            | // CODE BLOCK: always specify the language after [source,]
            | [source,kotlin]
            | .Minimal example
            | ----
            | fun greet(name: String): String = "Hello, ${'$'}name!"
            | ----
            |
            | [NOTE.speaker]
            | --
            | Live-code this example. Ask learners to modify it.
            | --
            |
            | [.notes]
            | --
            | * Kotlin idiom: expression function using = instead of { return }
            | * Exercise: rewrite in Java then compare
            | --
            |
            | // LAST SLIDE: always a synthesis and next steps
            | == Summary and Next Steps
            |
            | [%step]
            | * What you can now do
            | * What you can explore further
            | * Recommended resources
            |
            | [NOTE.speaker]
            | --
            | Open the floor: what was new? what is still unclear?
            | --
            |
            | [.notes]
            | --
            | * Formative assessment: 5-question quiz to distribute
            | * Resources: documentation links, books, tutorials
            | --
            |
            | ---------------- EXAMPLE END ----------------
            |
            | === ABSOLUTE RULES ===
            | - ALWAYS start with the full header including all :revealjs_* attributes
            | - ALWAYS include an agenda slide with [%step] as the first slide
            | - ALWAYS add [NOTE.speaker] on every slide (presenter tips)
            | - ALWAYS add [.notes] on every slide (deeper content + exercises)
            | - ALWAYS use [%step] for key point lists
            | - ALWAYS use [source,language] for any code block
            | - ALWAYS end with a summary + next steps slide
            | - Output language is specified in the user message — follow it strictly
            | - Output: raw AsciiDoc ONLY — zero markdown, zero explanation
        """.trimMargin()

        fun deckUserMessage(ctx: DeckContext) = """
            | Generate a complete AsciiDoc/Reveal.js training presentation with the following context:
            |
            | Subject      : ${ctx.subject}
            | Audience     : ${ctx.audience}
            | Duration     : ${ctx.duration} minutes
            | Output language: ${ctx.language}
            | Author       : ${ctx.author.name} <${ctx.author.email}>
            | Reveal.js theme    : ${ctx.revealjs.theme}
            | Reveal.js slideNumber: ${ctx.revealjs.slideNumber}
            | Reveal.js width    : ${ctx.revealjs.width}
            | Reveal.js height   : ${ctx.revealjs.height}
            |
            | Follow the system prompt structure strictly.
            | Output the complete deck in one single response.
        """.trimMargin()

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