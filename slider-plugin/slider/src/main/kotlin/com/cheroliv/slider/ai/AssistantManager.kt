@file:Suppress("MemberVisibilityCanBePrivate")

package com.cheroliv.slider.ai

import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.Either.Left
import arrow.core.Either.Right
import com.cheroliv.slider.DeckContext
import com.cheroliv.slider.SliderManager.localConf
import com.cheroliv.slider.SliderManager.yamlMapper
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
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
import java.io.File
import java.time.Duration.ofSeconds
import kotlin.coroutines.resume


object AssistantManager {
    const val GEMINI_2_5_FLASH = "gemini-2.5-flash"

    // =========================================================================
    // Provider selection — -Pai.provider=ollama|gemini|mistral|huggingface
    // =========================================================================

    /**
     * Gradle property key to select the AI provider at the command line.
     *
     * Usage:
     *   ./gradlew generateDeck -Pai.provider=gemini -Pdeck.context=slides/misc/my.yml
     *   ./gradlew generateDeck -Pai.provider=mistral -Pdeck.context=slides/misc/my.yml
     *   ./gradlew generateDeck                       -Pdeck.context=slides/misc/my.yml
     *   (no -Pai.provider → defaults to ollama)
     */
    const val PROP_AI_PROVIDER     = "ai.provider"
    const val PROVIDER_OLLAMA      = "ollama"
    const val PROVIDER_GEMINI      = "gemini"
    const val PROVIDER_MISTRAL     = "mistral"
    const val PROVIDER_HUGGINGFACE = "huggingface"

    /** Reads -Pai.provider, defaulting to "ollama" when absent or blank. */
    val Project.aiProvider: String
        get() = (findProperty(PROP_AI_PROVIDER) as? String
            ?: PROVIDER_OLLAMA).lowercase().trim()

    // =========================================================================
    // Misc
    // =========================================================================

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
            "smollm:135m"                      to "SmollM",
            "llama3.2:3b-instruct-q8_0"        to "LlamaTiny",
            "smollm:135m-instruct-v0.2-q8_0"   to "SmollMInstruct",
            "gemma3:1b-it-fp16"                to "Gemma3Instruct",
        )

    @JvmStatic
    val geminiModels
        get() = setOf(GEMINI_2_5_FLASH to "GeminiFlash25")

    @JvmStatic
    val mistralModels
        get() = setOf(
            MISTRAL_SMALL_LATEST.toString() to "MistralSmall",
            OPEN_MISTRAL_NEMO.toString()    to "MistralNemo",
        )

    @JvmStatic
    val huggingFaceModels
        get() = setOf(
            "meta-llama/Llama-3.1-8B-Instruct:sambanova" to "Llama31Sambanova",
            "Qwen/Qwen3.5-35B-A3B:novita"                to "Qwen35Novita",
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
        // Mistral AI
        mistralModels.forEach {
            createMistralChatTask(it.first, "helloMistral${it.second}")
            createMistralStreamingChatTask(it.first, "helloMistralStream${it.second}")
        }
        // HuggingFace — via OpenAI-compatible router
        huggingFaceModels.forEach {
            createHuggingFaceChatTask(it.first, "helloHuggingFace${it.second}")
            createHuggingFaceStreamingChatTask(it.first, "helloHuggingFaceStream${it.second}")
        }
        // generateDeck — provider resolved at runtime via -Pai.provider
        registerGenerateDeckTask()
    }

    // =========================================================================
    // generateDeck task
    // =========================================================================

    /**
     * Registers the generateDeck task.
     *
     * Required properties:
     *   -Pdeck.context=<path>     Path to the *-deck-context.yml file
     *
     * Optional properties:
     *   -Pai.provider=<provider>  ollama (default) | gemini | mistral | huggingface
     *
     * Examples:
     *   ./gradlew generateDeck -Pdeck.context=slides/misc/kotlin-intro-deck-context.yml
     *   ./gradlew generateDeck -Pdeck.context=slides/misc/kotlin-intro-deck-context.yml -Pai.provider=gemini
     *   ./gradlew generateDeck -Pdeck.context=slides/misc/kotlin-intro-deck-context.yml -Pai.provider=mistral
     */
    private fun Project.registerGenerateDeckTask() {
        tasks.register("generateDeck") {
            it.group = "slider-ai"
            it.description = buildString {
                append("Generate a complete AsciiDoc/Reveal.js deck from a *-deck-context.yml. ")
                append("Provider: -Pai.provider=ollama|gemini|mistral|huggingface (default: ollama). ")
                append("Context : -Pdeck.context=<path>.")
            }
            it.doFirst { project.runGenerateDeck() }
        }
    }

    private fun Project.runGenerateDeck() {
        val contextPath = findProperty("deck.context") as? String
            ?: error(
                "Missing required property -Pdeck.context.\n" +
                        "Usage: ./gradlew generateDeck -Pdeck.context=slides/misc/my-deck-context.yml"
            )

        val ctx: DeckContext = contextPath
            .let(::File)
            .also { require(it.exists()) { "Deck context file not found: $contextPath" } }
            .let { yamlMapper.readValue(it, DeckContext::class.java) }

        val provider = aiProvider
        println("🤖 generateDeck — provider: $provider — context: $contextPath")

        val model: ChatModel = resolveGenerateDeckModel(provider)

        val systemMsg = SystemMessage.from(PromptManager.deckSystemPrompt)
        val userMsg   = UserMessage.from(PromptManager.deckUserMessage(ctx))
        val adocContent = model.chat(listOf(systemMsg, userMsg)).aiMessage().text()

        val outputFile = layout.projectDirectory.asFile
            .resolve("slides/misc")
            .resolve(ctx.outputFile)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(adocContent)

        println("✅ Deck generated → ${outputFile.absolutePath}")
    }

    /**
     * Resolves the ChatModel for generateDeck based on the value of -Pai.provider.
     *
     * Provider → default model used when none is specified explicitly:
     *   ollama      → first entry in localModels  (smollm:135m)
     *   gemini      → gemini-2.5-flash
     *   mistral     → MISTRAL_SMALL_LATEST
     *   huggingface → Llama-3.1-8B-Instruct:sambanova
     *
     * Unknown values fall back to ollama with a warning.
     */
    private fun Project.resolveGenerateDeckModel(provider: String): ChatModel =
        when (provider) {
            PROVIDER_GEMINI      -> createGeminiChatModel()
            PROVIDER_MISTRAL     -> createMistralChatModel()
            PROVIDER_HUGGINGFACE -> createHuggingFaceChatModel()
            else -> {
                if (provider != PROVIDER_OLLAMA) println(
                    "⚠️  Unknown ai.provider='$provider'. " +
                            "Valid values: $PROVIDER_OLLAMA, $PROVIDER_GEMINI, " +
                            "$PROVIDER_MISTRAL, $PROVIDER_HUGGINGFACE. " +
                            "Falling back to $PROVIDER_OLLAMA."
                )
                createOllamaChatModel(localModels.first().first)
            }
        }

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
    // Mistral AI model factories
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
    // HuggingFace model factories (via OpenAI-compatible router)
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
                    is Left  -> "Error during response generation:\n${answer.value}".run(::println)
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
                    is Left  -> "Error during response generation:\n${answer.value}".run(::println)
                }
            }
        }
    }

    // =========================================================================
    // Mistral runners
    // =========================================================================

    fun Project.runMistralChat(model: String) {
        createMistralChatModel(model = model)
            .run { PromptManager.userMessageFr.run(::chat).let(::println) }
    }

    fun Project.runMistralStreamChat(model: String) {
        runBlocking {
            createMistralStreamingChatModel(model).run {
                when (val answer = generateStreamingResponse(this, PromptManager.userMessageFr)) {
                    is Right -> "Complete response received:\n${answer.value.aiMessage().text()}".run(::println)
                    is Left  -> "Error during response generation:\n${answer.value}".run(::println)
                }
            }
        }
    }

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
                    is Left  -> "Error during response generation:\n${answer.value}".run(::println)
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
            it.description = "Ollama $model chat prompt."
            it.doFirst { project.runChat(model) }
        }
    }

    fun Project.createStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Ollama $model streaming chat prompt."
            it.doFirst { runStreamChat(model) }
        }
    }

    // =========================================================================
    // Gemini task factories
    // =========================================================================

    fun Project.createGeminiChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Gemini $model chat prompt."
            it.doFirst { project.runGeminiChat(model) }
        }
    }

    fun Project.createGeminiStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Gemini $model streaming chat prompt."
            it.doFirst { runGeminiStreamChat(model) }
        }
    }

    // =========================================================================
    // Mistral task factories
    // =========================================================================

    fun Project.createMistralChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Mistral $model chat prompt."
            it.doFirst { project.runMistralChat(model) }
        }
    }

    fun Project.createMistralStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "Mistral $model streaming chat prompt."
            it.doFirst { runMistralStreamChat(model) }
        }
    }

    // =========================================================================
    // HuggingFace task factories
    // =========================================================================

    fun Project.createHuggingFaceChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "HuggingFace $model chat prompt."
            it.doFirst { project.runHuggingFaceChat(model) }
        }
    }

    fun Project.createHuggingFaceStreamingChatTask(model: String, taskName: String) {
        tasks.register(taskName) {
            it.group = "slider-ai"
            it.description = "HuggingFace $model streaming chat prompt."
            it.doFirst { runHuggingFaceStreamChat(model) }
        }
    }

    // =========================================================================
    // PromptManager
    // =========================================================================

    object PromptManager {

        @JvmStatic
        fun main(args: Array<String>) { userMessageFr.run(::println) }

        const val ASSISTANT_NAME = "E-3PO"
        val userName: String = System.getProperty("user.name")!!

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

        // ---------------------------------------------------------------------
        // Deck generation prompts
        // ---------------------------------------------------------------------

        val deckSystemPrompt = """
You are E-3PO, an expert at generating AsciiDoc/Reveal.js slide decks.

## OUTPUT FORMAT — strict AsciiDoc + Reveal.js syntax

Every deck must start with this exact header block (fill values from context):
```
= <title>
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
```

Each slide uses `==` as heading level:
```
== Slide Title

Content here

[NOTE.speaker]
--
Speaker notes here (if speakerNotes=true)
--

[.notes]
--
Page notes here (if pageNotes=true)
--
```

## INCREMENTAL BULLETS — [%step]
Use `[%step]` on a list to reveal items one by one:
```
[%step]
* First point
* Second point
```

## CODE BLOCKS
```
[source,kotlin]
----
fun hello() = println("Hello")
----
```

## SLIDE CONTENT SIZE CONSTRAINTS
* With `[%step]`   : maximum 5 bullet points per slide
* Without `[%step]`: maximum 7 bullet points per slide
* Code block       : maximum 10 lines per [source,...] block
* Prose paragraph  : maximum 3 sentences per slide
* NEVER mix a bullet list and a code block on the same slide

## NOTES GENERATION RULES
* speakerNotes=true   → generate a [NOTE.speaker] block on every slide
* pageNotes=true      → generate a [.notes] block on every slide
* pageNotesStyle controls the depth of [.notes]:
  - MINIMAL        → one reference line only
  - DETAILED       → deep content + references + exercises
  - EXERCISES_ONLY → practical exercises only
* SlideHint.speakerHint  guides [NOTE.speaker] content — do not copy it verbatim
* SlideHint.pageNotesHint guides [.notes] content      — do not copy it verbatim

## ABSOLUTE RULES
1. Output raw AsciiDoc ONLY — no markdown, no explanations, no code fences around the deck
2. Every slide must have a == heading
3. The first slide after the title must be an Agenda/Plan slide
4. The last slide must be a Summary/Conclusion slide
5. If slide hints are provided, follow their order and titles exactly
6. If no hints are provided, create a pedagogically sound structure for the subject
7. Language of content must match the `language` field in the context
""".trimIndent()

        fun deckUserMessage(ctx: DeckContext): String = buildString {
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
            if (ctx.slides.isEmpty()) {
                appendLine("No slide hints provided — build a pedagogically appropriate structure.")
            } else {
                appendLine("Slide hints (follow this order and these titles exactly):")
                ctx.slides.forEach { hint ->
                    appendLine("  - title: ${hint.title}")
                    hint.speakerHint?.let  { appendLine("    speakerHint: $it") }
                    hint.pageNotesHint?.let { appendLine("    pageNotesHint: $it") }
                }
            }
        }
    }
}