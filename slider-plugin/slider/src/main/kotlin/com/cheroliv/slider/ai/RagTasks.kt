package com.cheroliv.slider.ai

import com.cheroliv.slider.DeckContext
import com.cheroliv.slider.SliderManager.Configuration.localConf
import com.cheroliv.slider.SliderManager.Configuration.yamlMapper
import com.cheroliv.slider.ai.AssistantManager.aiProvider
import com.cheroliv.slider.ai.AssistantManager.resolveModel
import com.cheroliv.slider.ai.RagManager.retrieve
import com.cheroliv.slider.ai.RagManager.reindex
import com.fasterxml.jackson.module.kotlin.readValue
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import org.gradle.api.tasks.TaskAction
import java.io.File

// =============================================================================
// ReindexRagTask
// =============================================================================

/**
 * Gradle task: `reindexRag`
 *
 * Drops the entire pgvector embeddings table and re-indexes all source files
 * from scratch. Use after deleting or renaming source files.
 *
 * Usage:
 *   ./gradlew reindexRag
 */
abstract class ReindexRagTask : RagTask() {

    @TaskAction
    fun run() {
        val pgService = service()
        RagManager.run { project.reindex(pgService) }
    }
}

// =============================================================================
// ProposeDeckContextTask
// =============================================================================

/**
 * Gradle task: `proposeDeckContext`  (step 1/2)
 *
 * Uses RAG + the selected LLM to propose a [DeckContext] for a given subject.
 * Writes a `*-deck-context.yml` file for human review before generation.
 *
 * Required properties:
 *   `-Psubject=<text>`
 *
 * Optional properties:
 *   `-Poutput=<path>`              default: slides/misc/<slug>-deck-context.yml
 *   `-Pai.provider=<provider>`     ollama (default) | gemini | mistral | huggingface
 *
 * Usage:
 *   ./gradlew proposeDeckContext -Psubject="Kotlin coroutines" -Pai.provider=gemini
 */
abstract class ProposeDeckContextTask : RagTask() {

    @TaskAction
    fun run() {
        val pgService = service()

        val subject = project.findProperty("subject") as? String
            ?: error(
                "Missing required property -Psubject.\n" +
                        "Usage: ./gradlew proposeDeckContext -Psubject=\"Introduction to Kotlin\""
            )

        val outputPath = project.findProperty("output") as? String
            ?: "slides/misc/${subject.toSlug()}-deck-context.yml"

        val provider = project.aiProvider
        println("🔍 [1/2] proposeDeckContext — provider: $provider — subject: \"$subject\"")

        println("📚 [RAG] Retrieving relevant context chunks…")
        val ragContext = RagManager.run { project.retrieve(subject, pgService) }
        if (ragContext.isNotEmpty())
            println("📚 [RAG] ${ragContext.lines().size} lines of context retrieved.")
        else
            println("⚠️  [RAG] No relevant chunks found — proceeding without RAG context.")

        val model = project.resolveModel(provider)
        val systemMsg = SystemMessage.from(AssistantManager.PromptManager.contextSystemPrompt)
        val userMsg   = UserMessage.from(AssistantManager.PromptManager.contextUserMessage(subject, ragContext))

        println("🤖 [LLM] Proposing DeckContext…")
        val rawJson = model.chat(listOf(systemMsg, userMsg)).aiMessage().text()

        val ctx: DeckContext = runCatching {
            val clean = rawJson
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            yamlMapper.readValue<DeckContext>(clean)
        }.getOrElse { e ->
            error("LLM returned invalid JSON for DeckContext: ${e.message}\nRaw response:\n$rawJson")
        }

        val outputFile = project.layout.projectDirectory.asFile.resolve(outputPath)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(yamlMapper.writeValueAsString(ctx))

        println("✅ [1/2] DeckContext written → ${outputFile.absolutePath}")
        println("   Review and adjust the file, then run:")
        println("   ./gradlew generateDeck -Pdeck.context=$outputPath -Pai.provider=$provider")
    }

    private fun String.toSlug(): String =
        lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"),   "e")
            .replace(Regex("[ìíîï]"),   "i")
            .replace(Regex("[òóôõö]"),  "o")
            .replace(Regex("[ùúûü]"),   "u")
            .replace(Regex("[ç]"),      "c")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}

// =============================================================================
// GenerateDeckTask
// =============================================================================

/**
 * Gradle task: `generateDeck`  (step 2/2)
 *
 * Reads a validated `*-deck-context.yml` and generates a complete AsciiDoc
 * Reveal.js deck, RAG-augmented with AsciiDoc syntax examples from the project.
 *
 * Required properties:
 *   `-Pdeck.context=<path>`
 *
 * Optional properties:
 *   `-Pai.provider=<provider>`     ollama (default) | gemini | mistral | huggingface
 *
 * Usage:
 *   ./gradlew generateDeck -Pdeck.context=slides/misc/kotlin-coroutines-deck-context.yml
 *   ./gradlew generateDeck -Pdeck.context=slides/misc/kotlin-coroutines-deck-context.yml -Pai.provider=gemini
 */
abstract class GenerateDeckTask : RagTask() {

    @TaskAction
    fun run() {
        val pgService = service()

        val contextPath = project.findProperty("deck.context") as? String
            ?: error(
                "Missing required property -Pdeck.context.\n" +
                        "Usage: ./gradlew generateDeck -Pdeck.context=slides/misc/my-deck-context.yml"
            )

        val ctx: DeckContext = File(contextPath)
            .also { require(it.exists()) { "Deck context file not found: $contextPath" } }
            .let { yamlMapper.readValue(it, DeckContext::class.java) }

        val provider = project.aiProvider
        println("🎨 [2/2] generateDeck — provider: $provider — context: $contextPath")

        println("📚 [RAG] Retrieving AsciiDoc syntax examples…")
        val ragContext = RagManager.run {
            project.retrieve("AsciiDoc Reveal.js slide syntax ${ctx.subject}", pgService)
        }
        if (ragContext.isNotEmpty())
            println("📚 [RAG] ${ragContext.lines().size} lines of AsciiDoc examples retrieved.")
        else
            println("⚠️  [RAG] No relevant chunks found — relying on system prompt only.")

        val model = project.resolveModel(provider)
        val systemMsg = SystemMessage.from(AssistantManager.PromptManager.deckSystemPrompt)
        val userMsg   = UserMessage.from(AssistantManager.PromptManager.deckUserMessage(ctx, ragContext))

        println("🤖 [LLM] Generating deck…")
        val adocContent = model.chat(listOf(systemMsg, userMsg)).aiMessage().text()

        val outputFile = project.layout.projectDirectory.asFile
            .resolve("slides/misc")
            .resolve(ctx.outputFile)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(adocContent)

        println("✅ [2/2] Deck generated → ${outputFile.absolutePath}")
    }
}