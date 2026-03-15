@file:Suppress("MemberVisibilityCanBePrivate")

package com.cheroliv.slider.ai

import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.cheroliv.slider.DeckContext
import com.cheroliv.slider.SliderManager.Configuration.localConf
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel
import dev.langchain4j.model.mistralai.MistralAiChatModel
import dev.langchain4j.model.mistralai.MistralAiChatModelName.MISTRAL_SMALL_LATEST
import dev.langchain4j.model.mistralai.MistralAiChatModelName.OPEN_MISTRAL_NEMO
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildServiceSpec
import java.time.Duration.ofSeconds
import kotlin.coroutines.resume

/**
 * Central AI orchestrator for the Slider Gradle plugin.
 *
 * Responsibilities:
 * - Provider selection via `-Pai.provider`
 * - Model factory methods (ChatModel + StreamingChatModel) for each provider
 * - Task registration: hello* smoke-test tasks + RAG pipeline tasks
 * - [PromptManager]: system and user prompts for both pipeline steps
 *
 * ## Provider selection
 * Pass `-Pai.provider=ollama|gemini|mistral|huggingface` on the command line.
 * Defaults to `ollama` when absent.
 *
 * ## Two-step RAG pipeline
 *   1. `proposeDeckContext` → [ProposeDeckContextTask]
 *   2. `generateDeck`       → [GenerateDeckTask]
 *
 * Both tasks share the same model instance resolved by [resolveModel].
 */
object AssistantManager {

    const val GEMINI_2_5_FLASH = "gemini-2.5-flash"

    // =========================================================================
    // Provider selection
    // =========================================================================

    const val PROP_AI_PROVIDER     = "ai.provider"
    const val PROVIDER_OLLAMA      = "ollama"
    const val PROVIDER_GEMINI      = "gemini"
    const val PROVIDER_MISTRAL     = "mistral"
    const val PROVIDER_HUGGINGFACE = "huggingface"

    /** Reads `-Pai.provider`, defaulting to `"ollama"` when absent or blank. */
    val Project.aiProvider: String
        get() = (findProperty(PROP_AI_PROVIDER) as? String
            ?: PROVIDER_OLLAMA).lowercase().trim()

    // =========================================================================
    // Model catalogs
    // =========================================================================

    val localModels
        get() = setOf(
            "smollm:135m"                    to "SmollM",
            "llama3.2:3b-instruct-q8_0"      to "LlamaTiny",
            "smollm:135m-instruct-v0.2-q8_0" to "SmollMInstruct",
            "gemma3:1b-it-fp16"              to "Gemma3Instruct",
        )

    val geminiModels
        get() = setOf(GEMINI_2_5_FLASH to "GeminiFlash25")

    val mistralModels
        get() = setOf(
            MISTRAL_SMALL_LATEST.toString() to "MistralSmall",
            OPEN_MISTRAL_NEMO.toString()    to "MistralNemo",
        )

    val huggingFaceModels
        get() = setOf(
            "meta-llama/Llama-3.1-8B-Instruct:sambanova" to "Llama31Sambanova",
            "Qwen/Qwen3.5-35B-A3B:novita"                to "Qwen35Novita",
        )

    // =========================================================================
    // Task registration
    // =========================================================================

    fun Project.createChatTasks() {
        val pgServiceProvider = gradle.sharedServices.registerIfAbsent(
            "pgVectorService", PgVectorService::class.java
        ) { spec: BuildServiceSpec<PgVectorService.Params> ->
            spec.parameters.image.convention(PgVectorService.DEFAULT_IMAGE)
            spec.parameters.database.convention(PgVectorService.DEFAULT_DATABASE)
            spec.parameters.user.convention(PgVectorService.DEFAULT_USER)
            spec.parameters.password.convention(PgVectorService.DEFAULT_PASSWORD)
            spec.parameters.table.convention(PgVectorService.DEFAULT_TABLE)
            spec.parameters.startupTimeout.convention(PgVectorService.DEFAULT_TIMEOUT)
            // If -Ppgvector.port is provided, use external pgvector (e.g. Testcontainers)
            (project.findProperty("pgvector.port") as? String)?.toIntOrNull()?.let {
                spec.parameters.externalPort.set(it)
            }
            spec.maxParallelUsages.set(1)
        }

        localModels.forEach {
            registerHelloChatTask(it.first, "helloOllama${it.second}")
            registerHelloStreamingChatTask(it.first, "helloOllamaStream${it.second}")
        }
        geminiModels.forEach {
            registerHelloGeminiChatTask(it.first, "helloGemini${it.second}")
            registerHelloGeminiStreamingChatTask(it.first, "helloGeminiStream${it.second}")
        }
        mistralModels.forEach {
            registerHelloMistralChatTask(it.first, "helloMistral${it.second}")
            registerHelloMistralStreamingChatTask(it.first, "helloMistralStream${it.second}")
        }
        huggingFaceModels.forEach {
            registerHelloHuggingFaceChatTask(it.first, "helloHuggingFace${it.second}")
            registerHelloHuggingFaceStreamingChatTask(it.first, "helloHuggingFaceStream${it.second}")
        }

        registerReindexRagTask(pgServiceProvider)
        registerProposeDeckContextTask(pgServiceProvider)
        registerGenerateDeckTask(pgServiceProvider)
    }

    // =========================================================================
    // RAG task registration
    // =========================================================================

    private fun Project.registerReindexRagTask(
        pgServiceProvider: Provider<PgVectorService>
    ) {
        tasks.register("reindexRag", ReindexRagTask::class.java) {
            it.group = "slider-ai"
            it.description = "Force a full rebuild of the RAG embedding index."
            it.pgVectorService.set(pgServiceProvider)
            it.usesService(pgServiceProvider)
        }
    }

    private fun Project.registerProposeDeckContextTask(
        pgServiceProvider: Provider<PgVectorService>
    ) {
        tasks.register("proposeDeckContext", ProposeDeckContextTask::class.java) {
            it.group = "slider-ai"
            it.description = buildString {
                append("Propose a deck-context.yml for a given subject using RAG + LLM (step 1/2). ")
                append("Required: -Psubject=<text>. ")
                append("Optional: -Poutput=<path>, -Pai.provider=ollama|gemini|mistral|huggingface.")
            }
            it.pgVectorService.set(pgServiceProvider)
            it.usesService(pgServiceProvider)
        }
    }

    private fun Project.registerGenerateDeckTask(
        pgServiceProvider: Provider<PgVectorService>
    ) {
        tasks.register("generateDeck", GenerateDeckTask::class.java) {
            it.group = "slider-ai"
            it.description = buildString {
                append("Generate a complete AsciiDoc/Reveal.js deck from a *-deck-context.yml (step 2/2). ")
                append("Required: -Pdeck.context=<path>. ")
                append("Optional: -Pai.provider=ollama|gemini|mistral|huggingface (default: ollama).")
            }
            it.pgVectorService.set(pgServiceProvider)
            it.usesService(pgServiceProvider)
        }
    }

    // =========================================================================
    // Model resolution — single model per execution
    // =========================================================================

    /**
     * Resolves the [ChatModel] for the given [provider].
     *
     * Single resolution point used by both [ProposeDeckContextTask] and
     * [GenerateDeckTask], enforcing the one-model-per-execution constraint.
     */
    fun Project.resolveModel(provider: String): ChatModel =
        when (provider) {
            PROVIDER_GEMINI      -> createGeminiChatModel()
            PROVIDER_MISTRAL     -> createMistralChatModel()
            PROVIDER_HUGGINGFACE -> createHuggingFaceChatModel()
            else -> {
                if (provider != PROVIDER_OLLAMA) println(
                    "⚠️  Unknown ai.provider='$provider'. " +
                            "Valid: $PROVIDER_OLLAMA, $PROVIDER_GEMINI, " +
                            "$PROVIDER_MISTRAL, $PROVIDER_HUGGINGFACE. " +
                            "Falling back to $PROVIDER_OLLAMA."
                )
                createOllamaChatModel(localModels.first().first)
            }
        }

    // =========================================================================
    // Streaming coroutine bridge
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
        model: String = GEMINI_2_5_FLASH
    ): GoogleAiGeminiChatModel =
        (localConf.ai?.gemini?.firstOrNull()
            ?: error("No Gemini API key found in slides-context.yml under ai.gemini"))
            .run(GoogleAiGeminiChatModel.builder()::apiKey)
            .modelName(model)
            .temperature(1.0)
            .logRequestsAndResponses(true)
            .build()

    fun Project.createGeminiStreamingChatModel(
        model: String = GEMINI_2_5_FLASH
    ): GoogleAiGeminiStreamingChatModel =
        (localConf.ai?.gemini?.firstOrNull()
            ?: error("No Gemini API key found in slides-context.yml under ai.gemini"))
            .run(GoogleAiGeminiStreamingChatModel.builder()::apiKey)
            .modelName(model)
            .temperature(1.0)
            .logRequestsAndResponses(true)
            .build()

    // =========================================================================
    // Mistral model factories
    // =========================================================================

    fun Project.createMistralChatModel(
        model: String = MISTRAL_SMALL_LATEST.toString()
    ): MistralAiChatModel =
        (localConf.ai?.mistral?.firstOrNull()
            ?: error("No Mistral API key found in slides-context.yml under ai.mistral"))
            .run(MistralAiChatModel.builder()::apiKey)
            .modelName(model)
            .logRequests(true)
            .logResponses(true)
            .build()

    fun Project.createMistralStreamingChatModel(
        model: String = MISTRAL_SMALL_LATEST.toString()
    ): MistralAiStreamingChatModel =
        (localConf.ai?.mistral?.firstOrNull()
            ?: error("No Mistral API key found in slides-context.yml under ai.mistral"))
            .run(MistralAiStreamingChatModel.builder()::apiKey)
            .modelName(model)
            .logRequests(true)
            .logResponses(true)
            .build()

    // =========================================================================
    // HuggingFace model factories
    // =========================================================================

    fun Project.createHuggingFaceChatModel(
        model: String = "meta-llama/Llama-3.1-8B-Instruct:sambanova"
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
        model: String = "meta-llama/Llama-3.1-8B-Instruct:sambanova"
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
    // Hello task runners (smoke tests — send a bare "Hello" to each provider)
    // =========================================================================

    private fun Project.runOllamaChat(model: String) =
        createOllamaChatModel(model).chat("Hello").let(::println)

    private fun Project.runOllamaStreamChat(model: String) = runBlocking {
        when (val r = generateStreamingResponse(createOllamaStreamingChatModel(model), "Hello")) {
            is Right -> println(r.value.aiMessage().text())
            is Left  -> println("Error: ${r.value}")
        }
    }

    private fun Project.runGeminiChat(model: String) =
        createGeminiChatModel(model).chat("Hello").let(::println)

    private fun Project.runGeminiStreamChat(model: String) = runBlocking {
        when (val r = generateStreamingResponse(createGeminiStreamingChatModel(model), "Hello")) {
            is Right -> println(r.value.aiMessage().text())
            is Left  -> println("Error: ${r.value}")
        }
    }

    private fun Project.runMistralChat(model: String) =
        createMistralChatModel(model).chat("Hello").let(::println)

    private fun Project.runMistralStreamChat(model: String) = runBlocking {
        when (val r = generateStreamingResponse(createMistralStreamingChatModel(model), "Hello")) {
            is Right -> println(r.value.aiMessage().text())
            is Left  -> println("Error: ${r.value}")
        }
    }

    private fun Project.runHuggingFaceChat(model: String) =
        createHuggingFaceChatModel(model).chat("Hello").let(::println)

    private fun Project.runHuggingFaceStreamChat(model: String) = runBlocking {
        when (val r = generateStreamingResponse(createHuggingFaceStreamingChatModel(model), "Hello")) {
            is Right -> println(r.value.aiMessage().text())
            is Left  -> println("Error: ${r.value}")
        }
    }

    // =========================================================================
    // Hello task factories
    // =========================================================================

    private fun Project.registerHelloChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Ollama $model — smoke test."
            it.doFirst { project.runOllamaChat(model) }
        }
    }

    private fun Project.registerHelloStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Ollama $model — streaming smoke test."
            it.doFirst { runOllamaStreamChat(model) }
        }
    }

    private fun Project.registerHelloGeminiChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Gemini $model — smoke test."
            it.doFirst { project.runGeminiChat(model) }
        }
    }

    private fun Project.registerHelloGeminiStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Gemini $model — streaming smoke test."
            it.doFirst { runGeminiStreamChat(model) }
        }
    }

    private fun Project.registerHelloMistralChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Mistral $model — smoke test."
            it.doFirst { project.runMistralChat(model) }
        }
    }

    private fun Project.registerHelloMistralStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Mistral $model — streaming smoke test."
            it.doFirst { runMistralStreamChat(model) }
        }
    }

    private fun Project.registerHelloHuggingFaceChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "HuggingFace $model — smoke test."
            it.doFirst { project.runHuggingFaceChat(model) }
        }
    }

    private fun Project.registerHelloHuggingFaceStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "HuggingFace $model — streaming smoke test."
            it.doFirst { runHuggingFaceStreamChat(model) }
        }
    }

    // =========================================================================
    // PromptManager
    // =========================================================================

    object PromptManager {

        // ---------------------------------------------------------------------
        // Step 1 — DeckContext proposal
        // ---------------------------------------------------------------------

        val contextSystemPrompt = """
You are E-3PO, an expert instructional designer for adult technical training.

Your task is to propose a structured DeckContext for a slide deck.

## OUTPUT CONTRACT
Return ONLY a valid JSON object matching this exact structure — no markdown fences,
no explanation, no preamble:

{
  "subject": "string",
  "audience": "string",
  "duration": <integer minutes>,
  "language": "string",
  "outputFile": "string (kebab-case, ends with -deck_<lang>.adoc where <lang> is the ISO 639-1 language code, e.g. kotlin-coroutines-deck_fr.adoc)",
  "author": { "name": "string", "email": "string" },
  "revealjs": {
    "theme": "string",
    "slideNumber": "c/t",
    "width": 1408,
    "height": 792,
    "controls": true,
    "controlsLayout": "edges",
    "history": true,
    "fragmentInURL": true
  },
  "notes": {
    "speakerNotes": true,
    "pageNotes": true,
    "pageNotesStyle": "DETAILED"
  },
  "slides": [
    { "title": "string", "speakerHint": "string", "pageNotesHint": "string" }
  ]
}

## SLIDE PLANNING RULES
- First slide after title: always an Agenda slide
- Last slide: always a Summary/Conclusion slide
- Duration guideline: ~2 minutes per content slide
- Adapt depth and number of slides to the audience level
- speakerHint: what the presenter should say/demonstrate on that slide
- pageNotesHint: references, exercises or deeper content for learners
- outputFile: must follow the pattern <subject-slug>-deck_<lang>.adoc (e.g. kotlin-coroutines-deck_fr.adoc, spring-boot-intro-deck_en.adoc)
""".trimIndent()

        fun contextUserMessage(
            subject: String,
            language: String,
            authorName: String,
            authorEmail: String,
            ragContext: String,
        ): String = buildString {
            appendLine("Propose a complete DeckContext JSON for the following subject:")
            appendLine()
            appendLine("Subject : $subject")
            appendLine("Language: $language")
            appendLine("The outputFile MUST follow the pattern: <subject-slug>_${language}-deck.adoc")
            appendLine("Author name : $authorName")
            appendLine("Author email: $authorEmail")
            appendLine()
            if (ragContext.isNotEmpty()) {
                appendLine("## Relevant examples from the project (use as structural reference)")
                appendLine()
                appendLine(ragContext)
                appendLine()
            }
            appendLine("Return ONLY the JSON object. No explanation, no markdown fences.")
        }

        // ---------------------------------------------------------------------
        // Step 2 — Deck generation
        // ---------------------------------------------------------------------

        val deckSystemPrompt = """
You are E-3PO, an expert at generating AsciiDoc/Reveal.js slide decks.

## OUTPUT CONTRACT
Output raw AsciiDoc ONLY — no markdown, no explanations, no code fences around the deck.

## REQUIRED HEADER
= <subject>
<author name> <<author email>>
:revealjs_theme: <theme>
:revealjs_slideNumber: <slideNumber>
:revealjs_width: <width>
:revealjs_height: <height>
:revealjs_controls: true
:revealjs_controlsLayout: edges
:revealjs_history: true
:revealjs_fragmentInURL: true
:source-highlighter: rouge

## SLIDE STRUCTURE
Each slide: == Slide Title
Speaker notes (if speakerNotes=true): [NOTE.speaker] -- … --
Page notes   (if pageNotes=true):     [.notes] -- … --

pageNotesStyle controls [.notes] depth:
  MINIMAL        → one reference line
  DETAILED       → deep content + references + exercises
  EXERCISES_ONLY → practical exercises only

## SIZE CONSTRAINTS
- With [%step]    : max 5 bullet points per slide
- Without [%step] : max 7 bullet points per slide
- Code block      : max 10 lines per [source,...] block
- Prose           : max 3 sentences per slide
- NEVER mix a bullet list and a code block on the same slide

## ABSOLUTE RULES
1. Every slide must have a == heading
2. First slide after title: Agenda/Plan
3. Last slide: Summary/Conclusion
4. Follow slide hints order and titles exactly when provided
5. Content language must match the `language` field
""".trimIndent()

        fun deckUserMessage(ctx: DeckContext, ragContext: String): String = buildString {
            appendLine("Generate a complete Reveal.js slide deck with the following context:")
            appendLine()
            appendLine("Subject   : ${ctx.subject}")
            appendLine("Audience  : ${ctx.audience}")
            appendLine("Duration  : ${ctx.duration} minutes")
            appendLine("Language  : ${ctx.language}")
            appendLine("OutputFile: ${ctx.outputFile}")
            appendLine()
            appendLine("Author:")
            appendLine("  Name  : ${ctx.author.name}")
            appendLine("  Email : ${ctx.author.email}")
            appendLine()
            appendLine("Reveal.js configuration:")
            appendLine("  theme        : ${ctx.revealjs.theme}")
            appendLine("  slideNumber  : ${ctx.revealjs.slideNumber}")
            appendLine("  width        : ${ctx.revealjs.width}")
            appendLine("  height       : ${ctx.revealjs.height}")
            appendLine()
            appendLine("Notes configuration:")
            appendLine("  speakerNotes   : ${ctx.notes.speakerNotes}")
            appendLine("  pageNotes      : ${ctx.notes.pageNotes}")
            appendLine("  pageNotesStyle : ${ctx.notes.pageNotesStyle}")
            appendLine()
            if (ctx.slides.isEmpty())
                appendLine("No slide hints provided — build a pedagogically appropriate structure.")
            else {
                appendLine("Slide hints (follow this order and these titles exactly):")
                ctx.slides.forEach { hint ->
                    appendLine("  - title: ${hint.title}")
                    hint.speakerHint?.let  { appendLine("    speakerHint: $it") }
                    hint.pageNotesHint?.let { appendLine("    pageNotesHint: $it") }
                }
            }
            if (ragContext.isNotEmpty()) {
                appendLine()
                appendLine("## AsciiDoc syntax reference examples (from project — use as style guide)")
                appendLine()
                appendLine(ragContext)
            }
        }
    }
}