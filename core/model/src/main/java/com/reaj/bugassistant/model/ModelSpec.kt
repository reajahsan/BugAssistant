package com.reaj.bugassistant.model

/** A downloadable GGUF model, pinned by size and SHA-256 so a corrupt or swapped file is rejected. */
data class ModelSpec(
    val id: String,
    /** Shown in the UI. A product name, so it is not localized. */
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

object Models {
    /** Llama 3.2 1B Instruct, Q4_K_M. Public on Hugging Face, no login required. */
    val LLAMA_3_2_1B_Q4_K_M = ModelSpec(
        id = "llama-3.2-1b-instruct-q4_k_m",
        displayName = "Llama 3.2 1B",
        url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        sizeBytes = 807_694_464,
        sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
    )

    /** Llama 3.2 3B Instruct, Q4_K_M. Better reasoning over stack traces; ~2-3x slower than 1B. */
    val LLAMA_3_2_3B_Q4_K_M = ModelSpec(
        id = "llama-3.2-3b-instruct-q4_k_m",
        displayName = "Llama 3.2 3B",
        url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        sizeBytes = 2_019_377_696,
        sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
    )

    /** Preferred model; [ModelSelector] falls back to [SMALL] on low-RAM or low-storage devices. */
    val LARGE = LLAMA_3_2_3B_Q4_K_M
    val SMALL = LLAMA_3_2_1B_Q4_K_M
}
