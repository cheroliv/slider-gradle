package com.cheroliv.slider.scenarios

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner.create
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.io.File.createTempFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI

class SliderWorld {
    val log: Logger = getLogger(SliderWorld::class.java)

    val scope = CoroutineScope(Default + SupervisorJob())

    var projectDir: File? = null
    var buildResult: BuildResult? = null
    var exception: Throwable? = null

    private val asyncJobs = mutableListOf<Deferred<BuildResult>>()

    // -------------------------------------------------------------------------
    // pgvector container (Testcontainers)
    // -------------------------------------------------------------------------

    private var pgContainer: PostgreSQLContainer<*>? = null

    /**
     * Starts a pgvector container via Testcontainers.
     * Exposes [pgPort], [pgDatabase], [pgUser], [pgPassword] for use
     * as Gradle properties passed to the task under test.
     */
    fun startPgVector() {
        val container = PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("slider_rag")
            .withUsername("slider")
            .withPassword("slider")
        container.start()
        pgContainer = container
        log.info("pgvector container started on port ${container.firstMappedPort}")
    }

    fun stopPgVector() {
        pgContainer?.stop()
        pgContainer = null
    }

    val pgPort: Int? get() = pgContainer?.firstMappedPort
    val pgDatabase: String get() = pgContainer?.databaseName ?: "slider_rag"
    val pgUser: String get() = pgContainer?.username ?: "slider"
    val pgPassword: String get() = pgContainer?.password ?: "slider"

    /**
     * Returns Gradle properties to connect the task to the running pgvector container.
     * Inject these into [executeGradle] properties map.
     */
    fun pgProperties(): Map<String, String> = pgPort?.let {
        mapOf(
            "pgvector.host" to "localhost",
            "pgvector.port" to it.toString(),
            "pgvector.database" to pgDatabase,
            "pgvector.user" to pgUser,
            "pgvector.password" to pgPassword,
        )
    } ?: emptyMap()

    // -------------------------------------------------------------------------
    // Ollama — local detection + Testcontainers fallback
    // -------------------------------------------------------------------------

    private var ollamaContainer: GenericContainer<*>? = null

    /** Base URL of the Ollama instance available for this scenario. */
    var ollamaBaseUrl: String? = null
        private set

    /**
     * Ensures an Ollama instance is available:
     * - If Ollama is running locally on port 11434, uses it directly.
     * - Otherwise starts a Testcontainers Ollama container.
     *
     * Exposes [ollamaBaseUrl] for injection as -Pollama.baseUrl.
     */
    fun ensureOllama() {
        if (isOllamaLocal()) {
            ollamaBaseUrl = "http://localhost:11434"
            log.info("Ollama detected locally at $ollamaBaseUrl")
        } else {
            log.info("Ollama not found locally — starting Testcontainers Ollama...")
            val container = object : GenericContainer<Nothing>(
                DockerImageName.parse("ollama/ollama:latest")
            ) {}
            container.withExposedPorts(11434)
            container.waitingFor(Wait.forHttp("/api/tags").forStatusCode(200))
            container.start()
            ollamaContainer = container
            ollamaBaseUrl = "http://localhost:${container.firstMappedPort}"
            log.info("Ollama container started at $ollamaBaseUrl")
        }
    }

    private fun isOllamaLocal(): Boolean = runCatching {
        val conn = URI("http://localhost:11434/api/tags").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 1_000
        conn.requestMethod = "GET"
        conn.responseCode == 200
    }.getOrDefault(false)

    fun stopOllama() {
        ollamaContainer?.stop()
        ollamaContainer = null
        ollamaBaseUrl = null
    }

    // -------------------------------------------------------------------------
    // Mock LLM server (Ollama-compatible HTTP server)
    // -------------------------------------------------------------------------

    private var mockServer: HttpServer? = null

    /** Port assigned to the mock server, or null if not started. */
    var mockServerPort: Int? = null
        private set

    /**
     * Starts a minimal Ollama-compatible HTTP server that always returns
     * [responseBody] as the assistant message content.
     *
     * The server listens on a random available port, exposed via [mockServerPort].
     * It handles POST /api/chat as expected by LangChain4j's OllamaChatModel.
     *
     * Call [stopMockLlm] (or [cleanup]) to stop it after the scenario.
     */
    fun startMockLlm(responseBody: String) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val port = server.address.port
        mockServerPort = port

        val ollamaResponse = """
            {
              "model": "smollm:135m",
              "message": { "role": "assistant", "content": ${escapeJson(responseBody)} },
              "done": true
            }
        """.trimIndent().toByteArray()

        server.createContext("/api/chat") { exchange ->
            exchange.sendResponseHeaders(200, ollamaResponse.size.toLong())
            exchange.responseBody.use { it.write(ollamaResponse) }
        }
        server.executor = null
        server.start()
        mockServer = server
        log.info("Mock LLM server started on port $port")
    }

    fun stopMockLlm() {
        mockServer?.stop(0)
        mockServer = null
        mockServerPort = null
    }

    private fun escapeJson(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    // -------------------------------------------------------------------------
    // Gradle execution
    // -------------------------------------------------------------------------

    /**
     * Executes a Gradle task asynchronously with optional Gradle properties.
     * Properties are passed as -Pkey=value arguments.
     */
    fun executeGradleAsync(
        vararg tasks: String,
        properties: Map<String, String> = emptyMap(),
    ): Deferred<BuildResult> {
        require(projectDir != null) { "Project directory must be initialized" }
        val propArgs = properties.map { (k, v) -> "-P$k=$v" }
        val allArgs = tasks.toList() + propArgs + "--stacktrace"
        log.info("Starting async Gradle execution: $allArgs")
        return scope.async {
            try {
                create()
                    .withProjectDir(projectDir!!)
                    .withArguments(allArgs)
                    .withPluginClasspath()
                    .build()
            } catch (e: Exception) {
                log.error("Gradle build failed", e)
                exception = e
                throw e
            }
        }.also { asyncJobs.add(it) }
    }

    /**
     * Executes a Gradle task that is expected to fail.
     * Uses buildAndFail() instead of build().
     */
    fun executeGradleAsyncExpectingFailure(
        vararg tasks: String,
        properties: Map<String, String> = emptyMap(),
    ): Deferred<BuildResult> {
        require(projectDir != null) { "Project directory must be initialized" }
        val propArgs = properties.map { (k, v) -> "-P$k=$v" }
        val allArgs = tasks.toList() + propArgs + "--stacktrace"
        log.info("Starting async Gradle execution (expecting failure): $allArgs")
        return scope.async {
            try {
                create()
                    .withProjectDir(projectDir!!)
                    .withArguments(allArgs)
                    .withPluginClasspath()
                    .buildAndFail()
            } catch (e: Exception) {
                log.error("Unexpected exception during expected-failure build", e)
                exception = e
                throw e
            }
        }.also { asyncJobs.add(it) }
    }

    suspend fun executeGradle(
        vararg tasks: String,
        properties: Map<String, String> = emptyMap(),
    ): BuildResult = executeGradleAsync(*tasks, properties = properties)
        .await()
        .also { buildResult = it }

    suspend fun executeGradleExpectingFailure(
        vararg tasks: String,
        properties: Map<String, String> = emptyMap(),
    ): BuildResult = executeGradleAsyncExpectingFailure(*tasks, properties = properties)
        .await()
        .also { buildResult = it }

    suspend fun <T> withTimeout(seconds: Long, block: suspend () -> T): T =
        withTimeout(seconds * 1000) { block() }

    suspend fun awaitAll() {
        if (asyncJobs.isNotEmpty()) {
            log.info("Waiting for ${asyncJobs.size} async operations...")
            asyncJobs.awaitAll()
            log.info("All async operations completed")
        }
    }

    // -------------------------------------------------------------------------
    // Project creation
    // -------------------------------------------------------------------------

    fun createGradleProject(configFileName: String = "slides-context.yml"): File {
        val pluginId = "com.cheroliv.slider"
        val buildScriptContent = "slider { configPath = file(\"$configFileName\").absolutePath }"
        createTempFile("gradle-test-", "").apply {
            delete()
            mkdirs()
        }.run {
            resolve("settings.gradle.kts")
                .apply { createNewFile() }
                .writeText(
                    "pluginManagement.repositories.gradlePluginPortal()\n" +
                            "rootProject.name = \"${name}\""
                )
            resolve("build.gradle.kts")
                .apply { createNewFile() }
                .writeText("plugins { id(\"$pluginId\") }\n$buildScriptContent")
            projectDir = this
            return this
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Suppress("unused")
    fun cleanup() {
        stopMockLlm()
        stopPgVector()
        stopOllama()
        scope.cancel()
        projectDir?.deleteRecursively()
        projectDir = null
        buildResult = null
        exception = null
        asyncJobs.clear()
    }
}