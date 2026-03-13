package com.cheroliv.slider.ai

import dev.langchain4j.data.document.Document
import dev.langchain4j.data.document.DocumentSplitter
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.EmbeddingSearchResult
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore
import org.gradle.api.Project
import java.io.File
import java.security.MessageDigest

/**
 * Manages the RAG (Retrieval-Augmented Generation) store for the Slider plugin.
 *
 * ## Storage backend
 * [PgVectorEmbeddingStore] backed by the PostgreSQL+pgvector container managed
 * by [PgVectorService]. The service handles the full Docker lifecycle — start,
 * port assignment, readiness check, and stop — so [RagManager] only needs to
 * build the store from the coordinates exposed by the service.
 *
 * ## Same code, every context
 * Because [PgVectorService] starts the container via the Docker API (docker-java),
 * there is no difference between local and CI execution. No script, no
 * environment variable, no `services:` block in the GitHub Actions workflow.
 *
 * ## Embedding model
 * [AllMiniLmL6V2EmbeddingModel] runs entirely in-process via ONNX.
 * No network call, no extra API key, compatible with every AI provider.
 * Output dimension: 384.
 *
 * ## Incremental indexation
 * Each source file is tracked by its relative path + SHA-256 hash stored as
 * metadata alongside every chunk in pgvector (`source`, `file_hash`).
 * Only new or modified files are re-chunked and re-inserted on each run.
 * Deleted or renamed files are not pruned automatically — use [reindex] for
 * a full reset.
 *
 * ## Indexed sources (relative to project root)
 *   - `slides/**/*.adoc`               AsciiDoc syntax examples + generated decks
 *   - `slides/**/*-deck-context.yml`   DeckContext structure examples
 *   - `README*.adoc`                    Plugin documentation
 *
 * ## Required dependencies in slider-plugin/build.gradle.kts
 * ```kotlin
 * implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.0.0")
 * implementation("dev.langchain4j:langchain4j-pgvector:1.0.0")
 * implementation("com.github.docker-java:docker-java-core:3.3.6")
 * implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")
 * ```
 */
object RagManager {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private const val CHUNK_SIZE    = 512   // characters per chunk
    private const val CHUNK_OVERLAP = 64    // overlap between consecutive chunks
    private const val MAX_RESULTS   = 4     // chunks returned per query
    private const val MIN_SCORE     = 0.65  // cosine-similarity threshold
    private const val EMBEDDING_DIM = 384   // AllMiniLmL6V2 output dimension

    // Metadata keys stored alongside each chunk in pgvector
    private const val META_SOURCE    = "source"     // relative file path
    private const val META_FILE_HASH = "file_hash"  // SHA-256 of file content

    // -------------------------------------------------------------------------
    // Lazily initialised embedding model (shared across all tasks in the build)
    // -------------------------------------------------------------------------

    private val embeddingModel: EmbeddingModel by lazy {
        AllMiniLmL6V2EmbeddingModel()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the [MAX_RESULTS] most relevant text chunks for [query].
     *
     * Triggers incremental indexation before querying so the store always
     * reflects the current state of the source files.
     *
     * @param pgService the [PgVectorService] instance provided by Gradle
     */
    fun Project.retrieve(query: String, pgService: PgVectorService): String {
        val store = buildStore(pgService)
        ensureIndexed(store)

        val queryEmbedding: Embedding = embeddingModel.embed(query).content()
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(MAX_RESULTS)
            .minScore(MIN_SCORE)
            .build()
        val result: EmbeddingSearchResult<TextSegment> = store.search(request)

        if (result.matches().isEmpty()) return ""

        return result.matches().joinToString(separator = "\n\n---\n\n") { match ->
            val source = match.embedded().metadata().getString(META_SOURCE) ?: "unknown"
            "### source: $source\n${match.embedded().text()}"
        }
    }

    /**
     * Drops all rows in the embeddings table and re-indexes every source file
     * from scratch. Use this after deleting or renaming source files.
     *
     * Bound to the `reindexRag` Gradle task.
     *
     * @param pgService the [PgVectorService] instance provided by Gradle
     */
    fun Project.reindex(pgService: PgVectorService) {
        println("🗑️  [RAG] Dropping existing index…")
        val store: PgVectorEmbeddingStore = buildStore(pgService)
        // removeAll() exists on PgVectorEmbeddingStore (concrete class)
        // but not on the EmbeddingStore interface — explicit type required.
        store.removeAll()
        println("🔍 [RAG] Full re-index starting…")
        indexSources(store, forceAll = true)
        println("✅ [RAG] Full re-index complete.")
    }

    // -------------------------------------------------------------------------
    // Store construction — uses coordinates from PgVectorService
    // -------------------------------------------------------------------------

    /**
     * Builds a [PgVectorEmbeddingStore] from the coordinates exposed by
     * [PgVectorService]. The port comes from [PgVectorService.port] which
     * was resolved from the Docker API after container start — no hardcoded
     * value, no env variable lookup needed here.
     */
    private fun buildStore(pgService: PgVectorService): PgVectorEmbeddingStore {
        // The pgvector Docker container has no SSL configured.
        // The PostgreSQL JDBC driver negotiates SSL by default → EOFException.
        // PgVectorEmbeddingStore.builder() uses PGSimpleDataSource internally
        // with no way to pass sslmode. We override it via the system property
        // recognised by the JDBC driver for all new connections.
        System.setProperty("ssl", "false")
        System.setProperty("sslmode", "disable")
        return PgVectorEmbeddingStore.builder()
            .host("localhost")
            .port(pgService.port)
            .database(pgService.database)
            .user(pgService.user)
            .password(pgService.password)
            .table(pgService.table)
            .dimension(EMBEDDING_DIM)
            .createTable(true)
            .dropTableFirst(false)
            .build()
    }

    // -------------------------------------------------------------------------
    // Incremental indexation
    // -------------------------------------------------------------------------

    private fun Project.ensureIndexed(store: PgVectorEmbeddingStore) {
        val sources = collectSources()
        if (sources.isEmpty()) {
            println("⚠️  [RAG] No indexable files found — RAG context will be empty.")
            return
        }
        indexSources(store, forceAll = false)
    }

    private fun Project.indexSources(
        store: PgVectorEmbeddingStore,
        forceAll: Boolean,
    ) {
        val sources  = collectSources()
        val splitter: DocumentSplitter =
            DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP)
        var indexed  = 0
        var skipped  = 0

        sources.forEach { file ->
            val relativePath = file.relativeTo(projectDir).path
            val fileHash     = file.sha256()
            val text         = file.readText()
            if (text.isBlank()) return@forEach

            if (!forceAll && isAlreadyIndexed(store, relativePath, fileHash)) {
                skipped++
                return@forEach
            }

            // Remove stale chunks for this file before re-inserting
            runCatching {
                store.removeAll(metadataKey(META_SOURCE).isEqualTo(relativePath))
            }

            val document = Document.from(
                text,
                Metadata.from(META_SOURCE, relativePath)
                    .put(META_FILE_HASH, fileHash),
            )
            val segments: List<TextSegment> = splitter.split(document)
            val embeddings: List<Embedding> = embeddingModel.embedAll(segments).content()
            store.addAll(embeddings, segments)
            indexed++
        }

        when {
            indexed > 0 && skipped > 0 ->
                println("✅ [RAG] Indexed $indexed file(s), skipped $skipped unchanged.")
            indexed > 0 ->
                println("✅ [RAG] Indexed $indexed file(s).")
            else ->
                println("✅ [RAG] All ${sources.size} file(s) already up to date.")
        }
    }

    private fun isAlreadyIndexed(
        store: PgVectorEmbeddingStore,
        filePath: String,
        fileHash: String,
    ): Boolean = runCatching {
        val dummyEmbedding = Embedding.from(FloatArray(EMBEDDING_DIM) { 0f })
        val filter = metadataKey(META_SOURCE).isEqualTo(filePath)
            .and(metadataKey(META_FILE_HASH).isEqualTo(fileHash))
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(dummyEmbedding)
            .maxResults(1)
            .minScore(-1.0)
            .filter(filter)
            .build()
        store.search(request).matches().isNotEmpty()
    }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // Source collection
    // -------------------------------------------------------------------------

    private fun Project.collectSources(): List<File> = buildList {
        projectDir.walkTopDown()
            .filter { it.isFile && it.extension == "adoc" }
            .forEach { add(it) }

        projectDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-deck-context.yml") }
            .forEach { add(it) }

        projectDir.listFiles { f ->
            f.isFile && f.name.startsWith("README") && f.name.endsWith(".adoc")
        }?.forEach { add(it) }
    }

    // -------------------------------------------------------------------------
    // File hash helper
    // -------------------------------------------------------------------------

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}