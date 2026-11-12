package com.reaj.bugassistant.model

/** What the device can afford. [modelFiles] maps each `*.gguf` / `*.gguf.part` file in the model dir to its size. */
data class DeviceResources(
    val totalRamBytes: Long,
    val isLowRamDevice: Boolean,
    val usableStorageBytes: Long,
    val modelFiles: Map<String, Long> = emptyMap(),
)

sealed interface ModelChoice {
    val spec: ModelSpec

    data class Use(override val spec: ModelSpec, val reason: Reason) : ModelChoice
    /** Even the small model does not fit. [neededBytes] is how much more free space is required. */
    data class NotEnoughStorage(override val spec: ModelSpec, val neededBytes: Long) : ModelChoice

    enum class Reason { CAPABLE_DEVICE, LOW_RAM, LOW_STORAGE }
}

/** Picks the large model when the device has the RAM and storage for it, otherwise the small one. */
object ModelSelector {
    /**
     * 3B Q4_K_M needs ~2 GB of weights plus ~0.5 GB KV cache at 4k context. ActivityManager reports
     * less than the marketed RAM (a "6 GB" phone shows ~5.5 GB), so this admits 6 GB phones and up.
     */
    const val MIN_RAM_FOR_LARGE: Long = 5_500L * 1024 * 1024

    /** Space left free after the download, so the phone is not filled to the last byte. */
    const val STORAGE_HEADROOM: Long = 512L * 1024 * 1024

    fun select(
        res: DeviceResources,
        large: ModelSpec = Models.LARGE,
        small: ModelSpec = Models.SMALL,
    ): ModelChoice {
        val lowRam = res.isLowRamDevice || res.totalRamBytes < MIN_RAM_FOR_LARGE
        if (!lowRam && missingBytes(large, res) <= 0) return ModelChoice.Use(large, ModelChoice.Reason.CAPABLE_DEVICE)

        val missing = missingBytes(small, res)
        if (missing > 0) return ModelChoice.NotEnoughStorage(small, missing)
        return ModelChoice.Use(small, if (lowRam) ModelChoice.Reason.LOW_RAM else ModelChoice.Reason.LOW_STORAGE)
    }

    /**
     * Extra free space needed to finish [spec], or <= 0 if it fits. Bytes already downloaded count
     * toward it, and other model files count as free because they are deleted once a model is chosen.
     */
    fun missingBytes(spec: ModelSpec, res: DeviceResources): Long {
        // ModelDownloader only renames a file into place after its SHA-256 matches, so it is complete.
        if (spec.fileName in res.modelFiles) return 0
        val partial = res.modelFiles[spec.fileName + ".part"] ?: 0
        val reclaimable = res.modelFiles.filterKeys { it != spec.fileName + ".part" }.values.sum()
        val free = res.usableStorageBytes + reclaimable
        return (spec.sizeBytes - partial) + STORAGE_HEADROOM - free
    }
}
