package com.cheroliv.slider.ai

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerResponse
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.Duration

/**
 * Gradle [BuildService] that manages the lifecycle of a pgvector Docker container.
 *
 * Gradle instantiates this service before any task that declares it as a
 * requirement, and calls [close] automatically at the end of the build —
 * the container is always stopped and removed cleanly regardless of success
 * or failure.
 *
 * ## Why a BuildService and not a task?
 * A BuildService has a build-scoped lifecycle, not a task-scoped one. This
 * means the container starts once, stays up for the entire build (covering
 * both [proposeDeckContext] and [generateDeck] when chained), and is torn
 * down exactly once at the end — even when multiple tasks share it.
 *
 * ## Port assignment
 * The container binds pgvector's internal port 5432 to a **randomly assigned
 * host port** (binding `0` on the host side). The actual port is retrieved
 * from the Docker API after the container starts and exposed via [port].
 * No hardcoded port, no collision with existing PostgreSQL services.
 *
 * ## CI
 * In GitHub Actions the runner already has Docker available. The service
 * starts the container the same way as locally — no special CI configuration
 * needed, no `services:` block required in the workflow.
 *
 * ## Parameters (all optional — defaults match docker-compose.yml)
 *   | Parameter    | Default           |
 *   |--------------|-------------------|
 *   | image        | pgvector/pgvector:pg17 |
 *   | database     | slider_rag        |
 *   | user         | slider            |
 *   | password     | slider            |
 *   | table        | embeddings        |
 *   | startupTimeout | 30 seconds      |
 *
 * ## Required dependency in slider-plugin/build.gradle.kts
 * ```kotlin
 * implementation("com.github.docker-java:docker-java-core:3.3.6")
 * implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")
 * ```
 */
abstract class PgVectorService : BuildService<PgVectorService.Params>, AutoCloseable {

    // -------------------------------------------------------------------------
    // Parameters
    // -------------------------------------------------------------------------

    interface Params : BuildServiceParameters {
        val image:          Property<String>
        val database:       Property<String>
        val user:           Property<String>
        val password:       Property<String>
        val table:          Property<String>
        val startupTimeout: Property<Long>   // seconds
        /**
         * If set, PgVectorService skips Docker entirely and connects to this
         * external port on localhost. Used in tests via Testcontainers.
         * Set via Gradle property -Ppgvector.port=<port>.
         */
        val externalPort:   Property<Int>
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private lateinit var docker: DockerClient
    private lateinit var containerId: String

    /** The host port dynamically assigned to pgvector's internal port 5432. */
    var port: Int = -1
        private set

    val database: String get() = parameters.database.get()
    val user:     String get() = parameters.user.get()
    val password: String get() = parameters.password.get()
    val table:    String get() = parameters.table.get()

    /** Whether [start] has already been called. */
    @Volatile private var started = false

    // -------------------------------------------------------------------------
    // Explicit start — called by each RAG task action, not by Gradle init
    // -------------------------------------------------------------------------

    /**
     * Starts the pgvector container if not already running.
     *
     * Must be called explicitly from within a task action (doFirst/doLast),
     * never during Gradle configuration phase. Idempotent — safe to call
     * multiple times when tasks are chained.
     */
    @Synchronized
    fun start() {
        if (started) return
        if (parameters.externalPort.isPresent) {
            // External pgvector (e.g. Testcontainers in tests) — skip Docker
            port = parameters.externalPort.get()
            println("🐘 [PgVectorService] Using external pgvector at localhost:$port")
        } else {
            doStart()
        }
        started = true
    }

    private fun doStart() {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .maxConnections(10)
            .connectionTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofSeconds(30))
            .build()

        docker = DockerClientImpl.getInstance(config, httpClient)

        // Pull image if not present locally
        val image = parameters.image.get()
        ensureImagePresent(image)

        // Create container — bind internal 5432 to a random host port (0)
        val pgPort     = ExposedPort.tcp(INTERNAL_PORT)
        val portBinding = Ports().also { it.bind(pgPort, Ports.Binding.bindPort(0)) }

        val container: CreateContainerResponse = docker
            .createContainerCmd(image)
            .withExposedPorts(pgPort)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withPortBindings(portBinding)
                    .withAutoRemove(true)   // container removed as soon as it stops
            )
            .withEnv(
                "POSTGRES_DB=${parameters.database.get()}",
                "POSTGRES_USER=${parameters.user.get()}",
                "POSTGRES_PASSWORD=${parameters.password.get()}",
            )
            .exec()

        containerId = container.id
        docker.startContainerCmd(containerId).exec()

        // Resolve the actual host port assigned by Docker
        val bindings = docker.inspectContainerCmd(containerId).exec()
            .networkSettings.ports.bindings
        port = bindings[pgPort]
            ?.firstOrNull()
            ?.hostPortSpec
            ?.toIntOrNull()
            ?: error("[PgVectorService] Could not resolve mapped port for container $containerId")

        println("🐘 [PgVectorService] Container started — pgvector available at localhost:$port")

        // Wait until PostgreSQL is ready to accept connections
        waitUntilReady()
    }

    // -------------------------------------------------------------------------
    // Lifecycle — close() called by Gradle at end of build
    // -------------------------------------------------------------------------

    override fun close() {
        if (!started) return  // start() was never called — nothing to stop
        if (parameters.externalPort.isPresent) return  // external mode — nothing to stop
        runCatching {
            docker.stopContainerCmd(containerId).withTimeout(10).exec()
            println("🐘 [PgVectorService] Container stopped.")
        }.onFailure { e ->
            println("⚠️  [PgVectorService] Could not stop container $containerId: ${e.message}")
        }
        runCatching { docker.close() }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ensureImagePresent(image: String) {
        val available = docker.listImagesCmd()
            .withImageNameFilter(image)
            .exec()
            .isNotEmpty()

        if (!available) {
            println("🐘 [PgVectorService] Pulling image $image…")
            docker.pullImageCmd(image)
                .start()
                .awaitCompletion()
            println("🐘 [PgVectorService] Image pulled.")
        }
    }

    /**
     * Polls `pg_isready` inside the container until PostgreSQL accepts
     * connections or [Params.startupTimeout] is reached.
     */
    private fun waitUntilReady() {
        val timeoutMs  = parameters.startupTimeout.get() * 1_000L
        val pollMs     = 500L
        val deadline   = System.currentTimeMillis() + timeoutMs
        var ready      = false

        while (System.currentTimeMillis() < deadline) {
            val exec = docker.execCreateCmd(containerId)
                .withCmd(
                    "pg_isready",
                    "-U", parameters.user.get(),
                    "-d", parameters.database.get(),
                )
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()

            docker.execStartCmd(exec.id)
                .start()
                .awaitCompletion()  // blocks until pg_isready exits

            val exitCode = docker.inspectExecCmd(exec.id).exec().exitCodeLong
            if (exitCode == 0L) {
                ready = true
                break
            }
            Thread.sleep(pollMs)
        }

        if (!ready) error(
            "[PgVectorService] PostgreSQL did not become ready within " +
                    "${parameters.startupTimeout.get()}s on port $port."
        )

        println("🐘 [PgVectorService] PostgreSQL ready on port $port.")
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val INTERNAL_PORT = 5432

        /** Default parameter values — mirrors docker-compose.yml. */
        const val DEFAULT_IMAGE    = "pgvector/pgvector:pg17"
        const val DEFAULT_DATABASE = "slider_rag"
        const val DEFAULT_USER     = "slider"
        const val DEFAULT_PASSWORD = "slider"
        const val DEFAULT_TABLE    = "embeddings"
        const val DEFAULT_TIMEOUT  = 30L  // seconds
    }
}