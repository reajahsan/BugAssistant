package com.reaj.bugassistant.llama

/** Native code calls this with each complete UTF-8 piece of generated text. */
fun interface TokenCallback {
    fun onToken(bytes: ByteArray)
}

/**
 * Kotlin side of app/src/main/cpp/llama-android.cpp.
 * Text crosses JNI as UTF-8 byte arrays to avoid JNI's "modified UTF-8" problems.
 * All calls except [cancel] must come from one thread (see [LlamaEngine]).
 */
object LlamaNative {
    init {
        System.loadLibrary("llama-android")
        backendInit()
    }

    private external fun backendInit()

    /** Returns a session handle, or 0 on failure (details in logcat, tag LlamaJNI). */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    /** Wraps system + user text in the chat template embedded in the GGUF. */
    external fun formatChat(handle: Long, system: ByteArray, user: ByteArray): ByteArray

    /**
     * Blocking. [grammar] is GBNF, or "" for none.
     * Returns the number of generated tokens, or a negative error code (see [errorMessage]).
     */
    external fun generate(
        handle: Long, prompt: ByteArray, grammar: String,
        maxTokens: Int, callback: TokenCallback,
    ): Int

    /** Safe to call from any thread. */
    external fun cancel(handle: Long)

    external fun free(handle: Long)

    fun errorMessage(code: Int): String = when (code) {
        -1 -> "tokenization failed"
        -2 -> "prompt is too long for the context window"
        -3 -> "llama_decode failed"
        -4 -> "grammar failed to parse"
        else -> "native error $code"
    }
}
