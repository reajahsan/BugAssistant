package com.reaj.bugassistant.llama

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface GenEvent {
    data class Token(val text: String) : GenEvent
    data class Done(val tokens: Int) : GenEvent
}

/** Coroutine-friendly wrapper. Every native call runs on one dedicated thread. */
class LlamaEngine : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-inference") }
    private val dispatcher = executor.asCoroutineDispatcher()
    @Volatile private var handle = 0L

    suspend fun load(path: String, nCtx: Int = 4096, nThreads: Int = defaultThreads()) =
        withContext(dispatcher) {
            if (handle != 0L) { LlamaNative.free(handle); handle = 0L }
            val h = LlamaNative.loadModel(path, nCtx, nThreads)
            check(h != 0L) { "Failed to load model (see logcat tag LlamaJNI)" }
            handle = h
        }

    fun generate(
        system: String, user: String, grammar: String = "", maxTokens: Int = 512,
    ): Flow<GenEvent> = callbackFlow {
        val h = handle
        check(h != 0L) { "Model not loaded" }

        launch(dispatcher) {
            try {
                val prompt = LlamaNative.formatChat(h, system.toByteArray(), user.toByteArray())
                val n = LlamaNative.generate(h, prompt, grammar, maxTokens) { bytes ->
                    trySend(GenEvent.Token(String(bytes, Charsets.UTF_8)))
                }
                if (n < 0) close(IllegalStateException("Generation failed: ${LlamaNative.errorMessage(n)}"))
                else { trySend(GenEvent.Done(n)); close() }
            } catch (e: Throwable) {
                close(e)
            }
        }
        // Collector cancelled (Stop button) -> set the native cancel flag
        awaitClose { if (handle == h) LlamaNative.cancel(h) }
    }.buffer(Channel.UNLIMITED)

    override fun close() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)          // stop a running generation quickly
        executor.execute { if (handle != 0L) { LlamaNative.free(handle); handle = 0L } }
        executor.shutdown()
    }

    companion object {
        fun defaultThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    }
}
