@file:Suppress("unused")

package com.cheroliv.slider.scenarios

import com.cheroliv.slider.SliderManager.Configuration.yamlMapper
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.File

/**
 * Step definitions for the proposeDeckContext feature.
 *
 * ## Mock LLM strategy
 * proposeDeckContext runs inside a GradleTestKit subprocess — in-memory mocks
 * are not injectable. Instead, a minimal Ollama-compatible HTTP server is started
 * in the test JVM ([SliderWorld.startMockLlm]) and its URL is passed to GradleRunner
 * via `-Pollama.baseUrl=http://localhost:<port>`. LangChain4j's OllamaChatModel
 * then hits the mock instead of a real Ollama instance.
 */
class ProposeDeckContextSteps(private val world: SliderWorld) {

    // -------------------------------------------------------------------------
    // Mock LLM — valid DeckContext JSON response fixture
    // -------------------------------------------------------------------------

    /**
     * Returns a minimal valid DeckContext JSON for the given [subject].
     * The LLM mock always returns this — it is structurally valid and parseable
     * by Jackson into a [com.cheroliv.slider.DeckContext].
     */
    private fun validDeckContextJson(subject: String, language: String = "fr"): String {
        val slug = subject.lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return """
            {
              "subject": "$subject",
              "audience": "développeurs Kotlin intermédiaires",
              "duration": 60,
              "language": "$language",
              "outputFile": "${slug}_${language}-deck.adoc",
              "author": { "name": "cheroliv", "email": "cheroliv@example.com" },
              "revealjs": {
                "theme": "sky",
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
                {
                  "title": "Agenda",
                  "speakerHint": "Présenter le plan.",
                  "pageNotesHint": "Lister les prérequis."
                }
              ]
            }
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Given
    // -------------------------------------------------------------------------

    @Given("an Ollama instance is available")
    fun ensureOllamaAvailable() {
        world.ensureOllama()
    }

    @Given("a mock LLM that returns a valid DeckContext JSON for subject {string}")
    fun startMockLlmWithValidDeckContext(subject: String) {
        world.startPgVector()
        world.startMockLlm(validDeckContextJson(subject))
    }

    // -------------------------------------------------------------------------
    // When
    // -------------------------------------------------------------------------

    @When("I execute the task {string} without any properties")
    fun executeTaskWithoutProperties(taskName: String) = runBlocking {
        world.executeGradleExpectingFailure(taskName)
    }

    @When("I execute the task {string} with properties:")
    fun executeTaskWithProperties(taskName: String, table: DataTable) = runBlocking {
        val properties = table.asMap(String::class.java, String::class.java)
            .toMutableMap()

        // Inject mock server URL if running — forces Ollama provider
        world.mockServerPort?.let { port ->
            properties["ollama.baseUrl"] = "http://localhost:$port"
            properties.putIfAbsent("ai.provider", "ollama")
        }

        // Inject real Ollama URL if available (integration scenarios)
        world.ollamaBaseUrl?.let { url ->
            if (world.mockServerPort == null) {
                properties["ollama.baseUrl"] = url
                properties.putIfAbsent("ai.provider", "ollama")
            }
        }

        // Inject pgvector coordinates if container is running
        properties.putAll(world.pgProperties())

        world.executeGradle(taskName, properties = properties)
    }

    // -------------------------------------------------------------------------
    // Then — build outcome
    // -------------------------------------------------------------------------

    @Then("the build should fail")
    fun buildShouldFail() {
        assertThat(world.buildResult).isNotNull
    }

    @Then("the build output should contain {string}")
    fun buildOutputShouldContain(expected: String) {
        val output = world.buildResult?.output ?: ""
        assertThat(output).contains(expected)
    }

    // -------------------------------------------------------------------------
    // Then — file assertions
    // -------------------------------------------------------------------------

    @Then("the file {string} should exist")
    fun fileShouldExist(relativePath: String) {
        val file = world.projectDir!!.resolve(relativePath)
        assertThat(file)
            .describedAs("Expected file to exist: ${file.absolutePath}")
            .exists()
    }

    @Then("no file matching {string} should exist")
    fun noFileMatchingShouldExist(pattern: String) {
        val dir = world.projectDir!!.resolve(pattern.substringBefore("*").trimEnd('/'))
        val glob = pattern.substringAfterLast('/')
        val matches = dir.listFiles { f ->
            f.name.matches(glob.replace("*", ".*").toRegex())
        } ?: emptyArray()
        assertThat(matches)
            .describedAs("Expected no file matching $pattern but found: ${matches.map { it.name }}")
            .isEmpty()
    }

    // -------------------------------------------------------------------------
    // Then — DeckContext content assertions
    // -------------------------------------------------------------------------

    private fun loadDeckContext(relativePath: String): Map<String, Any?> {
        val file = world.projectDir!!.resolve(relativePath)
        @Suppress("UNCHECKED_CAST")
        return yamlMapper.readValue(file, Map::class.java) as Map<String, Any?>
    }

    private fun lastDeckContextFile(): File {
        val miscDir = world.projectDir!!.resolve("slides/misc")
        return miscDir.listFiles { f ->
            f.name.endsWith("-deck-context.yml")
        }?.maxByOrNull { it.lastModified() }
            ?: error("No *-deck-context.yml found in ${miscDir.absolutePath}")
    }

    @Then("the file {string} should be a valid DeckContext")
    fun fileShouldBeValidDeckContext(relativePath: String) {
        val ctx = loadDeckContext(relativePath)
        assertThat(ctx).containsKeys("subject", "language", "outputFile", "author")
        assertThat(ctx["subject"]).isNotNull().isInstanceOf(String::class.java)
        assertThat(ctx["outputFile"].toString()).endsWith("-deck.adoc")
    }

    @Then("the DeckContext field {string} should equal {string}")
    fun deckContextFieldShouldEqual(field: String, expected: String) {
        val ctx = loadDeckContext(lastDeckContextFile().relativeTo(world.projectDir!!).path)
        assertThat(ctx[field].toString()).isEqualTo(expected)
    }

    @Then("the DeckContext {string} should match the pattern {string}")
    fun deckContextFieldShouldMatchPattern(field: String, pattern: String) {
        val ctx = loadDeckContext(lastDeckContextFile().relativeTo(world.projectDir!!).path)
        val value = ctx[field].toString()
        // pattern "<slug>_<lang>-deck.adoc" → regex [a-z0-9-]+_[a-z]{2}-deck\.adoc
        val regex = Regex("[a-z0-9-]+_[a-z]{2}-deck\\.adoc")
        assertThat(value)
            .describedAs("outputFile '$value' should match pattern '$pattern'")
            .matches { regex.matches(it) }
    }

    @Then("the DeckContext author name should equal {string}")
    fun deckContextAuthorNameShouldEqual(expected: String) {
        val ctx = loadDeckContext(lastDeckContextFile().relativeTo(world.projectDir!!).path)
        @Suppress("UNCHECKED_CAST")
        val author = ctx["author"] as? Map<String, Any?>
        assertThat(author?.get("name").toString()).isEqualTo(expected)
    }

    @Then("the DeckContext author email should equal {string}")
    fun deckContextAuthorEmailShouldEqual(expected: String) {
        val ctx = loadDeckContext(lastDeckContextFile().relativeTo(world.projectDir!!).path)
        @Suppress("UNCHECKED_CAST")
        val author = ctx["author"] as? Map<String, Any?>
        assertThat(author?.get("email").toString()).isEqualTo(expected)
    }
}